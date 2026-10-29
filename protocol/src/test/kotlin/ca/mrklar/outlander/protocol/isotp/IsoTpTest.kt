package ca.mrklar.outlander.protocol.isotp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IsoTpTest {

    /** Convertit une chaîne hex espacée ("62 F1 90") en octets. */
    private fun octets(hex: String): ByteArray {
        val tokens = hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return ByteArray(tokens.size) { tokens[it].toInt(16).toByte() }
    }

    private fun reassembleLines(vararg lines: String): Map<String, Reassembly> =
        reassemble(parseCanLines(lines.toList()))

    // ------------------------------------------------------------ parse (P1-P4)

    // (P1) En-tête 8 hex minuscules normalisé en majuscules, octets minuscules acceptés.
    @Test
    fun parseEnteteHuitHexMinusculeNormaliseMajuscule() {
        val frames = parseCanLines(listOf("18daf1db 03 62 f1 90"))

        assertEquals(1, frames.size)
        assertEquals("18DAF1DB", frames[0].headerHex)
        assertTrue(octets("03 62 F1 90").contentEquals(frames[0].bytes))
    }

    // (P2) Ligne sans en-tête : tous les tokens sont des octets, headerHex vide.
    @Test
    fun parseLigneSansEnteteTousLesTokensSontDesOctets() {
        val frames = parseCanLines(listOf("62 F1 90 41"))

        assertEquals(1, frames.size)
        assertEquals("", frames[0].headerHex)
        assertEquals(4, frames[0].bytes.size)
        assertTrue(octets("62 F1 90 41").contentEquals(frames[0].bytes))
    }

    // (P3) Token d'octet invalide : ligne entière ignorée, jamais d'exception.
    @Test
    fun parseTokenInvalideLigneIgnoree() {
        val frames = parseCanLines(listOf("7E8 ZZ 00"))

        assertEquals(emptyList(), frames)
    }

    // (P4) Égalité par contenu de deux CanFrame identiques (et hashCode cohérent).
    @Test
    fun canFrameEgaliteParContenu() {
        val a = CanFrame("7E8", octets("05 62 F1"))
        val b = CanFrame("7E8", octets("05 62 F1"))
        val c = CanFrame("7E8", octets("05 62 F2"))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    // ------------------------------------------------------- réassemblage (1-7)

    // (1) Oracle ancien modèle : 8 lignes publiées -> Complete de 55 octets.
    // Charge : 61 01 + 53 items ; payload[29..30] = items 27+28 = 01 54 (34,0 Ah).
    @Test
    fun oracleAncienModeleReassemble55Octets() {
        val texte = javaClass.getResource("/fixtures/legacy-2101-anko.txt")!!.readText()
        val frames = parseCanLines(texte.lines())
        assertEquals(8, frames.size)

        val resultat = reassemble(frames)

        assertEquals(listOf("762"), resultat.keys.toList())
        val reassemblage = resultat.getValue("762")
        assertTrue(reassemblage is Reassembly.Complete)
        val payload = reassemblage.payload
        assertEquals(55, payload.size)
        assertEquals(0x61.toByte(), payload[0])
        assertEquals(0x01.toByte(), payload[1])
        assertEquals(0x01.toByte(), payload[29])
        assertEquals(0x54.toByte(), payload[30])
    }

    // (2) Single Frame PCI : l'octet PCI 05 déclare 5 octets de charge.
    @Test
    fun singleFramePci() {
        val resultat = reassembleLines("7E8 05 62 F1 90 4A 41")

        assertEquals(
            Reassembly.Complete(octets("62 F1 90 4A 41")),
            resultat.getValue("7E8"),
        )
    }

    // (3) Présentation longueur (ATCAF1 sans PCI) : 04 déclare 4 octets de charge.
    @Test
    fun presentationLongueurSansPci() {
        val resultat = reassembleLines("7E8 04 41 00 98 3B")

        assertEquals(
            Reassembly.Complete(octets("41 00 98 3B")),
            resultat.getValue("7E8"),
        )
    }

    // (4) Multi-ECU entrelacé : deux entrées séparées, ordre d'arrivée des groupes.
    @Test
    fun multiEcuEntrelaceDeuxEntreesSeparees() {
        val resultat = reassembleLines(
            "7E8 10 08 62 F1 90 41 42 43",
            "7E9 03 41 00 AA",
            "7E8 21 44 45",
        )

        assertEquals(listOf("7E8", "7E9"), resultat.keys.toList())
        assertEquals(
            Reassembly.Complete(octets("62 F1 90 41 42 43 44 45")),
            resultat.getValue("7E8"),
        )
        assertEquals(
            Reassembly.Complete(octets("41 00 AA")),
            resultat.getValue("7E9"),
        )
    }

    // (5) CF hors séquence (21 puis 23, le 22 manque) : Incomplete, jamais Complete.
    @Test
    fun cfHorsSequenceIncomplete() {
        val resultat = reassembleLines(
            "762 10 37 61 01 82 83 0F 8B",
            "762 21 24 0F 88 03 0C 6E 52",
            "762 23 00 00 00 01 00 01 30",
        )

        assertTrue(resultat.getValue("762") is Reassembly.Incomplete)
    }

    // (6) FF seule sans CF : Incomplete(expected=55, received=6).
    @Test
    fun ffSeuleSansCfIncomplete() {
        val resultat = reassembleLines("762 10 37 61 01 82 83 0F 8B")

        assertEquals(
            Reassembly.Incomplete(expectedBytes = 55, receivedBytes = 6),
            resultat.getValue("762"),
        )
    }

    // (7) Lignes indexées 0:/1: : headerHex vide, concaténation dans l'ordre.
    @Test
    fun lignesIndexeesConcatenationDansOrdre() {
        val resultat = reassembleLines(
            "0: 10 0A 62 F1 90 31 32 33",
            "1: 21 34 35 36 37",
        )

        assertEquals(listOf(""), resultat.keys.toList())
        assertEquals(
            Reassembly.Complete(octets("62 F1 90 31 32 33 34 35 36 37")),
            resultat.getValue(""),
        )
    }

    // Ligne indexée à plusieurs chiffres : en-tête absent, octets après les deux-points.
    @Test
    fun parseLigneIndexeePlusieursChiffres() {
        val frames = parseCanLines(listOf("12: 62 F1"))

        assertEquals(1, frames.size)
        assertEquals("", frames[0].headerHex)
        assertTrue(octets("62 F1").contentEquals(frames[0].bytes))
    }

    // FF + une CF puis fin : receivedBytes cumule les charges hors PCI (6 + 7 = 13).
    @Test
    fun ffSuivieDUneCfPuisFinIncompleteCumule() {
        val resultat = reassembleLines(
            "762 10 37 61 01 82 83 0F 8B",
            "762 21 24 0F 88 03 0C 6E 52",
        )

        assertEquals(
            Reassembly.Incomplete(expectedBytes = 55, receivedBytes = 13),
            resultat.getValue("762"),
        )
    }

    // -------------------------------------------------- précisions du meneur

    // Longueur FF inférieure à la charge de la First Frame : Complete tronqué.
    @Test
    fun ffLongueurInferieureALaChargeCompleteTronque() {
        val resultat = reassembleLines("7E8 10 03 62 F1 90 41 42")

        assertEquals(
            Reassembly.Complete(octets("62 F1 90")),
            resultat.getValue("7E8"),
        )
    }

    // CF en trop après complétude : ignorées (même hors séquence).
    @Test
    fun cfEnTropApresCompletudeIgnorees() {
        val resultat = reassembleLines(
            "7E8 10 08 62 F1 90 41 42 43",
            "7E8 21 44 45",
            "7E8 2F AA BB",
        )

        assertEquals(
            Reassembly.Complete(octets("62 F1 90 41 42 43 44 45")),
            resultat.getValue("7E8"),
        )
    }

    // Trame unique qui n'est ni SF PCI ni présentation longueur : brute telle quelle.
    @Test
    fun trameUniqueNonIsoTpBrute() {
        val resultat = reassembleLines("62 F1 90 41")

        assertEquals(
            Reassembly.NotIsoTp(octets("62 F1 90 41")),
            resultat.getValue(""),
        )
    }

    // Séquence CF modulo 16 : 1, 2, ..., 15, 0, 1.
    @Test
    fun sequenceCfBoucleModuloSeize() {
        // FF porte 2 octets, puis 17 CF d'un octet de charge chacune : total 19.
        val frames = mutableListOf(CanFrame("7E8", octets("10 13 AA BB")))
        var seq = 1
        val attendu = mutableListOf(0xAA.toByte(), 0xBB.toByte())
        for (i in 0 until 17) {
            val charge = (0x30 + i).toByte()
            attendu.add(charge)
            frames.add(CanFrame("7E8", byteArrayOf((0x20 or seq).toByte(), charge)))
            seq = (seq + 1) and 0x0F
        }

        val resultat = reassemble(frames)

        assertEquals(
            Reassembly.Complete(ByteArray(attendu.size) { attendu[it] }),
            resultat.getValue("7E8"),
        )
    }

    // Égalité par contenu des charges Complete / NotIsoTp, types distincts.
    @Test
    fun reassemblyEgaliteParContenu() {
        assertEquals(
            Reassembly.Complete(octets("62 F1 90")),
            Reassembly.Complete(octets("62 F1 90")),
        )
        assertEquals(
            Reassembly.Complete(octets("62 F1 90")).hashCode(),
            Reassembly.Complete(octets("62 F1 90")).hashCode(),
        )
        assertEquals(
            Reassembly.NotIsoTp(octets("62 F1 90")),
            Reassembly.NotIsoTp(octets("62 F1 90")),
        )
        assertNotEquals<Reassembly>(
            Reassembly.Complete(octets("62 F1 90")),
            Reassembly.NotIsoTp(octets("62 F1 90")),
        )
    }
}
