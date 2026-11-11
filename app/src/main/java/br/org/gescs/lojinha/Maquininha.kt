package br.org.gescs.lojinha

import android.content.Context
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPag
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Instância única do SDK no app inteiro, como recomenda o app de exemplo oficial
 * (SmartCoffee): "Utilize o Wrapper como um Singleton em sua aplicação".
 *
 * Fica fora da Activity para que uma cobrança em andamento não seja duplicada
 * se a tela for recriada.
 */
object Maquininha {

    /** Pacote do serviço de pagamento que vem instalado na Moderninha Smart. */
    const val PACOTE_SERVICO = "br.com.uol.pagseguro.plugpagservice"

    @Volatile
    private var instancia: PlugPag? = null

    fun plugPag(context: Context): PlugPag =
        instancia ?: synchronized(this) {
            instancia ?: PlugPag(context.applicationContext).also { instancia = it }
        }

    /**
     * Pagamento, estorno, ativação, reimpressão e cálculo de parcelas são operações
     * BLOQUEANTES e não podem rodar em paralelo: todas passam por esta fila única.
     */
    val filaSdk: ExecutorService = Executors.newSingleThreadExecutor()

    /** abort() precisa de outra thread: a fila principal fica presa no doPayment(). */
    val filaAbort: ExecutorService = Executors.newSingleThreadExecutor()

    /** true enquanto um pagamento ou estorno está aberto na maquininha. */
    val emAndamento = AtomicBoolean(false)

    fun servicoInstalado(context: Context): Boolean =
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(PACOTE_SERVICO, 0)
            true
        } catch (e: Exception) {
            false
        }
}
