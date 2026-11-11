package br.org.gescs.lojinha

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * Casca Android que roda DENTRO da Moderninha Smart 2: abre a página /maquininha
 * do GESCS numa WebView e expõe a ponte de pagamento (PagBankBridge) para ela.
 *
 * Fluxo: a vendedora confirma a venda no GESCS (celular/PC) → o GESCS grava a
 * cobrança no Supabase → a página /maquininha, aberta aqui, pega a cobrança e
 * chama window.AndroidPagamento.pagar(...) → a maquininha pede o cartão.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var bridge: PagBankBridge
    private lateinit var urlSistema: String
    private lateinit var hostsPermitidos: Set<String>

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Caixa não deve apagar a tela no meio do atendimento.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Em builds de debug dá para inspecionar a WebView pelo chrome://inspect no PC.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        // Endereço do GESCS: definido em gradle.properties (urlSistema).
        urlSistema = getString(R.string.url_sistema)
        hostsPermitidos = setOfNotNull(HOST_ASSETS, Uri.parse(urlSistema).host)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true   // localStorage da UI (carrinho, catálogo etc.)
        }

        // Serve app/src/main/assets/ em https://appassets.androidplatform.net/assets/...
        // Assim um build do Vite/React (que usa <script type="module">) funciona
        // offline, sem os bloqueios de CORS que o file:// causa.
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            // A ponte de pagamento fica visível para qualquer página aberta na WebView,
            // então só deixamos navegar pelo GESCS e pelas páginas locais do app.
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = request.url.host !in hostsPermitidos

            // Sem internet ao abrir: mostra uma tela local com botão "Tentar de novo".
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame && request.url.host != HOST_ASSETS) {
                    view.loadUrl("$URL_SEM_CONEXAO?voltar=" + Uri.encode(urlSistema))
                }
            }
        }

        // Expõe a ponte de pagamento ao JavaScript como window.AndroidPagamento
        bridge = PagBankBridge(this, webView)
        webView.addJavascriptInterface(bridge, "AndroidPagamento")

        webView.loadUrl(urlSistema)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // API antiga, mas é a que existe no Android das maquininhas
    override fun onBackPressed() {
        // Botão voltar navega dentro da UI; não fecha o app no meio de uma venda.
        if (webView.canGoBack()) webView.goBack()
        else if (!bridge.operacaoEmAndamento()) super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        /** Páginas locais (app/src/main/assets e web/) servidas pelo WebViewAssetLoader. */
        const val HOST_ASSETS = "appassets.androidplatform.net"
        const val URL_SEM_CONEXAO = "https://$HOST_ASSETS/assets/sem-conexao.html"
    }
}
