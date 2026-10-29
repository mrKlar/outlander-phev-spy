package ca.mrklar.outlander.protocol.transport

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayTransportTest {

    private fun bytes(s: String): ByteArray = s.toByteArray()

    private fun List<ByteArray>.enListes(): List<List<Byte>> = map { it.toList() }

    private val chunks: List<ByteArray>
        get() = listOf(bytes("ELM"), bytes("327 v1.5\r"), bytes("\r>"))

    // (1) Mode gate : ordre et contenu des chunks après la première écriture.
    @Test
    fun chunksEmisDansLOrdreApresPremiereEcritureEnModeGate() = runTest {
        val attendu = chunks
        val transport = ReplayTransport(attendu, gateOnWrite = true)

        val recus = async { transport.incoming.take(attendu.size).toList() }
        transport.write(bytes("ATI\r"))

        assertEquals(attendu.enListes(), recus.await().enListes())

        // Une deuxième écriture ne ré-émet pas les chunks.
        transport.write(bytes("ATI\r"))
        assertNull(withTimeoutOrNull(100) { transport.incoming.first() })
    }

    // (2) Mode gate : rien n'est émis sans écriture. runBlocking (et non
    // runTest) pour que withTimeoutOrNull(100) soit un délai réel, non
    // avancé par l'horloge virtuelle de runTest.
    @Test
    fun aucunChunkSansEcritureEnModeGate() = runBlocking {
        val transport = ReplayTransport(chunks, gateOnWrite = true)

        val recu = withTimeoutOrNull(100) { transport.incoming.first() }

        assertNull(recu)
    }

    // (3) Mode non-gate : chunks émis dès la collecte, sans écriture.
    @Test
    fun chunksEmisSansEcritureEnModeNonGate() = runTest {
        val attendu = chunks
        val transport = ReplayTransport(attendu, gateOnWrite = false)

        val recus = transport.incoming.take(attendu.size).toList()

        assertEquals(attendu.enListes(), recus.enListes())
        assertTrue(transport.written.isEmpty())
    }

    // (4) written enregistre chaque écriture octet pour octet.
    @Test
    fun writtenEnregistreChaqueEcritureOctetPourOctet() = runTest {
        val transport = ReplayTransport(chunks)
        val premiere = byteArrayOf(0x41, 0x54, 0x49, 0x0D) // "ATI\r"
        val deuxieme = byteArrayOf(0x00, 0x01, 0x7F, -1, -128)

        transport.write(premiere)
        transport.write(deuxieme)

        assertEquals(
            listOf(premiere.toList(), deuxieme.toList()),
            transport.written.enListes(),
        )
    }

    // (5) Copies défensives : muter les tableaux source après coup ne
    // change ni le chunk émis ni l'écriture enregistrée.
    @Test
    fun copiesDefensivesContreMutationDesTableauxSource() = runTest {
        val source = byteArrayOf(1, 2, 3)
        val transport = ReplayTransport(listOf(source), gateOnWrite = true)
        source[0] = 99

        val ecriture = byteArrayOf(7, 8, 9)
        transport.write(ecriture)
        ecriture[0] = 42

        assertEquals(listOf<Byte>(1, 2, 3), transport.incoming.first().toList())
        assertEquals(listOf<Byte>(7, 8, 9), transport.written.single().toList())
    }

    // (6a) close() termine la collecte : toList() retourne avec les chunks
    // déjà émis, et un second close() est sans effet.
    @Test
    fun closeTermineLaCollecte() = runTest {
        val attendu = chunks
        val transport = ReplayTransport(attendu, gateOnWrite = true)
        transport.write(bytes("ATI\r"))
        transport.close()

        assertEquals(attendu.enListes(), transport.incoming.toList().enListes())

        transport.close()
    }

    // (6b) write() après close() lève IllegalStateException.
    @Test
    fun writeApresCloseLeveIllegalStateException() = runTest {
        val transport = ReplayTransport(chunks)
        transport.close()

        var levee: IllegalStateException? = null
        try {
            transport.write(bytes("ATI\r"))
        } catch (e: IllegalStateException) {
            levee = e
        }

        assertNotNull(levee)
    }
}
