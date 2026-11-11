package br.org.gescs.lojinha

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * Casca Android que roda DENTRO da Moderninha Smart 2: carrega a UI web da lojinha
 * numa WebView e expõe a ponte de pagamento (PagBankBridge) para o JavaScript.
 *
 * Fluxo: vendedora monta a venda na UI → clica "Concluir venda" → o JS chama
 * window.AndroidPagamento.pagar(...) → a maquininha pede o cartão ao cliente.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var bridge: PagBankBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Caixa não deve apagar a tela no meio do atendimento.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Em builds de debug dá para inspecionar a WebView pelo chrome://inspect no PC.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

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
            // então só deixamos navegar pelos endereços da própria lojinha.
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = request.url.host !in HOSTS_PERMITIDOS
        }

        // Expõe a ponte de pagamento ao JavaScript como window.AndroidPagamento
        bridge = PagBankBridge(this, webView)
        webView.addJavascriptInterface(bridge, "AndroidPagamento")

        // Onde carregar a UI:
        // - OFFLINE (recomendado para caixa): copie o build (pasta dist/) do seu app web
        //   para app/src/main/assets/ e mantenha a URL abaixo.
        // - ONLINE: aponte para o seu deploy, ex.: webView.loadUrl("https://lojinha.gescs.org.br")
        //   (a ponte AndroidPagamento continua disponível do mesmo jeito).
        webView.loadUrl(URL_INICIAL)
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
        const val URL_INICIAL = "https://appassets.androidplatform.net/assets/index.html"

        // Se for carregar a UI de um deploy online, adicione o domínio aqui também.
        val HOSTS_PERMITIDOS = setOf("appassets.androidplatform.net")
    }
}
