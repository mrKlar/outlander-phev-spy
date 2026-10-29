package ca.mrklar.outlander.protocol.elm

import ca.mrklar.outlander.protocol.journal.InMemoryJournal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ElmSessionTest {

    private fun bytes(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

    private fun exchange(
        expected: String?,
        vararg chunks: String,
    ): ScriptedElmTransport.Exchange =
        ScriptedElmTransport.Exchange(expected, chunks.map { bytes(it) })

    /** Session sur le scope d'arrière-plan du test, horloge monotone fixe. */
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

    /** Séquence d'init complète ; [ceaResponse] permet le refus d'ATCEA. */
    private fun initScript(ceaResponse: String = "OK\r>"): List<ScriptedElmTransport.Exchange> =
        listOf(
            exchange("ATZ", "ELM327 v1.5\r>"),
            exchange("ATI", "ELM327 v1.5\r>"),
            exchange("STI", "?\r>"),
            exchange("ATRV", "12.4V\r>"),
            exchange("ATE0", "OK\r>"),
            exchange("ATL0", "OK\r>"),
            exchange("ATS1", "OK\r>"),
            exchange("ATH1", "OK\r>"),
            exchange("ATCAF1", "OK\r>"),
            exchange("ATCFC1", "OK\r>"),
            exchange("ATAT0", "OK\r>"),
            exchange("ATSTFF", "OK\r>"),
            exchange("ATCEA", ceaResponse),
            exchange("ATSP0", "OK\r>"),
            exchange("0100", "SEARCHING...\r7E8 06 41 00 98 3B 80 13\r>"),
            exchange("ATDPN", "AUTO, ISO 15765-4 (CAN 11/500)\r>"),
        )

    // (1) Init complète tous OK : capacités exactes et journal d'init.
    @Test
    fun initCompleteCapacitesEtJournal() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(initScript())
        val journal = InMemoryJournal()
        val session = session(transport, journal)

        val cap = session.initialize()

        assertEquals("ELM327 v1.5", cap.ati)
        assertNull(cap.sti)
        assertEquals(12.4, cap.atrvVolts)
        assertTrue(
            cap.okCommands.containsAll(
                setOf("ATE0", "ATL0", "ATS1", "ATH1", "ATCAF1", "ATCFC1", "ATAT0", "ATSTFF"),
            ),
            "okCommands=${cap.okCommands}",
        )
        assertTrue(cap.deniedCommands.isEmpty(), "deniedCommands=${cap.deniedCommands}")
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", cap.detectedProtocol)
        assertTrue(transport.mismatches.isEmpty(), "mismatches=${transport.mismatches}")
        assertEquals(16, transport.writtenTexts.size)

        val events = journal.events
        assertEquals(16, events.size)
        assertTrue(events.all { it.phase == "init" })
        // Les derniers événements portent l'adaptateur identifié pendant l'init.
        assertEquals("ELM327 v1.5", events.last().adapterAti)
        assertEquals("ELM327 v1.5", events[10].adapterAti)
        assertFalse(events.last().adapterStn!!)

        session.close()
    }

    // (2) ATCEA refusée (« ? ») : consignée, l'init se termine quand même.
    @Test
    fun initAtceaRefuseeConsigneeEtContinue() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(initScript(ceaResponse = "?\r>"))
        val journal = InMemoryJournal()
        val session = session(transport, journal)

        val cap = session.initialize()

        assertTrue("ATCEA" in cap.deniedCommands, "deniedCommands=${cap.deniedCommands}")
        assertFalse("ATCEA" in cap.okCommands, "okCommands=${cap.okCommands}")
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", cap.detectedProtocol)
        assertEquals(16, journal.events.size)
        assertTrue(transport.mismatches.isEmpty(), "mismatches=${transport.mismatches}")

        session.close()
    }

    // (3) Sérialisation : 5 transact concurrents, écritures une par une,
    // réponses appariées à l'ordre d'écriture.
    @Test
    fun transactConcurrentsSerialises() = runTest(UnconfinedTestDispatcher()) {
        val commandes = listOf("010C", "010D", "0105", "0111", "010B")
        val transport = ScriptedElmTransport(
            commandes.indices.map { i -> exchange(null, "R$i\r>") },
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)

        val resultats = commandes
            .map { cmd -> async { cmd to session.transact(cmd, timeoutMs = 300) } }
            .awaitAll()

        // Chaque commande écrite exactement une fois, jamais entrelacée.
        assertEquals(commandes.map { "$it\r" }.toSet(), transport.writtenTexts.toSet())
        assertEquals(5, transport.writtenTexts.size)
        // La réponse reçue par une commande est celle de son rang d'écriture.
        for ((cmd, reponse) in resultats) {
            val rang = transport.writtenTexts.indexOf("$cmd\r")
            assertEquals(listOf("R$rang"), reponse.lines, "commande $cmd")
            assertEquals(ElmStatus.POSITIVE, reponse.status, "commande $cmd")
        }
        assertEquals(5, journal.events.size)

        session.close()
    }

    // (4) Timeout : échange sans réponse → TIMEOUT journalisé.
    @Test
    fun timeoutSansReponse() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(
            listOf(ScriptedElmTransport.Exchange("0100", emptyList())),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)

        val reponse = session.transact("0100", timeoutMs = 150)

        assertEquals(ElmStatus.TIMEOUT, reponse.status)
        assertTrue(reponse.durationMs > 0, "durationMs=${reponse.durationMs}")
        val event = journal.events.single()
        assertEquals("timeout", event.responseStatus)
        assertEquals("0100", event.requestBytes)

        session.close()
    }

    // (5) continuesWhile : NRC 78 puis données → une réponse fusionnée,
    // une seule écriture.
    @Test
    fun continuesWhileFusionneSansReecrire() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(
            listOf(
                exchange(
                    "22F190",
                    "18DAF1DB 03 7F 22 78\r>",
                    "18DAF1DB 05 62 F1 90 4A 41\r>",
                ),
            ),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)

        val reponse = session.transact(
            "22F190",
            timeoutMs = 300,
            continuesWhile = { bloc -> bloc.lines.any { it.contains("7F 22 78") } },
        )

        assertEquals(ElmStatus.POSITIVE, reponse.status)
        assertEquals(
            listOf("18DAF1DB 03 7F 22 78", "18DAF1DB 05 62 F1 90 4A 41"),
            reponse.lines,
        )
        assertEquals(listOf("22F190\r"), transport.writtenTexts)
        assertEquals(1, journal.events.size)
        assertEquals(reponse.lines, journal.events.single().responseFrames)

        session.close()
    }

    // (6) enrich : le journal reçoit l'événement modifié par l'appelant.
    @Test
    fun enrichModifieEvenementJournalise() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(listOf(exchange("ATE0", "OK\r>")))
        val journal = InMemoryJournal()
        val session = session(transport, journal)

        session.transact("ATE0", timeoutMs = 300, enrich = { it.copy(note = "x") })

        val event = journal.events.single()
        assertEquals("x", event.note)
        assertEquals("ATE0", event.requestBytes)
        assertEquals("at", event.kind)
        assertEquals("positive", event.responseStatus)

        session.close()
    }

    // (7) elmSnapshot après init : protocole, CAF et ST suivis.
    @Test
    fun elmSnapshotApresInit() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(initScript())
        val journal = InMemoryJournal()
        val session = session(transport, journal)

        session.initialize()
        val snap = session.elmSnapshot()

        assertEquals("0", snap["protocol"])
        assertEquals("1", snap["caf"])
        assertEquals("1020", snap["st_ms"])

        session.close()
    }

    // Utilitaires publics : ATRV relue et ATSTxx journalisés via transact.
    @Test
    fun readAdapterVoltageEtSetResponseTimeoutHex() = runTest(UnconfinedTestDispatcher()) {
        val transport = ScriptedElmTransport(
            listOf(
                exchange("ATRV", "14,1V\r>"),
                exchange("ATST64", "OK\r>"),
            ),
        )
        val journal = InMemoryJournal()
        val session = session(transport, journal)

        assertEquals(14.1, session.readAdapterVoltage())
        assertEquals(ElmStatus.POSITIVE, session.setResponseTimeoutHex("64").status)
        assertEquals("400", session.elmSnapshot()["st_ms"])
        assertEquals(listOf("ATRV\r", "ATST64\r"), transport.writtenTexts)
        assertEquals(14.1, journal.events[0].atrvVolts)

        session.close()
    }
}
