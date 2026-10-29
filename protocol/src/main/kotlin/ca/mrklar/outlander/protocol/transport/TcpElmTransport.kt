package ca.mrklar.outlander.protocol.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transport ELM327 sur socket TCP (adaptateur Wi-Fi et, en développement,
 * l'émulateur ELM327 local). Kotlin/JVM pur : `java.net.Socket` seulement.
 *
 * La lecture tourne sur [Dispatchers.IO] dans un [callbackFlow] qui se
 * ferme sur EOF ou erreur d'E/S ; les chunks livrés sont des copies, sans
 * alignement sur les lignes ELM. Les écritures sont synchronisées sur le
 * flux de sortie et immédiatement flushées. [close] est idempotent et
 * ferme la socket (ce qui termine aussi le flux entrant).
 */
class TcpElmTransport private constructor(private val socket: Socket) : ElmTransport {

    private val output = socket.getOutputStream()
    private val ecritureLock = Any()
    private val ferme = AtomicBoolean(false)

    override val incoming: Flow<ByteArray> = callbackFlow {
        val tampon = ByteArray(4096)
        val entree = socket.getInputStream()
        try {
            while (currentCoroutineContext().isActive) {
                val lu = withContext(Dispatchers.IO) { entree.read(tampon) }
                if (lu < 0) break
                if (lu > 0) trySend(tampon.copyOf(lu))
            }
        } catch (e: IOException) {
            // Socket fermée (par close() ou par le pair) : fin normale du flux.
        } finally {
            close()
        }
    }

    override suspend fun write(data: ByteArray) {
        check(!ferme.get()) { "TcpElmTransport déjà fermé" }
        withContext(Dispatchers.IO) {
            synchronized(ecritureLock) {
                output.write(data)
                output.flush()
            }
        }
    }

    override suspend fun close() {
        if (!ferme.compareAndSet(false, true)) return
        try {
            socket.close()
        } catch (e: IOException) {
            // Fermeture best-effort : la socket est déjà morte.
        }
    }

    companion object {
        /** Connecte une socket TCP à [host]:[port] (délai [connectTimeoutMs]). */
        suspend fun connect(
            host: String,
            port: Int,
            connectTimeoutMs: Int = 5000,
        ): TcpElmTransport = withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.tcpNoDelay = true
            } catch (e: IOException) {
                try {
                    socket.close()
                } catch (ignore: IOException) {
                    // Échec de connexion : l'exception d'origine fait foi.
                }
                throw e
            }
            TcpElmTransport(socket)
        }
    }
}
