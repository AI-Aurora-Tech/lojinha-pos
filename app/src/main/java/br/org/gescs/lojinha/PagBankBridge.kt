package br.org.gescs.lojinha

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPag
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagActivationData
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagCustomPrinterLayout
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagEventData
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagEventListener
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagPaymentData
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagPrintResult
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagTransactionResult
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagVoidData
import br.com.uol.pagseguro.plugpagservice.wrapper.exception.PlugPagException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ponte entre a UI web (WebView) e o SDK de pagamento da Moderninha Smart 2
 * (PlugPagServiceWrapper 1.35.0). Segue o app de exemplo oficial da PagBank
 * (github.com/pagseguro/pagseguro-plugpagservicewrapper-smartcoffeedemo).
 *
 * Do JavaScript (todas as chamadas retornam na hora; o resultado chega depois):
 *   AndroidPagamento.pagar(JSON.stringify({ callbackId, valorCentavos, metodo, parcelas, parcelamento, referencia }))
 *   AndroidPagamento.cancelar()
 *   AndroidPagamento.estornar(JSON.stringify({ callbackId, codigoTransacao, idTransacao, metodo }))
 *   AndroidPagamento.calcularParcelas(valorCentavos, parcelamento, callbackId)
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
    private val appContext = context.applicationContext
    private val plugPag = Maquininha.plugPag(appContext)
    private val filaSdk = Maquininha.filaSdk
    private val emAndamento = Maquininha.emAndamento

    private var textoSenha = ""

    init {
        // Recebe as mensagens do serviço para orientar o cliente na tela da UI.
        plugPag.setEventListener(object : PlugPagEventListener {
            override fun onEvent(data: PlugPagEventData) = repassarEvento(data)
        })

        // Popup que pergunta se imprime a via do cliente depois da venda.
        filaSdk.execute {
            try {
                plugPag.setPlugPagCustomPrinterLayout(
                    PlugPagCustomPrinterLayout(
                        "Imprimir via do cliente?",
                        "#000000", "#FFFFFF", "#A0A0A0", "#FFFFFF",
                        "#000000", "#808080", "#FFFFFF",
                        60 // segundos até o popup fechar sozinho
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "não foi possível configurar o popup de impressão", e)
            }
        }
    }

    fun operacaoEmAndamento(): Boolean = emAndamento.get()

    // ------------------------------------------------------------------ pagamento

    @JavascriptInterface
    fun pagar(pedidoJson: String) {
        val pedido = lerJson(pedidoJson) ?: return
        val callbackId = pedido.optString("callbackId")

        // Valida antes de ocupar a maquininha.
        val dados = try {
            montarPagamento(pedido)
        } catch (e: IllegalArgumentException) {
            responderErro(callbackId, "DADOS_INVALIDOS", e.message ?: "Dados de pagamento inválidos.")
            return
        }

        if (!emAndamento.compareAndSet(false, true)) {
            responderErro(callbackId, "OCUPADO", DICAS.getValue("ocupado"))
            return
        }

        filaSdk.execute {
            try {
                if (!terminalPronto(callbackId)) return@execute
                textoSenha = ""
                val r = plugPag.doPayment(dados)
                responderTransacao(callbackId, r, metodoDoTipo(dados.type))
            } catch (e: Exception) {
                responderExcecao(callbackId, e)
            } finally {
                emAndamento.set(false)
            }
        }
    }

    private fun montarPagamento(pedido: JSONObject): PlugPagPaymentData {
        val valorCentavos = pedido.optInt("valorCentavos", 0)
        require(valorCentavos >= VALOR_MINIMO) { "O valor mínimo para cobrar na maquininha é R$ 1,00." }
        require(valorCentavos <= VALOR_MAXIMO) { "Valor acima do limite da maquininha." }

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
        if (parcelas > 1) {
            require(valorCentavos >= VALOR_MINIMO_PARCELADO) { "Parcelamento só a partir de R$ 10,00." }
        }

        return PlugPagPaymentData(
            tipo,
            valorCentavos,
            if (parcelas == 1) PlugPag.INSTALLMENT_TYPE_A_VISTA else tipoParcelamento(pedido.optString("parcelamento")),
            parcelas,
            limparReferencia(pedido.optString("referencia")),
            pedido.optBoolean("imprimirComprovante", true)
        )
    }

    /**
     * Cancela a operação em andamento (ex.: cliente desistiu enquanto a máquina pedia o cartão).
     * A resposta chega pelo pagar()/estornar() que estava aberto, como cancelado.
     */
    @JavascriptInterface
    fun cancelar() {
        Maquininha.filaAbort.execute {
            try {
                plugPag.abort()
            } catch (e: Exception) {
                Log.w(TAG, "abort falhou", e)
            }
        }
    }

    /** Opções de parcelamento com o valor de cada parcela, para mostrar ao cliente antes de cobrar. */
    @JavascriptInterface
    fun calcularParcelas(valorCentavos: Int, parcelamento: String, callbackId: String) {
        filaSdk.execute {
            try {
                val opcoes = JSONArray().put(opcaoParcela(1, valorCentavos, valorCentavos))
                if (valorCentavos >= VALOR_MINIMO_PARCELADO) {
                    // Lista vazia quando o valor não permite parcelar.
                    plugPag.calculateInstallments("$valorCentavos", tipoParcelamento(parcelamento))
                        .forEach { opcoes.put(opcaoParcela(it.quantity, it.amount, it.total)) }
                }
                responder(callbackId, JSONObject().put("ok", true).put("opcoes", opcoes))
            } catch (e: Exception) {
                responderExcecao(callbackId, e)
            }
        }
    }

    private fun opcaoParcela(parcelas: Int, valorParcela: Int, total: Int) =
        JSONObject()
            .put("parcelas", parcelas)
            .put("valorParcelaCentavos", valorParcela)
            .put("totalCentavos", total)

    // ------------------------------------------------------------------ pós-venda

    /** Estorno (devolução) de uma venda aprovada. Use os dados devolvidos pelo pagar(). */
    @JavascriptInterface
    fun estornar(dadosJson: String) {
        val dados = lerJson(dadosJson) ?: return
        val callbackId = dados.optString("callbackId")
        val codigoTransacao = dados.optString("codigoTransacao")
        val idTransacao = dados.optString("idTransacao")
        if (codigoTransacao.isEmpty() || idTransacao.isEmpty()) {
            responderErro(callbackId, "DADOS_INVALIDOS", "Informe codigoTransacao e idTransacao da venda.")
            return
        }

        // PIX (QR Code) usa um tipo de estorno diferente do cartão.
        val metodo = dados.optString("metodo").ifEmpty { metodoDoTipo(dados.optInt("tipoPagamento", 0)) }
        val tipoEstorno = if (metodo == "pix" || metodo == "qrcode") PlugPag.VOID_QRCODE else PlugPag.VOID_PAYMENT

        if (!emAndamento.compareAndSet(false, true)) {
            responderErro(callbackId, "OCUPADO", DICAS.getValue("ocupado"))
            return
        }

        filaSdk.execute {
            try {
                if (!terminalPronto(callbackId)) return@execute
                val r = plugPag.voidPayment(
                    PlugPagVoidData(
                        codigoTransacao,
                        idTransacao,
                        dados.optBoolean("imprimirComprovante", true),
                        tipoEstorno
                    )
                )
                responderTransacao(callbackId, r, metodo)
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
                responder(callbackId, transacaoParaJson(r, metodoDoTipo(r.paymentType)))
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
                if (!Maquininha.servicoInstalado(appContext)) {
                    responderErro(callbackId, "SERVICO_AUSENTE", DICAS.getValue("servico_ausente"))
                    return@execute
                }
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

    /**
     * Ativa a maquininha com o código de ativação da conta PagBank. Só use se
     * verificarTerminal() disser que não está autenticada (o terminal de
     * desenvolvimento já vem ativado).
     */
    @JavascriptInterface
    fun ativar(codigoAtivacao: String, callbackId: String) {
        filaSdk.execute {
            try {
                if (plugPag.isAuthenticated()) {
                    responder(callbackId, JSONObject().put("ok", true).put("mensagem", "A maquininha já está ativada."))
                    return@execute
                }
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

    /**
     * Checagens recomendadas pelo exemplo oficial antes de uma operação bloqueante.
     * Roda dentro da filaSdk. Se algo falhar, já responde ao JS e devolve false.
     */
    private fun terminalPronto(callbackId: String): Boolean {
        if (!Maquininha.servicoInstalado(appContext)) {
            responderErro(callbackId, "SERVICO_AUSENTE", DICAS.getValue("servico_ausente"))
            return false
        }
        if (plugPag.isServiceBusy()) {
            responderErro(callbackId, "OCUPADO", DICAS.getValue("ocupado"))
            return false
        }
        if (!plugPag.isAuthenticated()) {
            plugPag.startOnBoarding() // abre a tela de ativação da PagBank
            responderErro(callbackId, "NAO_AUTENTICADO", DICAS.getValue("nao_autenticado"))
            return false
        }
        return true
    }

    // ------------------------------------------------------------------ eventos

    private fun repassarEvento(data: PlugPagEventData) {
        val mensagem = when (data.eventCode) {
            PlugPagEventData.EVENT_CODE_DIGIT_PASSWORD -> {
                textoSenha += "*"
                "SENHA: $textoSenha"
            }
            PlugPagEventData.EVENT_CODE_NO_PASSWORD -> {
                textoSenha = ""
                "DIGITE A SENHA"
            }
            else -> data.customMessage ?: ""
        }
        val json = JSONObject()
            .put("codigo", data.eventCode)
            .put("mensagem", mensagem)
        executarJs("window.__eventoPagamento && window.__eventoPagamento($json);")
    }

    // ------------------------------------------------------------------ respostas

    private fun responderTransacao(callbackId: String, r: PlugPagTransactionResult, metodo: String) {
        val json = transacaoParaJson(r, metodo)
        json.put("aprovado", r.result == PlugPag.RET_OK)

        // O exemplo oficial aborta quando o serviço responde "ocupado" para destravá-lo.
        if (normalizarCodigo(r.errorCode) in setOf("SV03", "PP1017")) cancelar()

        responder(callbackId, json)
    }

    private fun transacaoParaJson(r: PlugPagTransactionResult, metodo: String): JSONObject {
        val ok = r.result == PlugPag.RET_OK
        val categoria = if (ok) "aprovado" else categoriaDoErro(r.errorCode)
        return JSONObject()
            .put("ok", ok)
            .put("categoria", categoria)
            .put("dica", DICAS[categoria] ?: (r.message ?: ""))
            .put("resultado", r.result ?: JSONObject.NULL)
            .put("codigoErro", r.errorCode ?: "")
            .put("mensagem", r.message ?: "")
            .put("metodo", metodo)
            .put("tipoPagamento", r.paymentType ?: JSONObject.NULL)
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
    }

    private fun responder(callbackId: String, json: JSONObject) {
        json.put("callbackId", callbackId)
        executarJs("window.__resultadoPagamento && window.__resultadoPagamento($json);")
    }

    private fun responderErro(callbackId: String, codigo: String, mensagem: String) {
        val categoria = categoriaDoErro(codigo)
        responder(
            callbackId,
            JSONObject()
                .put("ok", false)
                .put("aprovado", false)
                .put("categoria", categoria)
                .put("dica", mensagem)
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

    private fun lerJson(texto: String): JSONObject? =
        try {
            JSONObject(texto)
        } catch (e: Exception) {
            Log.e(TAG, "JSON inválido vindo da UI: $texto", e)
            null
        }

    companion object {
        private const val TAG = "PagBankBridge"

        // Limites usados pelo app de exemplo oficial.
        const val VALOR_MINIMO = 1_00
        const val VALOR_MINIMO_PARCELADO = 10_00
        const val VALOR_MAXIMO = 250_000_00

        fun tipoParcelamento(parcelamento: String): Int =
            if (parcelamento == "comprador") PlugPag.INSTALLMENT_TYPE_PARC_COMPRADOR
            else PlugPag.INSTALLMENT_TYPE_PARC_VENDEDOR

        fun metodoDoTipo(tipo: Int?): String = when (tipo) {
            PlugPag.TYPE_CREDITO -> "credito"
            PlugPag.TYPE_DEBITO -> "debito"
            PlugPag.TYPE_VOUCHER -> "voucher"
            PlugPag.TYPE_PIX -> "pix"
            PlugPag.TYPE_QRCODE, PlugPag.TYPE_QRCODE_CREDITO -> "qrcode"
            else -> ""
        }

        /** O PlugPag aceita referência de até 10 caracteres, só letras e números. */
        fun limparReferencia(referencia: String): String {
            val limpa = referencia.filter { it.isLetterOrDigit() && it.code < 128 }.take(10)
            return limpa.ifEmpty { (System.currentTimeMillis() % 10_000_000_000L).toString() }
        }

        private fun normalizarCodigo(codigo: String?): String =
            (codigo ?: "").replace(" ", "").uppercase()

        /**
         * Agrupa os códigos de erro mais comuns (lista do app de exemplo oficial)
         * em categorias que a UI consegue tratar.
         */
        fun categoriaDoErro(codigo: String?): String = when (normalizarCodigo(codigo)) {
            "0000" -> "aprovado"
            "C13", "B018" -> "cancelado"
            "R05", "R14", "R51", "R57", "R59", "R62", "R63", "R65", "R75", "R78", "R82", "R91",
            "B024", "M3011", "S46", "M512", "M3005", "M3025", "M5001" -> "recusado"
            "M831", "M815", "M826" -> "recusado_risco"
            "R55" -> "senha_incorreta"
            "A050", "A306", "A307", "A019", "B028", "A011", "A053", "A012" -> "erro_comunicacao"
            "C40", "C43", "C60", "C61", "C81", "C83", "C87" -> "erro_leitura"
            "C70", "C84" -> "tipo_cartao"
            "PP1051" -> "cartao_diferente"
            "S20" -> "duplicado"
            "C12", "B059" -> "tempo_esgotado"
            "SV03", "PP1047", "PP1017", "OCUPADO" -> "ocupado"
            "NAO_AUTENTICADO" -> "nao_autenticado"
            "SERVICO_AUSENTE" -> "servico_ausente"
            "DADOS_INVALIDOS" -> "dados_invalidos"
            else -> "outro"
        }

        /** Texto curto para a vendedora, por categoria. */
        val DICAS = mapOf(
            "aprovado" to "Pagamento aprovado.",
            "cancelado" to "Operação cancelada.",
            "recusado" to "Pagamento recusado. Peça outro cartão ou outra forma de pagamento.",
            "recusado_risco" to "Recusado por segurança. Tente de novo inserindo o chip.",
            "senha_incorreta" to "Senha incorreta. Tente de novo.",
            "erro_comunicacao" to "Falha de comunicação. Confira a internet da maquininha e tente de novo.",
            "erro_leitura" to "Erro na leitura do cartão. Tente de novo, de preferência inserindo o chip.",
            "tipo_cartao" to "O cartão não aceita essa modalidade. Confira se é crédito ou débito.",
            "cartao_diferente" to "Use o mesmo cartão da venda original.",
            "duplicado" to "Venda parecida acabou de ser feita (mesmo valor e cartão). Confira antes de cobrar de novo.",
            "tempo_esgotado" to "Tempo esgotado esperando o cartão ou o PIX.",
            "ocupado" to "A maquininha está ocupada com outra operação. Aguarde e tente de novo.",
            "nao_autenticado" to "A maquininha não está ativada. Conclua a ativação na tela da PagBank.",
            "servico_ausente" to "Serviço de pagamento da PagBank não encontrado. Este app só funciona na Moderninha Smart.",
        )
    }
}
