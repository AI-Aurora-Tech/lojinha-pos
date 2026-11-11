# Regras de ProGuard/R8 (só valem se minifyEnabled = true no build de release).

# Mantém os métodos expostos ao JavaScript da WebView.
-keepclassmembers class br.org.gescs.lojinha.PagBankBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# SDK de pagamento da PagBank (comunicação via AIDL/Parcelable com o PlugPagService).
-keep class br.com.uol.pagseguro.** { *; }
-dontwarn br.com.uol.pagseguro.**
