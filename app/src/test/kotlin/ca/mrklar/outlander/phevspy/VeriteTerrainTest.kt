package ca.mrklar.outlander.phevspy

import kotlin.test.Test
import kotlin.test.assertEquals

class VeriteTerrainTest {

    // Jauge cluster de 16 barres (confirmation du propriétaire,
    // 2026-10-31) : le journal garde le compte brut, jamais un %.
    @Test
    fun `barres et autonomie donnent deux parties brutes`() {
        assertEquals(
            listOf("SOC (barres cluster): 14/16", "autonomie: 52 km"),
            partiesVeriteTerrain("14", "52"),
        )
    }

    @Test
    fun `espaces autour des saisies retires et champ vide ignore`() {
        assertEquals(
            listOf("SOC (barres cluster): 16/16"),
            partiesVeriteTerrain(" 16 ", "  "),
        )
    }

    @Test
    fun `deux champs vides donnent aucune partie`() {
        assertEquals(emptyList(), partiesVeriteTerrain("", ""))
    }
}
