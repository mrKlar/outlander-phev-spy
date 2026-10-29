package ca.mrklar.outlander.protocol.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TcpElmTransportTest {

    /**
     * Mini-serveur socket local : à la première ligne reçue, il répond
     * « OK\r> ». Le transport doit livrer ce brut dans [ElmTransport.incoming]
     * et fermer deux fois sans erreur.
     */
    @Test
    fun boucleLocaleOkPromptEtCloseIdempotent() = runBlocking {
        val server = ServerSocket(0)
        val ligneRecue = AtomicReference<String?>(null)
        val serveurJob = CoroutineScope(Dispatchers.IO).launch {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val ligne = withContext(Dispatchers.IO) { reader.readLine() }
                ligneRecue.set(ligne)
                val out = socket.getOutputStream()
                out.write("OK\r>".toByteArray(Charsets.ISO_8859_1))
                out.flush()
                // Garde la socket ouverte jusqu'à lecture de l'EOF client.
                withContext(Dispatchers.IO) {
                    while (socket.getInputStream().read() >= 0) { /* attend la fermeture */ }
                }
            }
        }
        try {
            val transport = TcpElmTransport.connect("127.0.0.1", server.localPort)
            val accumule = StringBuilder()
            val collecte = CoroutineScope(Dispatchers.Unconfined).launch {
                transport.incoming.collect { chunk ->
                    accumule.append(String(chunk, Charsets.ISO_8859_1))
                }
            }
            transport.write("ATZ\r".toByteArray(Charsets.ISO_8859_1))

            withTimeout(5_000) {
                while (!accumule.contains(">")) {
                    kotlinx.coroutines.delay(20)
                }
            }
            assertEquals("ATZ", ligneRecue.get())
            assertEquals("OK\r>", accumule.toString())

            transport.close()
            transport.close() // idempotent : aucun effet, aucune exception
            collecte.cancel()
        } finally {
            serveurJob.cancel()
            server.close()
        }
    }

    @Test
    fun connectPortFermeLeve() = runBlocking {
        // Port 1 (tcpmux) fermé sur boucle locale : la connexion doit échouer.
        var leve = false
        try {
            TcpElmTransport.connect("127.0.0.1", 1, connectTimeoutMs = 1000)
        } catch (e: Exception) {
            leve = true
        }
        assertTrue(leve, "connect vers un port fermé doit lever")
    }
}
