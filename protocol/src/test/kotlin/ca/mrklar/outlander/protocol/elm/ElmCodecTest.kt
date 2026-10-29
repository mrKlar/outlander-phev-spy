package ca.mrklar.outlander.protocol.elm

import kotlin.test.Test
import kotlin.test.assertEquals

class ElmCodecTest {

    private fun bytes(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

    /** Alimente le codec avec un texte terminé par '>' et classe l'unique bloc obtenu. */
    private fun classe(
        codec: ElmCodec,
        command: String,
        flux: String,
        durationMs: Long = 12L,
    ): ElmResponse {
        val blocs = codec.feed(bytes(flux))
        assertEquals(1, blocs.size)
        return codec.classify(command, blocs.single(), durationMs)
    }

    // (a) Réponse simple, via feed + classify.
    @Test
    fun reponseSimplePositive() {
        val codec = ElmCodec()

        val reponse = classe(codec, "0100", "7E8 06 41 00 98 3B 80 13\r>")

        assertEquals(ElmStatus.POSITIVE, reponse.status)
        assertEquals(listOf("7E8 06 41 00 98 3B 80 13"), reponse.lines)
        assertEquals(emptyList(), reponse.elmTokens)
        assertEquals("0100", reponse.command)
        assertEquals("7E8 06 41 00 98 3B 80 13\r", reponse.rawText)
        assertEquals(12L, reponse.durationMs)
    }

    // (b) SEARCHING... précède les données : token consigné, données conservées.
    @Test
    fun searchingSuiviDeDonneesPositif() {
        val codec = ElmCodec()

        val reponse = classe(codec, "0100", "SEARCHING...\r7E8 06 41 00 98 3B 80 13\r>")

        assertEquals(ElmStatus.POSITIVE, reponse.status)
        assertEquals(listOf("7E8 06 41 00 98 3B 80 13"), reponse.lines)
        assertEquals(listOf("SEARCHING..."), reponse.elmTokens)
    }

    // (c) NO DATA seul.
    @Test
    fun noData() {
        val codec = ElmCodec()

        val reponse = classe(codec, "0100", "NO DATA\r>")

        assertEquals(ElmStatus.NO_DATA, reponse.status)
        assertEquals(emptyList(), reponse.lines)
        assertEquals(listOf("NO DATA"), reponse.elmTokens)
    }

    // (d) CAN ERROR seul.
    @Test
    fun canError() {
        val codec = ElmCodec()

        val reponse = classe(codec, "0100", "CAN ERROR\r>")

        assertEquals(ElmStatus.ELM_ERROR, reponse.status)
        assertEquals(emptyList(), reponse.lines)
        assertEquals(listOf("CAN ERROR"), reponse.elmTokens)
    }

    // (e) BUFFER FULL après une ligne partielle : prioritaire sur les lignes.
    @Test
    fun bufferFullPrioritaireSurLesLignes() {
        val codec = ElmCodec()

        val reponse = classe(codec, "22F100", "7E8 10 14 62 F1\rBUFFER FULL\r>")

        assertEquals(ElmStatus.BUFFER_FULL, reponse.status)
        assertEquals(listOf("7E8 10 14 62 F1"), reponse.lines)
        assertEquals(listOf("BUFFER FULL"), reponse.elmTokens)
    }

    // (f) '?' : commande non comprise.
    @Test
    fun pointInterrogation() {
        val codec = ElmCodec()

        val reponse = classe(codec, "ATXX", "?>")

        assertEquals(ElmStatus.ELM_ERROR, reponse.status)
        assertEquals(emptyList(), reponse.lines)
        assertEquals(listOf("?"), reponse.elmTokens)
    }

    // (g) OK seul (commande AT) : positif, lignes vides, token OK.
    @Test
    fun okSeulPositif() {
        val codec = ElmCodec()

        val reponse = classe(codec, "ATE0", "OK\r>")

        assertEquals(ElmStatus.POSITIVE, reponse.status)
        assertEquals(emptyList(), reponse.lines)
        assertEquals(listOf("OK"), reponse.elmTokens)
    }

    // (h) Écho de la commande : retiré des lignes, conservé dans le brut.
    @Test
    fun echoRetireDesLignes() {
        val codec = ElmCodec()

        val reponse = classe(codec, "0100", "0100\r4100 983B 8013\r>")

        assertEquals(ElmStatus.POSITIVE, reponse.status)
        assertEquals(listOf("4100 983B 8013"), reponse.lines)
        assertEquals(emptyList(), reponse.elmTokens)
        assertEquals("0100\r4100 983B 8013\r", reponse.rawText)
    }

    // (i) Multi-ECU : les deux lignes d'en-tête restent dans lines.
    @Test
    fun multiEcuDeuxLignes() {
        val codec = ElmCodec()

        val reponse = classe(
            codec,
            "0100",
            "7E8 06 41 00 98 3B 80 13\r7E9 06 41 00 88 18 00 10\r>",
        )

        assertEquals(ElmStatus.POSITIVE, reponse.status)
        assertEquals(
            listOf("7E8 06 41 00 98 3B 80 13", "7E9 06 41 00 88 18 00 10"),
            reponse.lines,
        )
        assertEquals(emptyList(), reponse.elmTokens)
    }

    // (j) Flux coupé en pleine ligne, prompt seul dans le dernier chunk.
    @Test
    fun promptSeulDansUnChunkEtCoupeEnPleineLigne() {
        val codec = ElmCodec()

        assertEquals(emptyList(), codec.feed(bytes("7E8 06 41")))
        assertEquals(emptyList(), codec.feed(bytes(" 00 98 3B 80 13\r")))

        val blocs = codec.feed(bytes(">"))
        assertEquals(listOf("7E8 06 41 00 98 3B 80 13\r"), blocs)

        val reponse = codec.classify("0100", blocs.single(), 5L)
        assertEquals(ElmStatus.POSITIVE, reponse.status)
        assertEquals(listOf("7E8 06 41 00 98 3B 80 13"), reponse.lines)
        assertEquals(emptyList(), reponse.elmTokens)
    }

    // (k) Deux réponses dans un seul chunk : feed retourne deux blocs.
    @Test
    fun deuxBlocsDansUnSeulChunk() {
        val codec = ElmCodec()

        val blocs = codec.feed(bytes("OK\r>NO DATA\r>"))

        assertEquals(listOf("OK\r", "NO DATA\r"), blocs)

        val premiere = codec.classify("ATE0", blocs[0], 1L)
        assertEquals(ElmStatus.POSITIVE, premiere.status)
        assertEquals(emptyList(), premiere.lines)
        assertEquals(listOf("OK"), premiere.elmTokens)

        val deuxieme = codec.classify("0100", blocs[1], 2L)
        assertEquals(ElmStatus.NO_DATA, deuxieme.status)
        assertEquals(emptyList(), deuxieme.lines)
        assertEquals(listOf("NO DATA"), deuxieme.elmTokens)
    }

    // (l) NRC sans en-têtes : unique ligne commençant par 7F.
    @Test
    fun nrcSansEntetes() {
        val codec = ElmCodec()

        val reponse = classe(codec, "220100", "7F2231\r>")

        assertEquals(ElmStatus.NRC, reponse.status)
        assertEquals(listOf("7F2231"), reponse.lines)
        assertEquals(emptyList(), reponse.elmTokens)
    }

    // (m) Ligne BUS INIT: : token, jamais dans lines.
    @Test
    fun busInitEstUnToken() {
        val codec = ElmCodec()

        val reponse = classe(
            codec,
            "0100",
            "BUS INIT: OK\r7E8 06 41 00 98 3B 80 13\r>",
        )

        assertEquals(ElmStatus.POSITIVE, reponse.status)
        assertEquals(listOf("7E8 06 41 00 98 3B 80 13"), reponse.lines)
        assertEquals(listOf("BUS INIT: OK"), reponse.elmTokens)
    }

    // (n) pending() expose le partiel non terminé, reset() le vide.
    @Test
    fun pendingEtReset() {
        val codec = ElmCodec()

        assertEquals("", codec.pending())
        assertEquals(emptyList(), codec.feed(bytes("7E8 06 41")))
        assertEquals("7E8 06 41", codec.pending())

        codec.reset()
        assertEquals("", codec.pending())

        // Après reset, le partiel abandonné ne pollue pas le bloc suivant.
        assertEquals(listOf("OK\r"), codec.feed(bytes("OK\r>")))
        assertEquals("", codec.pending())
    }

    // Tokens insensibles à la casse, consignés tels que reçus.
    @Test
    fun tokenMinusculeReconnuEtConsigneTelQuel() {
        val codec = ElmCodec()

        val reponse = classe(codec, "0100", "no data\r>")

        assertEquals(ElmStatus.NO_DATA, reponse.status)
        assertEquals(emptyList(), reponse.lines)
        assertEquals(listOf("no data"), reponse.elmTokens)
    }

    // UNABLE TO CONNECT : erreur ELM.
    @Test
    fun unableToConnect() {
        val codec = ElmCodec()

        val reponse = classe(codec, "0100", "UNABLE TO CONNECT\r>")

        assertEquals(ElmStatus.ELM_ERROR, reponse.status)
        assertEquals(emptyList(), reponse.lines)
        assertEquals(listOf("UNABLE TO CONNECT"), reponse.elmTokens)
    }
}
