package ca.mrklar.outlander.protocol.scan

import ca.mrklar.outlander.protocol.elm.AdapterCapabilities
import ca.mrklar.outlander.protocol.elm.ElmSession
import ca.mrklar.outlander.protocol.elm.ScriptedElmTransport
import ca.mrklar.outlander.protocol.journal.InMemoryJournal
import ca.mrklar.outlander.protocol.uds.EcuChannel
import ca.mrklar.outlander.protocol.uds.GuardResult
import ca.mrklar.outlander.protocol.uds.ObdClient
import ca.mrklar.outlander.protocol.uds.RequestGuard
import ca.mrklar.outlander.protocol.uds.UdsClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScanExecutorTest {

    private fun bytes(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

    private fun ex(expected: String?, response: String): ScriptedElmTransport.Exchange =
        ScriptedElmTransport.Exchange(expected, listOf(bytes(response)))

    private fun ok(cmd: String) = ex(cmd, "OK\r>")
    private fun nd(cmd: String) = ex(cmd, "NO DATA\r>")
    private fun atrv(v: String = "14.2V") = ex("ATRV", "$v\r>")

    private fun prog11(tx: String, rx: String): List<ScriptedElmTransport.Exchange> = listOf(
        ok("ATSH$tx"), ok("ATCRA$rx"), ok("ATFCSH$tx"), ok("ATFCSD300000"), ok("ATFCSM1"),
    )

    private fun prog29(addr: String): List<ScriptedElmTransport.Exchange> = listOf(
        ok("ATSH DA $addr F1"), ok("ATCRA DA F1 $addr"), ok("ATFCSH DA $addr F1"),
        ok("ATFCSD300000"), ok("ATFCSM1"),
    )

    private val capacites = AdapterCapabilities(
        ati = "ELM327 v1.5",
        sti = null,
        atrvVolts = 14.2,
        okCommands = setOf("ATE0", "ATL0", "ATS1", "ATH1", "ATCAF1", "ATCFC1", "ATAT0", "ATSTFF", "ATSP0"),
        deniedCommands = emptySet(),
        detectedProtocol = "AUTO, ISO 15765-4 (CAN 29/500)",
    )

    private fun TestScope.executor(
        transport: ScriptedElmTransport,
        journal: InMemoryJournal,
    ): Pair<ElmSession, ScanExecutor> {
        val session = ElmSession(
            scope = backgroundScope,
            transport = transport,
            journal = journal,
            transportKind = "test",
            clockMono = { 1000L },
        )
        val executor = ScanExecutor(
            session = session,
            capabilities = capacites,
            uds = UdsClient(session),
            obd = ObdClient(session),
            journal = journal,
            config = ScanConfig(pacingMs = 0),
            clockMono = { 1000L },
            delayFn = { },
        )
        return session to executor
    }

    /** Requêtes fixes d'une phase légiférée après `0100` (aucun chaînage, PID 01 seul déclaré). */
    private val requetesLegifereesSuite =
        listOf("0900", "0902", "0904", "0906", "090A", "03", "07", "0A", "0101", "0142")

    /** Ordre des DID d'identification : 6 nommés puis le reste de F180–F19F. */
    private val didsIdentification: List<Int> = run {
        val premiers = listOf(0xF186, 0xF190, 0xF187, 0xF189, 0xF191, 0xF18C)
        premiers + (0xF180..0xF19F).filter { it !in premiers }
    }

    // -----------------------------------------------------------------------
    // Test roi : session scriptée complète, phases 1 à 7 de runPhases.
    // -----------------------------------------------------------------------

    @Test
    fun sessionScripteeComplete() = runTest(UnconfinedTestDispatcher()) {
        // Faux VIN ISO-TP complet : 62 F1 90 + « JA4J24A58RZ000001 » = 20 octets (10 14, 21, 22).
        val vinFrames = "18DAF1DB 10 14 62 F1 90 4A 41 34\r" +
            "18DAF1DB 21 4A 32 34 41 35 38 52\r" +
            "18DAF1DB 22 5A 30 30 30 30 30 31\r>"

        val pairesPresence = buildList {
            for (tx in 0x7E0..0x7E7) add("%03X".format(tx) to "%03X".format(tx + 8))
            add("797" to "79A"); add("79B" to "7BB"); add("784" to "78C")
            add("743" to "763"); add("740" to "760")
        }

        val script = buildList {
            // legifere-11 : 0100 sans réponse, suite en NO DATA.
            add(atrv()); add(ok("ATSP6")); add(ok("ATSH7DF")); add(ok("ATFCSM0"))
            add(nd("0100"))
            for (r in requetesLegifereesSuite) add(nd(r))
            // legifere-29 : deux répondants, masque 80 00 00 00 (seul le PID 01, pas de chaînage).
            add(atrv()); add(ok("ATSP7")); add(ok("ATCP18")); add(ok("ATSH DB 33 F1")); add(ok("ATFCSM0"))
            add(ex("0100", "18DAF1DB 06 41 00 80 00 00 00\r18DAF1DE 06 41 00 80 00 00 00\r>"))
            for (r in requetesLegifereesSuite) add(nd(r))
            // heritage-2101 : NO DATA, donc pas de 2102.
            add(atrv()); add(ok("ATSP6")); addAll(prog11("761", "762")); add(nd("2101"))
            // presence-11 : 13 paires, 3E00 puis 1001, tous NO DATA.
            add(atrv())
            for ((tx, rx) in pairesPresence) {
                addAll(prog11(tx, rx)); add(nd("3E00")); add(nd("1001"))
            }
            // cible-29 : DB répond (3E00 + VIN) ; DE et E5 muets, y compris en fonctionnel.
            add(atrv()); add(ok("ATSP7")); add(ok("ATCP18"))
            addAll(prog29("DB"))
            add(ex("3E00", "18DAF1DB 02 7E 00\r>"))
            add(ex("22F190", vinFrames))
            addAll(prog29("DE")); add(nd("3E00")); add(nd("22F190")); add(nd("22F190"))
            addAll(prog29("E5")); add(nd("3E00")); add(nd("22F190")); add(nd("22F190"))
            // identification sur DB : F186 positif, reste NO DATA, puis les 3 lectures DTC.
            add(atrv()); addAll(prog29("DB"))
            add(ex("22F186", "18DAF1DB 04 62 F1 86 01\r>"))
            for (did in didsIdentification.drop(1)) add(nd("22%04X".format(did)))
            add(nd("1901FF")); add(nd("1902FF")); add(nd("190A"))
            // did-gen4 : 01D1 positif (4 octets), les 7 autres en NRC 31.
            add(atrv())
            add(ex("2201D1", "18DAF1DB 07 62 01 D1 00 64 00 00\r>"))
            for (req in listOf("2201D0", "220106", "220178", "220179", "22010B", "22022A", "22F0D0")) {
                add(ex(req, "18DAF1DB 03 7F 22 31\r>"))
            }
        }

        val transport = ScriptedElmTransport(script)
        val journal = InMemoryJournal()
        val (session, executor) = executor(transport, journal)

        val evenements = executor.runPhases().toList()

        // Ordre des phases.
        assertEquals(
            listOf(
                "legifere-11", "legifere-29", "heritage-2101", "presence-11",
                "cible-29", "identification", "did-gen4",
            ),
            evenements.filterIsInstance<ScanEvent.PhaseStarted>().map { it.phase },
        )
        assertEquals(
            listOf(
                "legifere-11", "legifere-29", "heritage-2101", "presence-11",
                "cible-29", "identification", "did-gen4",
            ),
            evenements.filterIsInstance<ScanEvent.PhaseFinished>().map { it.phase },
        )
        assertTrue(evenements.none { it is ScanEvent.Stopped }, "evenements=$evenements")

        // Répondants : les deux en-têtes légiférés 29 bits + DB en cible-29.
        val repondants = evenements.filterIsInstance<ScanEvent.EcuResponded>()
        assertTrue(
            repondants.any { it.phase == "legifere-29" && it.headerHex == "18DAF1DB" },
            "repondants=$repondants",
        )
        assertTrue(
            repondants.any { it.phase == "legifere-29" && it.headerHex == "18DAF1DE" },
            "repondants=$repondants",
        )
        assertTrue(
            repondants.any { it.phase == "cible-29" && it.headerHex == "18DAF1DB" },
            "repondants=$repondants",
        )

        // Probe Gen4 principale : positive, jamais décodée (aucune équation encodée).
        val probeSoc = evenements.filterIsInstance<ScanEvent.ProbeResult>()
            .singleOrNull { it.signalId == "bmu.soc.did01d1" }
        assertNotNull(probeSoc)
        assertTrue(probeSoc.reply.startsWith("positive"), "reply=${probeSoc.reply}")
        assertNull(probeSoc.decoded)

        // Toutes les écritures prévues consommées, dans l'ordre.
        assertTrue(transport.mismatches.isEmpty(), "mismatches=${transport.mismatches}")
        assertEquals(script.size, transport.writtenTexts.size)

        // Rejeu des écritures non-AT dans le garde : chacune doit être Allowed
        // sur au moins un canal plausible.
        val canauxPlausibles = listOf(
            EcuChannel.LEGISLATED_11,
            EcuChannel.FUNCTIONAL_29,
            EcuChannel.BMU_DB,
            EcuChannel.ECU_DE,
            EcuChannel.ECU_E5,
            EcuChannel.LEGACY_BMU,
            EcuChannel.physical11(0x7E0, 0x7E8),
        )
        for (ecriture in transport.writtenTexts) {
            val texte = ecriture.trim()
            if (texte.startsWith("AT") || texte.startsWith("ST")) continue
            val hex = texte.filterNot { it.isWhitespace() }.uppercase()
            assertTrue(
                hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'A'..'F' },
                "écriture non-AT non hexadécimale: « $ecriture »",
            )
            val octets = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            assertTrue(
                canauxPlausibles.any { RequestGuard.check(it, octets) is GuardResult.Allowed },
                "requête hors liste blanche sur tous les canaux: $hex",
            )
        }

        // Le journal contient des silences no_data (jamais promus en positif).
        assertTrue(journal.events.any { it.responseStatus == "no_data" })
        // Aucun événement ni note ne déclare un ECU « absent ».
        for (e in journal.events) {
            assertFalse(e.note?.contains("absent", ignoreCase = true) == true, "note=${e.note}")
        }
        for (e in evenements) {
            assertFalse(e.toString().contains("absent", ignoreCase = true), "evenement=$e")
        }

        session.close()
    }

    // -----------------------------------------------------------------------
    // Gardes §5.5
    // -----------------------------------------------------------------------

    @Test
    fun troisCanErrorArretentLeScan() = runTest(UnconfinedTestDispatcher()) {
        val script = listOf(
            atrv(), ok("ATSP6"), ok("ATSH7DF"), ok("ATFCSM0"),
            ex("0100", "CAN ERROR\r>"),
            ex("0900", "CAN ERROR\r>"),
            ex("0902", "CAN ERROR\r>"),
        )
        val transport = ScriptedElmTransport(script)
        val journal = InMemoryJournal()
        val (session, executor) = executor(transport, journal)

        val evenements = executor.runPhases().toList()

        val arrets = evenements.filterIsInstance<ScanEvent.Stopped>()
        assertEquals(1, arrets.size, "evenements=$evenements")
        assertEquals("legifere-11", arrets.single().phase)
        assertTrue(arrets.single().reason.contains("CAN ERROR"), "reason=${arrets.single().reason}")
        // Aucune phase suivante, aucune écriture après le 3e CAN ERROR.
        assertFalse(
            evenements.filterIsInstance<ScanEvent.PhaseStarted>().any { it.phase == "legifere-29" },
        )
        assertEquals(script.size, transport.writtenTexts.size)
        assertTrue(transport.mismatches.isEmpty(), "mismatches=${transport.mismatches}")

        session.close()
    }

    @Test
    fun atrvBasHorsReadyArreteAvantPhaseSuivante() = runTest(UnconfinedTestDispatcher()) {
        val script = buildList {
            // legifere-11 entièrement déroulée avec ATRV sain au départ.
            add(atrv("14.2V")); add(ok("ATSP6")); add(ok("ATSH7DF")); add(ok("ATFCSM0"))
            add(nd("0100"))
            for (r in requetesLegifereesSuite) add(nd(r))
            // Début de legifere-29 : ATRV à 11,8 V, vehicleReady=false → arrêt.
            add(ex("ATRV", "11.8V\r>"))
        }
        val transport = ScriptedElmTransport(script)
        val journal = InMemoryJournal()
        val (session, executor) = executor(transport, journal)

        val evenements = executor.runPhases(vehicleReady = false).toList()

        val arrets = evenements.filterIsInstance<ScanEvent.Stopped>()
        assertEquals(1, arrets.size, "evenements=$evenements")
        assertEquals("legifere-29", arrets.single().phase)
        assertTrue(arrets.single().reason.contains("ATRV"), "reason=${arrets.single().reason}")
        // ATSP7 jamais émis, aucune phase après legifere-29.
        assertFalse(transport.writtenTexts.any { it.contains("ATSP7") })
        assertFalse(
            evenements.filterIsInstance<ScanEvent.PhaseStarted>().any { it.phase == "heritage-2101" },
        )
        assertEquals(script.size, transport.writtenTexts.size)
        assertTrue(transport.mismatches.isEmpty(), "mismatches=${transport.mismatches}")

        session.close()
    }

    @Test
    fun bufferFullRetenteUneFoisAvecFcBridee() = runTest(UnconfinedTestDispatcher()) {
        val script = buildList {
            add(ok("ATST64"))
            add(ok("ATSP7")); add(ok("ATCP18"))
            add(atrv())
            addAll(prog29("DB"))
            add(ex("22F186", "BUFFER FULL\r>"))
            add(ok("ATFCSD300A0A"))
            add(ex("22F186", "18DAF1DB 04 62 F1 86 01\r>"))
            add(ok("ATFCSD300000"))
            add(ok("ATSTFF"))
        }
        val transport = ScriptedElmTransport(script)
        val journal = InMemoryJournal()
        val (session, executor) = executor(transport, journal)

        val resultat = executor.sweepDidRange(EcuChannel.BMU_DB, 0xF186..0xF186)

        assertEquals(listOf(0xF186), resultat.positives)
        assertEquals(2, transport.writtenTexts.count { it.trim() == "22F186" })
        assertTrue(transport.writtenTexts.any { it.contains("ATFCSD300A0A") })
        assertTrue(
            journal.events.any { it.kind == "event" && it.note?.contains("BUFFER FULL") == true },
            "journal=${journal.events.map { it.note }}",
        )
        assertEquals(script.size, transport.writtenTexts.size)
        assertTrue(transport.mismatches.isEmpty(), "mismatches=${transport.mismatches}")

        session.close()
    }

    // -----------------------------------------------------------------------
    // Balayage borné (phase 8)
    // -----------------------------------------------------------------------

    @Test
    fun balayagePetitExact() = runTest(UnconfinedTestDispatcher()) {
        val script = buildList {
            add(ok("ATST64"))
            add(ok("ATSP7")); add(ok("ATCP18"))
            add(atrv())
            addAll(prog29("DB"))
            add(ex("22F100", "18DAF1DB 04 62 F1 00 AA\r>"))
            add(ex("22F101", "18DAF1DB 03 7F 22 31\r>"))
            add(nd("22F102"))
            add(ex("22F103", "18DAF1DB 03 7F 22 33\r>"))
            add(ok("ATSTFF"))
        }
        val transport = ScriptedElmTransport(script)
        val journal = InMemoryJournal()
        val (session, executor) = executor(transport, journal)

        val resultat = executor.sweepDidRange(EcuChannel.BMU_DB, 0xF100..0xF103)

        assertEquals(
            SweepResult(
                positives = listOf(0xF100),
                negativesByNrc = mapOf(0x31 to listOf(0xF101), 0x33 to listOf(0xF103)),
                noDataCount = 1,
                stoppedReason = null,
            ),
            resultat,
        )
        assertTrue(transport.writtenTexts.any { it.contains("ATST64") })
        assertTrue(transport.writtenTexts.any { it.contains("ATSTFF") })
        assertEquals(script.size, transport.writtenTexts.size)
        assertTrue(transport.mismatches.isEmpty(), "mismatches=${transport.mismatches}")

        session.close()
    }

    @Test
    fun balayageRetestSessionEtendueFusionne() = runTest(UnconfinedTestDispatcher()) {
        val script = buildList {
            add(ok("ATST64"))
            add(ok("ATSP7")); add(ok("ATCP18"))
            add(atrv())
            addAll(prog29("DB"))
            add(ex("22F100", "18DAF1DB 04 62 F1 00 AA\r>"))
            add(ex("22F101", "18DAF1DB 03 7F 22 31\r>"))
            add(nd("22F102"))
            add(ex("22F103", "18DAF1DB 03 7F 22 33\r>"))
            // Retest : 10 03 positif, F103 (seul NRC retestable) devient positif, retour 10 01.
            add(ex("1003", "18DAF1DB 02 50 03\r>"))
            add(ex("22F103", "18DAF1DB 04 62 F1 03 BB\r>"))
            add(ex("1001", "18DAF1DB 02 50 01\r>"))
            add(ok("ATSTFF"))
        }
        val transport = ScriptedElmTransport(script)
        val journal = InMemoryJournal()
        val (session, executor) = executor(transport, journal)

        val resultat = executor.sweepDidRange(
            EcuChannel.BMU_DB, 0xF100..0xF103, retestInExtendedSession = true,
        )

        assertEquals(listOf(0xF100, 0xF103), resultat.positives)
        assertEquals(mapOf(0x31 to listOf(0xF101)), resultat.negativesByNrc)
        assertEquals(1, resultat.noDataCount)
        assertNull(resultat.stoppedReason)
        assertEquals(script.size, transport.writtenTexts.size)
        assertTrue(transport.mismatches.isEmpty(), "mismatches=${transport.mismatches}")

        session.close()
    }

    @Test
    fun refusDuGardeJournaliseSansTuer() = runTest(UnconfinedTestDispatcher()) {
        // 10 03 en adressage fonctionnel : refusé par le garde avant émission.
        // Le retest doit sauter proprement, avec une note de refus au journal.
        val script = buildList {
            add(ok("ATST64"))
            add(ok("ATSP7")); add(ok("ATCP18"))
            add(atrv())
            add(ok("ATSH DB 33 F1")); add(ok("ATFCSM0"))
            add(ex("22F100", "18DAF1DB 03 7F 22 33\r>"))
            add(ok("ATSTFF"))
        }
        val transport = ScriptedElmTransport(script)
        val journal = InMemoryJournal()
        val (session, executor) = executor(transport, journal)

        val resultat = executor.sweepDidRange(
            EcuChannel.FUNCTIONAL_29, 0xF100..0xF100, retestInExtendedSession = true,
        )

        assertEquals(mapOf(0x33 to listOf(0xF100)), resultat.negativesByNrc)
        assertFalse(transport.writtenTexts.any { it.trim() == "1003" })
        assertTrue(
            journal.events.any {
                it.kind == "event" && it.note?.contains("refus liste blanche") == true
            },
            "journal=${journal.events.map { it.note }}",
        )
        assertEquals(script.size, transport.writtenTexts.size)
        assertTrue(transport.mismatches.isEmpty(), "mismatches=${transport.mismatches}")

        session.close()
    }
}
