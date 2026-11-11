package br.org.gescs.lojinha

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPag
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagActivationData
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagEventData
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagEventListener
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagPaymentData
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagPrintResult
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagTransactionResult
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagVoidData
import br.com.uol.pagseguro.plugpagservice.wrapper.exception.PlugPagException
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ponte entre a UI web (WebView) e o SDK de pagamento da Moderninha Smart 2
 * (PlugPagServiceWrapper 1.35.0).
 *
 * Do JavaScript (todas as chamadas retornam na hora; o resultado chega depois):
 *   AndroidPagamento.pagar(JSON.stringify({ callbackId, valorCentavos, metodo, parcelas, referencia }))
 *   AndroidPagamento.cancelar()
 *   AndroidPagamento.estornar(JSON.stringify({ callbackId, codigoTransacao, idTransacao }))
 *   AndroidPagamento.reimprimir(via, callbackId)          // via = "cliente" | "estabelecimento"
 *   AndroidPagamento.ultimaTransacao(callbackId)
 *   AndroidPagamento.verificarTerminal(callbackId)
 *   AndroidPagamento.ativar(codigoAtivacao, callbackId)
 *
 * Para o JavaScript:
 *   window.__resultadoPagamento({ callbackId, ok, ... })   // resposta de cada chamada
 *   window.__eventoPagamento({ codigo, mensagem })         // "APROXIME OU INSIRA O CARTÃO", "SENHA: ****"...
 *
 * O arquivo web/pagamento.js embrulha tudo isso em Promises para usar na UI.
 */
class PagBankBridge(
    context: Context,
    private val webView: WebView
) {
    private val plugPag = PlugPag(context.applicationContext)

    // Operações do SDK são BLOQUEANTES: rodam numa fila fora da thread de UI.
    private val filaSdk = Executors.newSingleThreadExecutor()

    // abort() precisa de outra thread: a fila principal está presa no doPayment().
    private val filaAbort = Executors.newSingleThreadExecutor()

    private val emAndamento = AtomicBoolean(false)
    private var digitosSenha = 0

    init {
        plugPag.setEventListener(object : PlugPagEventListener {
            override fun onEvent(data: PlugPagEventData) = repassarEvento(data)
        })
    }

    fun operacaoEmAndamento(): Boolean = emAndamento.get()

    fun encerrar() {
        filaSdk.shutdown()
        filaAbort.shutdown()
    }

    // ------------------------------------------------------------------ pagamento

    @JavascriptInterface
    fun pagar(pedidoJson: String) {
        val pedido = try {
            JSONObject(pedidoJson)
        } catch (e: Exception) {
            Log.e(TAG, "pedido inválido: $pedidoJson", e)
            return
        }
        val callbackId = pedido.optString("callbackId")

        if (!emAndamento.compareAndSet(false, true)) {
            responderErro(callbackId, "OCUPADO", "Já existe uma operação em andamento na maquininha.")
            return
        }

        filaSdk.execute {
            try {
                val dados = montarPagamento(pedido)
                digitosSenha = 0
                val r = plugPag.doPayment(dados)
                responder(callbackId, transacaoParaJson(r).put("aprovado", r.result == PlugPag.RET_OK))
            } catch (e: IllegalArgumentException) {
                responderErro(callbackId, "DADOS_INVALIDOS", e.message ?: "Dados de pagamento inválidos.")
            } catch (e: Exception) {
                responderExcecao(callbackId, e)
            } finally {
                emAndamento.set(false)
            }
        }
    }

    private fun montarPagamento(pedido: JSONObject): PlugPagPaymentData {
        val valorCentavos = pedido.optInt("valorCentavos", 0)
        require(valorCentavos > 0) { "Valor da venda precisa ser maior que zero." }

        val metodo = pedido.optString("metodo", "credito").lowercase()
        val tipo = when (metodo) {
            "credito" -> PlugPag.TYPE_CREDITO
            "debito" -> PlugPag.TYPE_DEBITO
            "pix" -> PlugPag.TYPE_PIX
            "voucher" -> PlugPag.TYPE_VOUCHER
            else -> throw IllegalArgumentException("Método de pagamento desconhecido: $metodo")
        }

        // Parcelamento só existe no crédito. "vendedor" = sem juros para o cliente.
        val parcelas = if (tipo == PlugPag.TYPE_CREDITO) pedido.optInt("parcelas", 1).coerceAtLeast(1) else 1
        val tipoParcelamento = when {
            parcelas == 1 -> PlugPag.INSTALLMENT_TYPE_A_VISTA
            pedido.optString("parcelamento") == "comprador" -> PlugPag.INSTALLMENT_TYPE_PARC_COMPRADOR
            else -> PlugPag.INSTALLMENT_TYPE_PARC_VENDEDOR
        }

        return PlugPagPaymentData(
            tipo,
            valorCentavos,
            tipoParcelamento,
            parcelas,
            limparReferencia(pedido.optString("referencia")),
            pedido.optBoolean("imprimirComprovante", true)
        )
    }

    /** Cancela a operação em andamento (ex.: cliente desistiu enquanto a máquina pedia o cartão). */
    @JavascriptInterface
    fun cancelar() {
        filaAbort.execute {
            try {
                plugPag.abort()
            } catch (e: Exception) {
                Log.w(TAG, "abort falhou", e)
            }
        }
    }

    // ------------------------------------------------------------------ pós-venda

    /** Estorno (cancelamento) de uma venda aprovada. Usa os dados devolvidos pelo pagar(). */
    @JavascriptInterface
    fun estornar(dadosJson: String) {
        val dados = try {
            JSONObject(dadosJson)
        } catch (e: Exception) {
            Log.e(TAG, "estorno inválido: $dadosJson", e)
            return
        }
        val callbackId = dados.optString("callbackId")

        if (!emAndamento.compareAndSet(false, true)) {
            responderErro(callbackId, "OCUPADO", "Já existe uma operação em andamento na maquininha.")
            return
        }

        filaSdk.execute {
            try {
                val r = plugPag.voidPayment(
                    PlugPagVoidData(
                        dados.getString("codigoTransacao"),
                        dados.getString("idTransacao"),
                        dados.optBoolean("imprimirComprovante", true)
                    )
                )
                responder(callbackId, transacaoParaJson(r).put("aprovado", r.result == PlugPag.RET_OK))
            } catch (e: Exception) {
                responderExcecao(callbackId, e)
            } finally {
                emAndamento.set(false)
            }
        }
    }

    @JavascriptInterface
    fun reimprimir(via: String, callbackId: String) {
        filaSdk.execute {
            try {
                val r: PlugPagPrintResult =
                    if (via == "estabelecimento") plugPag.reprintStablishmentReceipt()
                    else plugPag.reprintCustomerReceipt()
                responder(
                    callbackId,
                    JSONObject()
                        .put("ok", r.result == PlugPag.RET_OK)
                        .put("mensagem", r.message ?: "")
                        .put("codigoErro", r.errorCode ?: "")
                )
            } catch (e: Exception) {
                responderExcecao(callbackId, e)
            }
        }
    }

    /**
     * Última transação APROVADA na maquininha. Use para conferir uma venda se o app
     * fechou/travou antes de receber a resposta do pagar().
     */
    @JavascriptInterface
    fun ultimaTransacao(callbackId: String) {
        filaSdk.execute {
            try {
                val r = plugPag.getLastApprovedTransaction()
                responder(callbackId, transacaoParaJson(r))
            } catch (e: Exception) {
                responderExcecao(callbackId, e)
            }
        }
    }

    // ------------------------------------------------------------------ terminal

    @JavascriptInterface
    fun verificarTerminal(callbackId: String) {
        filaSdk.execute {
            try {
                responder(
                    callbackId,
                    JSONObject()
                        .put("ok", true)
                        .put("autenticado", plugPag.isAuthenticated())
                        .put("ocupado", plugPag.isServiceBusy())
                        .put("serial", plugPag.getSerialNumber() ?: "")
                        .put("modelo", plugPag.getModel() ?: "")
                )
            } catch (e: Exception) {
                responderExcecao(callbackId, e)
            }
        }
    }

    /** Ativa o terminal com o código de ativação da conta PagBank (só se verificarTerminal() disser que não está autenticado). */
    @JavascriptInterface
    fun ativar(codigoAtivacao: String, callbackId: String) {
        filaSdk.execute {
            try {
                val r = plugPag.initializeAndActivatePinpad(PlugPagActivationData(codigoAtivacao))
                responder(
                    callbackId,
                    JSONObject()
                        .put("ok", r.result == PlugPag.RET_OK)
                        .put("mensagem", r.errorMessage ?: "")
                        .put("codigoErro", r.errorCode ?: "")
                )
            } catch (e: Exception) {
                responderExcecao(callbackId, e)
            }
        }
    }

    // ------------------------------------------------------------------ eventos

    private fun repassarEvento(data: PlugPagEventData) {
        val mensagem = when (data.eventCode) {
            PlugPagEventData.EVENT_CODE_DIGIT_PASSWORD -> {
                digitosSenha++
                "SENHA: " + "*".repeat(digitosSenha)
            }
            PlugPagEventData.EVENT_CODE_NO_PASSWORD -> {
                digitosSenha = 0
                "SENHA:"
            }
            else -> data.customMessage ?: ""
        }
        val json = JSONObject()
            .put("codigo", data.eventCode)
            .put("mensagem", mensagem)
        executarJs("window.__eventoPagamento && window.__eventoPagamento($json);")
    }

    // ------------------------------------------------------------------ respostas

    private fun transacaoParaJson(r: PlugPagTransactionResult): JSONObject =
        JSONObject()
            .put("ok", r.result == PlugPag.RET_OK)
            .put("resultado", r.result ?: JSONObject.NULL)
            .put("codigoErro", r.errorCode ?: "")
            .put("mensagem", r.message ?: "")
            .put("codigoTransacao", r.transactionCode ?: "")
            .put("idTransacao", r.transactionId ?: "")
            .put("valor", r.amount ?: "")
            .put("data", r.date ?: "")
            .put("hora", r.time ?: "")
            .put("nsu", r.hostNsu ?: "")
            .put("autorizacao", r.autoCode ?: "")
            .put("bandeira", r.cardBrand ?: "")
            .put("parcelas", r.installments ?: JSONObject.NULL)
            .put("referencia", r.userReference ?: "")
            .put("serialTerminal", r.terminalSerialNumber ?: "")

    private fun responder(callbackId: String, json: JSONObject) {
        json.put("callbackId", callbackId)
        executarJs("window.__resultadoPagamento && window.__resultadoPagamento($json);")
    }

    private fun responderErro(callbackId: String, codigo: String, mensagem: String) {
        responder(
            callbackId,
            JSONObject()
                .put("ok", false)
                .put("aprovado", false)
                .put("codigoErro", codigo)
                .put("mensagem", mensagem)
        )
    }

    private fun responderExcecao(callbackId: String, e: Exception) {
        Log.e(TAG, "erro no PlugPag", e)
        val codigo = (e as? PlugPagException)?.errorCode ?: "ERRO_SDK"
        responderErro(callbackId, codigo, e.message ?: "Erro desconhecido na maquininha.")
    }

    private fun executarJs(script: String) {
        webView.post { webView.evaluateJavascript(script, null) }
    }

    companion object {
        private const val TAG = "PagBankBridge"

        /** O PlugPag aceita referência de até 10 caracteres, só letras e números. */
        fun limparReferencia(referencia: String): String {
            val limpa = referencia.filter { it.isLetterOrDigit() && it.code < 128 }.take(10)
            return limpa.ifEmpty { (System.currentTimeMillis() % 10_000_000_000L).toString() }
        }
    }
}
