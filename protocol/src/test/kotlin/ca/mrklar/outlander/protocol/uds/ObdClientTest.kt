package ca.mrklar.outlander.protocol.uds

import ca.mrklar.outlander.protocol.elm.ElmSession
import ca.mrklar.outlander.protocol.elm.ScriptedElmTransport
import ca.mrklar.outlander.protocol.journal.InMemoryJournal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ObdClientTest {

    private fun bytes(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

    private fun exchange(
        expected: String?,
        vararg chunks: String,
    ): ScriptedElmTransport.Exchange =
        ScriptedElmTransport.Exchange(expected, chunks.map { bytes(it) })

    private fun TestScope.session(
        transport: ScriptedElmTransport,
        journal: InMemoryJournal,
    ): ElmSession = ElmSession(
        scope = backgroundScope,
        transport = transport,
        journal = journal,
        transportKind = "test",
        clockMono = { 1000L },
    )

    private fun ok(commandes: List<String>): List<ScriptedElmTransport.Exchange> =
        commandes.map { exchange(it, "OK\r>") }

    private fun octets(vararg valeurs: Int): ByteArray =
        ByteArray(valeurs.size) { valeurs[it].toByte() }

    private val entetesLegislated = listOf("ATSH7DF", "ATFCSM0")

    // (h) 0100 fonctionnel 11 bits, deux répondants 7E8/7E9 :
    // map par en-tête, Positive = charge complète (écho de mode inclus).
    @Test
    fun requeteLegifereeDeuxRepondants() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(
            ok(entetesLegislated) + exchange(
                "0100",
                "7E8 06 41 00 98 3B 80 13\r7E9 06 41 00 80 00 00 00\r>",
            ),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = ObdClient(session)

        val reponses = client.request(EcuChannel.LEGISLATED_11, "0100")

        assertEquals(listOf("7E8", "7E9"), reponses.keys.toList())
        assertEquals(
            UdsReply.Positive(octets(0x41, 0x00, 0x98, 0x3B, 0x80, 0x13)),
            reponses["7E8"],
        )
        assertEquals(
            UdsReply.Positive(octets(0x41, 0x00, 0x80, 0x00, 0x00, 0x00)),
            reponses["7E9"],
        )
        assertEquals(
            (entetesLegislated + "0100").map { "$it\r" },
            transport.writtenTexts,
        )
        assertEquals("obd", journal.events.last().kind)

        session.close()
    }

    // (i) Mode 04 hors liste blanche : refus propagé, zéro écriture.
    @Test
    fun mode04RefuseZeroEcriture() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(emptyList())
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = ObdClient(session)

        assertFailsWith<RequestRefusedException> {
            client.request(EcuChannel.LEGISLATED_11, "04")
        }
        assertEquals(emptyList(), transport.writtenTexts)
        assertEquals(0, journal.events.size)

        session.close()
    }

    // Hex malformé (impair, non-hex, vide) → IllegalArgumentException, rien d'écrit.
    @Test
    fun hexMalformeLeve() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(emptyList())
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = ObdClient(session)

        assertFailsWith<IllegalArgumentException> {
            client.request(EcuChannel.LEGISLATED_11, "010")
        }
        assertFailsWith<IllegalArgumentException> {
            client.request(EcuChannel.LEGISLATED_11, "ZZZZ")
        }
        assertFailsWith<IllegalArgumentException> {
            client.request(EcuChannel.LEGISLATED_11, "")
        }
        assertEquals(emptyList(), transport.writtenTexts)

        session.close()
    }

    // NO DATA → map vide.
    @Test
    fun noDataDonneMapVide() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(
            ok(entetesLegislated) + exchange("0100", "NO DATA\r>"),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = ObdClient(session)

        val reponses = client.request(EcuChannel.LEGISLATED_11, "0100")

        assertTrue(reponses.isEmpty(), "réponses=$reponses")

        session.close()
    }
}
