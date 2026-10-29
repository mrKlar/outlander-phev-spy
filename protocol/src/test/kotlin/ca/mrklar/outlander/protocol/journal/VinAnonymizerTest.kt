package ca.mrklar.outlander.protocol.journal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VinAnonymizerTest {

    private val vin = "JA4J24A58RZ000001"

    private fun hexEspaceMaj(v: String): String =
        v.map { "%02X".format(it.code) }.joinToString(" ")

    // (f) Hex espacé majuscule dans une ligne JSONL fabriquée.
    @Test
    fun hexEspaceMajusculeMasque() {
        val ligne = """{"response":{"raw_text":"62 F1 90 ${hexEspaceMaj(vin)}","status":"positive"}}"""
        val sortie = VinAnonymizer.anonymizeLine(ligne, vin)

        assertFalse(sortie.contains("JA4J"), sortie)
        assertFalse(sortie.contains("4A 41 34"), sortie)
        assertFalse(sortie.contains(hexEspaceMaj(vin)), sortie)
        assertEquals(ligne.length, sortie.length)
        assertTrue(sortie.contains("3F 3F 3F"), sortie)
        // Le reste de la ligne est intact.
        assertTrue(sortie.contains("62 F1 90 "), sortie)
    }

    // (g) VIN ASCII direct, casse mixte incluse.
    @Test
    fun asciiDirectMasque() {
        val texte = "vin=$vin et encore ja4j24a58rz000001 fin"
        val sortie = VinAnonymizer.anonymizeText(texte, vin)

        assertFalse(sortie.contains("JA4J", ignoreCase = true), sortie)
        assertEquals(texte.length, sortie.length)
        assertEquals("vin=${"?".repeat(17)} et encore ${"?".repeat(17)} fin", sortie)
    }

    @Test
    fun hexCompactMajusculeEtMinuscule() {
        val compactMaj = vin.map { "%02X".format(it.code) }.joinToString("")
        val compactMin = compactMaj.lowercase()

        val sortieMaj = VinAnonymizer.anonymizeText("x${compactMaj}y", vin)
        assertEquals("x${"3F".repeat(17)}y", sortieMaj)

        val sortieMin = VinAnonymizer.anonymizeText("x${compactMin}y", vin)
        assertEquals("x${"3f".repeat(17)}y", sortieMin)
    }

    @Test
    fun hexEspaceMinusculeMasqueEnMinuscule() {
        val espaceMin = hexEspaceMaj(vin).lowercase()
        val sortie = VinAnonymizer.anonymizeText(espaceMin, vin)
        assertEquals(List(17) { "3f" }.joinToString(" "), sortie)
    }

    // Variante non énumérée par la brief : hex compact en casse mixte.
    @Test
    fun hexCompactCasseMixteMasque() {
        val compactMaj = vin.map { "%02X".format(it.code) }.joinToString("")
        val mixte = compactMaj.mapIndexed { i, c ->
            if (i % 2 == 0) c.lowercaseChar() else c
        }.joinToString("")

        val sortie = VinAnonymizer.anonymizeText("=$mixte=", vin)
        assertFalse(sortie.contains(mixte), sortie)
        assertEquals("=$mixte=".length, sortie.length)
        assertTrue(sortie.contains("3F".repeat(17)), sortie)
    }

    // (h) Longueur de VIN invalide rejetée.
    @Test
    fun longueurInvalideRejetee() {
        assertFailsWith<IllegalArgumentException> {
            VinAnonymizer.anonymizeText("rien", "TROPCOURT")
        }
        assertFailsWith<IllegalArgumentException> {
            VinAnonymizer.anonymizeLine("rien", "JA4J24A58RZ0000011")
        }
    }
}
