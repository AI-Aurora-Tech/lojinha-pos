package br.org.gescs.lojinha

import android.annotation.SuppressLin
import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

/**
 * Casca Android que carrega a sua UI React (build do Vite) dentro de uma WebView
 * e conecta a ponte de pagamento nativa. Assim você reaproveita todo o seu front
 * web e escreve só a "cola" nativa que fala com o SDK.
 */
class MainActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(saveInstanceState: Bundle?) {
        super.onCreate(saveInstanceState)

        val webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true    // Permite localStorage da sua UI
        }

        // Expoe a ponte fe pagamento ao JavaScript com o nome "Android Pagamento"
        webView.addJavaScriptInterface(
            PagBankBridge(this, webView),
            "AndroidPagamento"
        )

        // Onde carregar a UI:
        // - OFFLINE (recomendado para caixa): cope o buid do seu repo web para app/src/main/assets/
        // e carregue de file:///android_asset/index.html
        // - ONLINE: Aponte para seu deploy, ex: webView.loadUrl("https://lojinha.gescs.org.br")
        webView.loadUrl("file:///android_asset/index.html")
    }
}