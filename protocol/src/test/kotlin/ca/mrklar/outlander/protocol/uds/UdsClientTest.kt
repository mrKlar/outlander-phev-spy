package ca.mrklar.outlander.protocol.uds

import ca.mrklar.outlander.protocol.elm.ElmSession
import ca.mrklar.outlander.protocol.elm.ElmStatus
import ca.mrklar.outlander.protocol.elm.ScriptedElmTransport
import ca.mrklar.outlander.protocol.journal.InMemoryJournal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UdsClientTest {

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

    /** Échanges « OK » pour une séquence de commandes AT d'en-tête. */
    private fun ok(commandes: List<String>): List<ScriptedElmTransport.Exchange> =
        commandes.map { exchange(it, "OK\r>") }

    private fun octets(vararg valeurs: Int): ByteArray =
        ByteArray(valeurs.size) { valeurs[it].toByte() }

    private val entetesBmuDb = listOf(
        "ATSH DA DB F1", "ATCRA DA F1 DB", "ATFCSH DA DB F1", "ATFCSD300000", "ATFCSM1",
    )

    private val entetesLegacyBmu = listOf(
        "ATSH761", "ATCRA762", "ATFCSH761", "ATFCSD300000", "ATFCSM1",
    )

    // (canaux) Constantes et fabriques du compagnon EcuChannel.
    @Test
    fun canauxPredefinis() {
        assertEquals(0x7DFL, EcuChannel.LEGISLATED_11.txId)
        assertEquals(0L, EcuChannel.LEGISLATED_11.rxId)
        assertTrue(EcuChannel.LEGISLATED_11.functional)
        assertTrue(!EcuChannel.LEGISLATED_11.extended29Bit)

        assertEquals(0x18DB33F1L, EcuChannel.FUNCTIONAL_29.txId)
        assertEquals(0L, EcuChannel.FUNCTIONAL_29.rxId)
        assertTrue(EcuChannel.FUNCTIONAL_29.functional)
        assertTrue(EcuChannel.FUNCTIONAL_29.extended29Bit)

        assertEquals(0x761L, EcuChannel.LEGACY_BMU.txId)
        assertEquals(0x762L, EcuChannel.LEGACY_BMU.rxId)
        assertEquals(0x761L, EcuChannel.LEGACY_BMU.fcHeader)
        assertTrue(!EcuChannel.LEGACY_BMU.functional)

        val physique = EcuChannel.physical11(0x7E0, 0x7E8)
        assertEquals(0x7E0L, physique.txId)
        assertEquals(0x7E8L, physique.rxId)
        assertEquals(0x7E0L, physique.fcHeader)
        assertTrue(!physique.extended29Bit && !physique.functional)

        assertEquals(EcuChannel.logical29(0xDB), EcuChannel.BMU_DB)
        assertEquals(0x18DADBF1L, EcuChannel.BMU_DB.txId)
        assertEquals(0x18DAF1DBL, EcuChannel.BMU_DB.rxId)
        assertEquals(EcuChannel.BMU_DB.txId, EcuChannel.BMU_DB.fcHeader)
        assertTrue(EcuChannel.BMU_DB.extended29Bit && !EcuChannel.BMU_DB.functional)

        assertEquals(0x18DADEF1L, EcuChannel.ECU_DE.txId)
        assertEquals(0x18DAF1DEL, EcuChannel.ECU_DE.rxId)
        assertEquals(0x18DAE5F1L, EcuChannel.ECU_E5.txId)
        assertEquals(0x18DAF1E5L, EcuChannel.ECU_E5.rxId)
    }

    // (a) Séquence d'en-têtes 29 bits exacte avant 22F190 ; réponse ISO-TP
    // multi-trames → Positive sans l'en-tête 62 F1 90 ; journal enrichi.
    @Test
    fun lectureVin29BitsEntetesExactesPositiveEtJournal() = runTest(UnconfinedTestDispatcher()) {
        val vinFactice = "JA4J24A58RZ000001"
        val transport = ScriptedElmTransport(
            ok(entetesBmuDb) + exchange(
                "22F190",
                "18DAF1DB 10 14 62 F1 90 4A 41 34\r",
                "18DAF1DB 21 4A 32 34 41 35 38 52\r",
                "18DAF1DB 22 5A 30 30 30 30 30 31\r>",
            ),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = UdsClient(session)

        val reponse = client.readDataByIdentifier(EcuChannel.BMU_DB, 0xF190)

        assertEquals(
            UdsReply.Positive(vinFactice.toByteArray(Charsets.US_ASCII)),
            reponse,
        )
        assertEquals(
            (entetesBmuDb + "22F190").map { "$it\r" },
            transport.writtenTexts,
        )
        assertTrue(transport.mismatches.isEmpty(), "mismatches=${transport.mismatches}")

        val evenements = journal.events
        assertEquals(6, evenements.size)
        assertTrue(evenements.take(5).all { it.kind == "at" }, "kinds=${evenements.map { it.kind }}")
        val uds = evenements.last()
        assertEquals("uds", uds.kind)
        assertEquals("22F190", uds.requestBytes)
        val hexVin = vinFactice.toByteArray(Charsets.US_ASCII)
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        assertEquals("62F190$hexVin", uds.responseReassembled)
        assertNull(uds.responseNrc)

        session.close()
    }

    // (b) 7F 22 31 → Negative(0x22, 0x31) ; journal : reassembled + nrc.
    @Test
    fun reponseNegative31() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(
            ok(entetesBmuDb) + exchange("22F190", "18DAF1DB 03 7F 22 31\r>"),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = UdsClient(session)

        val reponse = client.readDataByIdentifier(EcuChannel.BMU_DB, 0xF190)

        assertEquals(UdsReply.Negative(0x22, 0x31), reponse)
        val uds = journal.events.last()
        assertEquals("7F2231", uds.responseReassembled)
        assertEquals("31", uds.responseNrc)

        session.close()
    }

    // (c) 7F 22 78 puis positive : une seule écriture de requête, Positive finale.
    @Test
    fun nrc78PuisPositiveUneSeuleEcriture() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(
            ok(entetesBmuDb) + exchange(
                "22F190",
                "18DAF1DB 03 7F 22 78\r>",
                "18DAF1DB 05 62 F1 90 4A 41\r>",
            ),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = UdsClient(session)

        val reponse = client.readDataByIdentifier(EcuChannel.BMU_DB, 0xF190)

        assertEquals(UdsReply.Positive(octets(0x4A, 0x41)), reponse)
        assertEquals(1, transport.writtenTexts.count { it == "22F190\r" })
        assertEquals("62F1904A41", journal.events.last().responseReassembled)
        assertNull(journal.events.last().responseNrc)

        session.close()
    }

    // (d) NO DATA → NoData (jamais « ECU absent »).
    @Test
    fun noDataDonneNoData() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(
            ok(entetesBmuDb) + exchange("22F190", "NO DATA\r>"),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = UdsClient(session)

        val reponse = client.readDataByIdentifier(EcuChannel.BMU_DB, 0xF190)

        assertEquals(UdsReply.NoData, reponse)
        assertEquals("no_data", journal.events.last().responseStatus)

        session.close()
    }

    // (e) Refus du garde : RequestRefusedException et ZÉRO écriture,
    // en-têtes AT compris (le garde passe avant programChannel).
    @Test
    fun refusGardeZeroEcriture() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(emptyList())
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = UdsClient(session)

        assertFailsWith<RequestRefusedException> {
            client.diagnosticSessionControl(EcuChannel.FUNCTIONAL_29, 0x03)
        }
        assertEquals(emptyList(), transport.writtenTexts)
        assertEquals(0, journal.events.size)

        session.close()
    }

    // (f) Cache de canal : deux lectures sur le même canal, en-têtes émis une fois.
    @Test
    fun cacheCanalEntetesUneSeuleFois() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(
            ok(entetesBmuDb) +
                exchange("22F190", "18DAF1DB 05 62 F1 90 4A 41\r>") +
                exchange("22F190", "18DAF1DB 05 62 F1 90 4A 41\r>"),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = UdsClient(session)

        client.readDataByIdentifier(EcuChannel.BMU_DB, 0xF190)
        client.readDataByIdentifier(EcuChannel.BMU_DB, 0xF190)

        assertEquals(
            (entetesBmuDb + listOf("22F190", "22F190")).map { "$it\r" },
            transport.writtenTexts,
        )

        session.close()
    }

    // (g) Écho DID différent du DID demandé → ElmFailure.
    @Test
    fun echoDidDifferentElmFailure() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(
            ok(entetesBmuDb) + exchange("22F190", "18DAF1DB 05 62 F1 91 4A 41\r>"),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = UdsClient(session)

        val reponse = client.readDataByIdentifier(EcuChannel.BMU_DB, 0xF190)

        assertTrue(reponse is UdsReply.ElmFailure, "réponse=$reponse")

        session.close()
    }

    // TesterPresent sur canal 11 bits : en-têtes collés exacts, Positive vide.
    @Test
    fun testerPresentLegacyBmu() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(
            ok(entetesLegacyBmu) + exchange("3E00", "762 02 7E 00\r>"),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = UdsClient(session)

        val reponse = client.testerPresent(EcuChannel.LEGACY_BMU)

        assertEquals(UdsReply.Positive(ByteArray(0)), reponse)
        assertEquals(
            (entetesLegacyBmu + "3E00").map { "$it\r" },
            transport.writtenTexts,
        )

        session.close()
    }

    // Exception fermée : legacyRead2101 vise 761→762 et émet exactement 2101.
    @Test
    fun legacyRead2101CanalEtRequete() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(
            ok(entetesLegacyBmu) + exchange("2101", "762 04 61 01 AA BB\r>"),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = UdsClient(session)

        val reponse = client.legacyRead2101()

        assertEquals(UdsReply.Positive(octets(0xAA, 0xBB)), reponse)
        assertEquals(
            (entetesLegacyBmu + "2101").map { "$it\r" },
            transport.writtenTexts,
        )

        session.close()
    }

    // 10 03 en physique : écho de sous-fonction vérifié, données après 50 03.
    @Test
    fun diagnosticSessionControlPhysique() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(
            ok(entetesBmuDb) + exchange("1003", "18DAF1DB 06 50 03 00 32 01 F4\r>"),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = UdsClient(session)

        val reponse = client.diagnosticSessionControl(EcuChannel.BMU_DB, 0x03)

        assertEquals(UdsReply.Positive(octets(0x00, 0x32, 0x01, 0xF4)), reponse)

        session.close()
    }

    // 19 02 FF (masque par défaut) puis 19 0A (masque omis, 2 octets).
    @Test
    fun readDtcAvecEtSansMasque() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(
            ok(entetesBmuDb) +
                exchange("1902FF", "18DAF1DB 03 59 02 FF\r>") +
                exchange("190A", "18DAF1DB 02 59 0A\r>"),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = UdsClient(session)

        val avecMasque = client.readDtc(EcuChannel.BMU_DB, 0x02)
        val sansMasque = client.readDtc(EcuChannel.BMU_DB, 0x0A, mask = null)

        assertEquals(UdsReply.Positive(octets(0xFF)), avecMasque)
        assertEquals(UdsReply.Positive(ByteArray(0)), sansMasque)
        assertTrue(transport.mismatches.isEmpty(), "mismatches=${transport.mismatches}")

        session.close()
    }

    // Charge ISO-TP incomplète (First Frame sans suite) → ElmFailure.
    @Test
    fun chargeIncompleteElmFailure() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(
            ok(entetesBmuDb) + exchange("22F190", "18DAF1DB 10 14 62 F1 90 4A 41 34\r>"),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = UdsClient(session)

        val reponse = client.readDataByIdentifier(EcuChannel.BMU_DB, 0xF190)

        assertTrue(reponse is UdsReply.ElmFailure, "réponse=$reponse")

        session.close()
    }

    // BUFFER FULL (même avec des lignes) → ElmFailure.
    @Test
    fun bufferFullElmFailure() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(
            ok(entetesBmuDb) + exchange(
                "22F190",
                "18DAF1DB 05 62 F1 90 4A 41\rBUFFER FULL\r>",
            ),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = UdsClient(session)

        val reponse = client.readDataByIdentifier(EcuChannel.BMU_DB, 0xF190)

        val echec = reponse as? UdsReply.ElmFailure
        assertTrue(echec != null, "réponse=$reponse")
        assertEquals(ElmStatus.BUFFER_FULL, echec.response.status)

        session.close()
    }

    // Canal fonctionnel : ATSH + ATFCSM0 seulement (rxId 0 → pas d'ATCRA).
    @Test
    fun programChannelFonctionnel() = runTest(UnconfinedTestDispatcher()) {
        val entetes = listOf("ATSH7DF", "ATFCSM0")
        val transport = ScriptedElmTransport(ok(entetes))
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = UdsClient(session)

        client.programChannel(EcuChannel.LEGISLATED_11)

        assertEquals(entetes.map { "$it\r" }, transport.writtenTexts)
        assertTrue(journal.events.all { it.kind == "at" })

        session.close()
    }

    // FC bridée : setFlowControlData émet ATFCSD + hex collé, kind=at.
    @Test
    fun setFlowControlDataEmetAtfcsd() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(ok(listOf("ATFCSD300A0A")))
        val journal = InMemoryJournal()
        val session = session(transport, journal)
        val client = UdsClient(session)

        val reponse = client.setFlowControlData("300A0A")

        assertEquals(ElmStatus.POSITIVE, reponse.status)
        assertEquals(listOf("ATFCSD300A0A\r"), transport.writtenTexts)
        assertEquals("at", journal.events.single().kind)

        session.close()
    }
}
