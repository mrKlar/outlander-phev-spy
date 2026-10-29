package ca.mrklar.outlander.protocol.journal

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalTest {

    private val murFixe = "2026-10-29T12:00:00-04:00"

    /** Horloge monotone scriptée : retourne les valeurs dans l'ordre,
     *  puis la dernière indéfiniment. */
    private fun mono(vararg valeurs: Long): () -> Long {
        var i = 0
        return { if (i < valeurs.size) valeurs[i++] else valeurs.last() }
    }

    private fun evenementComplet() = JournalEvent(
        phase = "init",
        kind = "uds",
        tsWall = "bidon",
        tsMonoMs = -1L,
        adapterAti = "ELM327 v1.5",
        adapterStn = false,
        transport = "tcp",
        elm = mapOf(
            "protocol" to "6",
            "sh" to "7E0",
            "cra" to "7E8",
            "caf" to "1",
            "st_ms" to "50",
        ),
        vehicleState = "READY",
        atrvVolts = 14.2,
        requestTxId = "7E0",
        requestBytes = "22 f1 90",
        requestService = 0x22,
        requestDid = "F190",
        responseRawText = "62 F1 90",
        responseFrames = listOf("7E8 06 62 F1 90"),
        responseReassembled = "62 f1 90",
        responseStatus = "positive",
        responseNrc = null,
        responseElmTokens = listOf("SEARCHING..."),
        durationMs = 123L,
        retryOf = null,
        txChunks = listOf(TxChunk(1001L, "22 f190")),
        rxChunks = listOf(TxChunk(1100L, "62F190")),
        note = "premier",
    )

    // (a) Ligne exacte pour un événement figé, horloges injectées.
    // (c) Au passage : tsWall/tsMonoMs bidon de l'événement sont remplacés.
    @Test
    fun ligneExacteEvenementFige() = runTest {
        val journal = InMemoryJournal(
            sessionId = "session-test",
            clockWall = { murFixe },
            clockMono = mono(1000L, 2000L),
        )

        journal.append(evenementComplet())

        val attendue = """{"schema":1,"session_id":"session-test","seq":0,"ts_wall":"2026-10-29T12:00:00-04:00","ts_mono_ms":1000,"phase":"init","kind":"uds","adapter":{"ati":"ELM327 v1.5","stn":false,"transport":"tcp"},"elm":{"protocol":"6","sh":"7E0","cra":"7E8","fcsh":null,"fcsd":null,"caf":"1","st_ms":"50"},"vehicle":{"state":"READY","atrv_v":14.2},"request":{"tx_id":"7E0","bytes":"22F190","service":"22","did":"F190"},"response":{"raw_text":"62 F1 90","frames":["7E8 06 62 F1 90"],"reassembled":"62F190","status":"positive","nrc":null,"elm_tokens":["SEARCHING..."]},"duration_ms":123,"retry_of":null,"tx_chunks":[{"at_mono_ms":1001,"hex":"22F190"}],"rx_chunks":[{"at_mono_ms":1100,"hex":"62F190"}],"note":"premier"}"""
        assertEquals(listOf(attendue), journal.renderedLines())

        // L'événement exposé porte les horodatages du journal, pas les bidon.
        val stocke = journal.events.single()
        assertEquals(murFixe, stocke.tsWall)
        assertEquals(1000L, stocke.tsMonoMs)

        journal.close()
    }

    // Deuxième ligne : seq=1, mono=2000, request/response null, note omise.
    @Test
    fun deuxiemeLigneSeqMonoEtChampsNuls() = runTest {
        val journal = InMemoryJournal(
            sessionId = "session-test",
            clockWall = { murFixe },
            clockMono = mono(1000L, 2000L),
        )
        journal.append(evenementComplet())
        journal.append(
            JournalEvent(phase = "manuel", kind = "event", tsWall = "x", tsMonoMs = 0L),
        )

        val ligne2 = journal.renderedLines()[1]
        assertTrue(ligne2.contains("\"seq\":1"))
        assertTrue(ligne2.contains("\"ts_mono_ms\":2000"))
        assertTrue(ligne2.contains("\"request\":null"))
        assertTrue(ligne2.contains("\"response\":null"))
        assertTrue(ligne2.contains("\"duration_ms\":null"))
        assertTrue(ligne2.contains("\"tx_chunks\":[]"))
        assertFalse(ligne2.contains("\"note\""))
        journal.close()
    }

    // (e) status no_data conservé tel quel.
    @Test
    fun statusNoDataConserve() = runTest {
        val journal = InMemoryJournal(
            sessionId = "s",
            clockWall = { murFixe },
            clockMono = mono(5L),
        )
        journal.append(
            JournalEvent(
                phase = "presence-11",
                kind = "obd",
                tsWall = "x",
                tsMonoMs = 0L,
                requestBytes = "0100",
                requestService = 0x01,
                responseRawText = "NO DATA",
                responseStatus = "no_data",
                responseElmTokens = listOf("NO DATA"),
            ),
        )

        val ligne = journal.renderedLines().single()
        assertTrue(ligne.contains("\"status\":\"no_data\""), ligne)
        assertTrue(ligne.contains("\"service\":\"01\""), ligne)
        journal.close()
    }

    // (d) raw_text avec guillemet, antislash et \r réel : échappements
    // corrects, et la ligne reste une seule ligne physique.
    @Test
    fun echappementRawTextUneSeuleLigne() = runTest {
        val journal = InMemoryJournal(
            sessionId = "s",
            clockWall = { murFixe },
            clockMono = mono(7L),
        )
        journal.append(
            JournalEvent(
                phase = "manuel",
                kind = "at",
                tsWall = "x",
                tsMonoMs = 0L,
                responseRawText = "a\"b\\c\r\u0001fin",
                responseStatus = "positive",
            ),
        )

        val ligne = journal.renderedLines().single()
        assertTrue(ligne.contains("\"raw_text\":\"a\\\"b\\\\c\\r\\u0001fin\""), ligne)
        assertFalse(ligne.contains('\n'))
        assertFalse(ligne.contains('\r'))
        journal.close()
    }

    // (b) FileJournal : 3 appends relus (seq 0,1,2), flush visible avant
    // close, parent créé, append après close lève, close idempotent.
    @Test
    fun fileJournalTroisAppendsEtFermeture() = runTest {
        val dir = Files.createTempDirectory("journal-test").toFile()
        try {
            val fichier = File(dir, "sous/journal.jsonl")
            val journal = FileJournal(
                fichier,
                sessionId = "fichier-test",
                clockWall = { murFixe },
                clockMono = mono(10L, 20L, 30L),
            )

            journal.append(evenementComplet())
            // Flush après chaque ligne : déjà lisible sans close.
            assertEquals(1, fichier.readLines().size)

            journal.append(evenementComplet())
            journal.append(evenementComplet())
            journal.close()
            journal.close() // idempotent

            val lignes = fichier.readLines()
            assertEquals(3, lignes.size)
            val seqs = lignes.map { ligne ->
                Regex("\"seq\":(\\d+)").find(ligne)!!.groupValues[1].toInt()
            }
            assertEquals(listOf(0, 1, 2), seqs)
            assertTrue(lignes.all { it.contains("\"session_id\":\"fichier-test\"") })

            assertFailsWith<IllegalStateException> {
                journal.append(evenementComplet())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun inMemoryAppendApresCloseLeve() = runTest {
        val journal = InMemoryJournal(
            sessionId = "s",
            clockWall = { murFixe },
            clockMono = mono(1L),
        )
        journal.close()
        journal.close()
        assertFailsWith<IllegalStateException> {
            journal.append(evenementComplet())
        }
    }
}
