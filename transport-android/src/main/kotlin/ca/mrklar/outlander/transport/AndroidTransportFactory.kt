package ca.mrklar.outlander.transport

import android.content.Context
import ca.mrklar.outlander.protocol.transport.ElmDevice
import ca.mrklar.outlander.protocol.transport.ElmTransport
import ca.mrklar.outlander.protocol.transport.ElmTransportKind
import com.juul.kable.Advertisement
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Fabrique de transports Android : mappe un [ElmDevice] vers le transport
 * réel correspondant. Les [Advertisement] BLE vus par le scanner sont
 * conservés en mémoire de processus (Kable exige l'annonce pour créer le
 * périphérique) ; après un redémarrage de processus, il faut rebalayer.
 */
class AndroidTransportFactory(context: Context) {

    private val appContext: Context = context.applicationContext
    private val advertisements = ConcurrentHashMap<String, Advertisement>()

    /** Mémorise l'annonce BLE vue au balayage, indexée par identifiant. */
    fun remember(advertisement: Advertisement) {
        advertisements[advertisement.identifier.toString()] = advertisement
    }

    /** Crée et connecte le transport pour [device]. */
    suspend fun create(device: ElmDevice, scope: CoroutineScope): ElmTransport =
        when (device.kind) {
            ElmTransportKind.CLASSIC_SPP ->
                ClassicSppTransport.connect(appContext, device.id)

            ElmTransportKind.BLE -> {
                val advertisement = advertisements[device.id]
                    ?: throw IllegalStateException("relancer le balayage BLE")
                BleTransport.connect(advertisement, scope)
            }

            ElmTransportKind.TCP, ElmTransportKind.REPLAY ->
                throw IllegalArgumentException(
                    "Transport ${device.kind} non géré par la fabrique Android (pur JVM côté :protocol)",
                )
        }
}
