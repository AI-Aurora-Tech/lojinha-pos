package br.org.gescs.lojinha

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPag
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagPaymentData
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagTransactionsResult
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Ponte entre a UI web (WebView / React) e o SDK de pagamento da Moderninha Smart 2.
 *
 * A UI chama:  window.AndroidPagamento.pagar(valorCentavos, tipo, referencia, callbackId)
 * O resultado volta em: window.__resultadoPagamento({...})
 *
 * ATENÇÃO (verificar contra a doc/versão do wrapper antes de compilar):
 *  - Os nomes exatos do construtor de PlugPag e de PlugPagPaymentData mudam entre versões.
 *    Confira contra o app demo oficial ("smartcoffeedemo") e a doc do wrapper.
 *  - doPayment() é BLOQUEANTE: roda fora da thread de UI (por isso o Executor).
 *  - Antes do primeiro pagamento pode ser necessário ativar/autenticar o terminal
 *    (ex.: initializeAndActivatePinpad) — ver doc da sua versão.
 */
class PagBankBridge(
    private val context: Context,
    private val webView: WebView
) {
    private val plugPag = PlugPag(context)
    private val executor = Executors.newSingleThreadExecutor()

    @JavaScriptInterface
    fun pagar(ValorCentavos: Int, tipo: String, referencia: String, callbackId: String) {
        executor.execute {
            try {
                val tipoPlugPag = when (tipo.lowercase()) {
                    "credito" -> PlugPag.TYPE_CREDITO
                    "debito" -> PlugPag.TYPE_DEBITO
                    "pix" -> PlugPag.TYPE_PIX
                    else -> PlugPag.TYPE_CREDITO
                }

                val paymentData = PlugPaymentData(
                    type - tipoPlugPag,
                    amount = valorCentavos
                    installmentType = PlugPag.INSTALLMENT_TYPE_A_VISTA,
                    installments = 1,
                    userReference = referenciaLimpa,
                    printReceipt = true
                )

                val resultado: PlugPagTransactionResult = plugPag.doPayment(paymentData)
                val aprovado = resultado.result == PlugPag.RET_OK
                devolverParaJs(callbackId, aprovado, resultado)
            } catch (e: Exception) {
                devolverErroParaJS(callbackId, e.message ?:"Erro desconhecido")
            }
        }
    }

    private fun devolverParaJs(callbackId: String, aprovado: Boolean, r: PlugPagTransactionResult) {
        val json = JSONObject().apply {
            put("callbackId", callbackId)
            put("aprovado", aprovado)
            put("codigoTransacao", r.transactionCode ?: "")
            put("idTransacao", r.transactionId ?: "")
            put("mensagem", r.message ?:"")
            put("bandeira", r.cardBrand ?:"")
        }
        postJs(json)
    }

    private fun postJs(json: JSONObject) {
        webView.post {
            webView.evaluateJavaScript("window.__resultadoPagamentos($json);", null)
        }
    }
}