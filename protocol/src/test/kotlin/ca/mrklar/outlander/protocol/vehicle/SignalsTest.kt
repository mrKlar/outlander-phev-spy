package ca.mrklar.outlander.protocol.vehicle

import ca.mrklar.outlander.protocol.isotp.Reassembly
import ca.mrklar.outlander.protocol.isotp.parseCanLines
import ca.mrklar.outlander.protocol.isotp.reassemble
import ca.mrklar.outlander.protocol.uds.EcuChannel
import ca.mrklar.outlander.protocol.uds.GuardResult
import ca.mrklar.outlander.protocol.uds.RequestGuard
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signaux data-driven et décodeur affine (spec §8, tâche 7b).
 *
 * L'oracle est la fixture publiée de l'ancien modèle (tâche 5) :
 * réassemblage ISO-TP de 55 octets, items comptés après `61 01`.
 */
class SignalsTest {

    /** Charge fixture après l'en-tête de réponse positive `61 01` (53 items). */
    private fun donneesFixture(): ByteArray {
        val texte = javaClass.getResource("/fixtures/legacy-2101-anko.txt")!!.readText()
        val resultat = reassemble(parseCanLines(texte.lines()))
        val reassemblage = resultat.getValue("762")
        assertTrue(reassemblage is Reassembly.Complete)
        val payload = reassemblage.payload
        assertEquals(55, payload.size)
        assertEquals(0x61.toByte(), payload[0])
        assertEquals(0x01.toByte(), payload[1])
        return payload.drop(2).toByteArray()
    }

    private fun parseHex(hex: String): ByteArray {
        val propre = hex.filterNot { it.isWhitespace() }
        return ByteArray(propre.length / 2) {
            propre.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    /** Définition ad hoc pour exercer le seul décodeur. */
    private fun definitionTest(
        byteOffset: Int? = 0,
        byteLength: Int? = 1,
        scale: Double? = 1.0,
        offset: Double? = 0.0,
    ): SignalDefinition = SignalDefinition(
        id = "test.decodeur",
        label = "Test décodeur",
        channel = EcuChannel.BMU_DB,
        requestHex = "2201D1",
        byteOffset = byteOffset,
        byteLength = byteLength,
        scale = scale,
        offset = offset,
        unit = "",
        source = "test",
        confidence = SignalConfidence.COMMUNAUTAIRE_NON_VALIDE,
    )

    // ----------------------------------------------------- (1) pipeline oracle

    // Items 27+28 = 01 54 = 340 ; 340 × 0,1 = 34,0 Ah exactement en Double.
    @Test
    fun pipelineCapaciteOracle34AhExactement() {
        val data = donneesFixture()
        assertEquals(53, data.size)
        assertEquals(0x01.toByte(), data[27])
        assertEquals(0x54.toByte(), data[28])

        val definition = assertNotNull(KnownSignals.byId("legacy.capacityAh.2101"))
        val valeur = AffineDecoder.decode(definition, data)

        assertEquals(34.0, valeur)
    }

    // Item 0 = 0x82 = 130 ; 130 × 0,5 − 5 = 60,0 %.
    @Test
    fun pipelineSocOracle60PourCent() {
        val data = donneesFixture()
        assertEquals(0x82.toByte(), data[0])

        val definition = assertNotNull(KnownSignals.byId("legacy.soc.2101"))
        val valeur = AffineDecoder.decode(definition, data)

        assertEquals(60.0, valeur)
    }

    // Items 8+9 = 0C 6E = 3182 ; attendu calculé à la main depuis les octets.
    @Test
    fun pipelineTensionPackCalculeeDepuisFixture() {
        val data = donneesFixture()
        assertEquals(0x0C.toByte(), data[8])
        assertEquals(0x6E.toByte(), data[9])
        val brutManuel = ((data[8].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
        assertEquals(0x0C6E, brutManuel)
        assertEquals(3182, brutManuel)
        val attenduManuel = brutManuel * 0.1

        val definition = assertNotNull(KnownSignals.byId("legacy.packVoltage.2101"))
        val valeur = AffineDecoder.decode(definition, data)

        // Même opération Double des deux côtés : égalité exacte exigée.
        assertEquals(attenduManuel, valeur)
        // 3182 × 0,1 diffère du littéral 318.2 d'environ 1 ulp : tolérance bornée.
        assertNotNull(valeur)
        assertTrue(abs(valeur - 318.2) < 1e-9, "valeur=$valeur")
    }

    // Items 29+30 = 00 CC = 204 ; même discipline que la tension pack.
    @Test
    fun pipelineChargeRestanteCalculeeDepuisFixture() {
        val data = donneesFixture()
        assertEquals(0x00.toByte(), data[29])
        assertEquals(0xCC.toByte(), data[30])
        val brutManuel = ((data[29].toInt() and 0xFF) shl 8) or (data[30].toInt() and 0xFF)
        assertEquals(204, brutManuel)
        val attenduManuel = brutManuel * 0.1

        val definition = assertNotNull(KnownSignals.byId("legacy.remainingAh.2101"))
        val valeur = AffineDecoder.decode(definition, data)

        assertEquals(attenduManuel, valeur)
        assertNotNull(valeur)
        assertTrue(abs(valeur - 20.4) < 1e-9, "valeur=$valeur")
    }

    // ------------------------------------------- (2) Gen4 sans équation → null

    @Test
    fun gen4SansEquationDonneNull() {
        val donnees = ByteArray(32) { (it * 7 + 3).toByte() }

        assertEquals(16, KnownSignals.gen4Community.size)
        for (definition in KnownSignals.gen4Community) {
            assertNull(definition.byteOffset, definition.id)
            assertNull(definition.byteLength, definition.id)
            assertNull(definition.scale, definition.id)
            assertNull(definition.offset, definition.id)
            assertNull(AffineDecoder.decode(definition, donnees), definition.id)
        }
    }

    // ------------------------------------------------- (3) bornes du décodeur

    @Test
    fun donneesTropCourtesDonneNull() {
        val capacite = assertNotNull(KnownSignals.byId("legacy.capacityAh.2101"))
        // Il faut 27 + 2 = 29 octets ; 28, 1 et 0 sont trop courts.
        assertNull(AffineDecoder.decode(capacite, ByteArray(28)))
        assertNull(AffineDecoder.decode(capacite, ByteArray(1)))
        assertNull(AffineDecoder.decode(capacite, ByteArray(0)))
        // Borne exacte : 29 octets suffisent (brut 0 → 0,0).
        assertEquals(0.0, AffineDecoder.decode(capacite, ByteArray(29)))
    }

    @Test
    fun byteLengthHorsBornesDonneNull() {
        val donnees = ByteArray(32) { 1 }
        assertNull(AffineDecoder.decode(definitionTest(byteLength = 0), donnees))
        assertNull(AffineDecoder.decode(definitionTest(byteLength = 5), donnees))
        assertNull(AffineDecoder.decode(definitionTest(byteLength = -1), donnees))
    }

    @Test
    fun byteOffsetNegatifDonneNull() {
        val donnees = ByteArray(32) { 1 }
        assertNull(AffineDecoder.decode(definitionTest(byteOffset = -1), donnees))
    }

    @Test
    fun equationIncompleteDonneNullSaufOffset() {
        val donnees = byteArrayOf(0x2A)
        assertNull(AffineDecoder.decode(definitionTest(byteOffset = null), donnees))
        assertNull(AffineDecoder.decode(definitionTest(byteLength = null), donnees))
        assertNull(AffineDecoder.decode(definitionTest(scale = null), donnees))
        // offset null compte 0,0 : 0x2A × 2,0 = 84,0.
        assertEquals(
            84.0,
            AffineDecoder.decode(definitionTest(scale = 2.0, offset = null), donnees),
        )
    }

    @Test
    fun assemblageBigEndianNonSigne() {
        // Big-endian : 01 00 = 256 (et non 1).
        assertEquals(
            256.0,
            AffineDecoder.decode(
                definitionTest(byteLength = 2),
                byteArrayOf(0x01, 0x00),
            ),
        )
        // Non signé sur 4 octets : FF FF FF FF = 4 294 967 295 (et non −1).
        assertEquals(
            4294967295.0,
            AffineDecoder.decode(
                definitionTest(byteLength = 4),
                byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            ),
        )
        // Décalage d'offset respecté : l'octet lu est data[2].
        assertEquals(
            0x7F.toDouble(),
            AffineDecoder.decode(
                definitionTest(byteOffset = 2, byteLength = 1),
                byteArrayOf(0x00, 0x00, 0x7F),
            ),
        )
    }

    // --------------------------------------------------------- (4) registre

    @Test
    fun registreSansSohEtConfiancesCommunautaires() {
        assertEquals(16, KnownSignals.gen4Community.size)
        assertEquals(4, KnownSignals.legacy2101.size)
        assertEquals(20, KnownSignals.all.size)
        assertEquals(
            KnownSignals.gen4Community + KnownSignals.legacy2101,
            KnownSignals.all,
        )
        assertEquals(20, KnownSignals.all.map { it.id }.toSet().size)

        for (definition in KnownSignals.all) {
            assertTrue(!definition.id.contains("soh"), definition.id)
            assertEquals(
                SignalConfidence.COMMUNAUTAIRE_NON_VALIDE,
                definition.confidence,
                definition.id,
            )
        }
        // Canaux attendus par DID (répondant du profil Gen4, campagne 1).
        val canauxAttendus = mapOf(
            "2201D1" to EcuChannel.BMU_DB, "2201D0" to EcuChannel.BMU_DB,
            "220106" to EcuChannel.BMU_DB, "220178" to EcuChannel.BMU_DB,
            "220179" to EcuChannel.BMU_DB, "22010B" to EcuChannel.BMU_DB,
            "22022A" to EcuChannel.BMU_DB, "22F0D0" to EcuChannel.BMU_DB,
            "220112" to EcuChannel.ECU_E5,
            "22011A" to EcuChannel.ECU_DE, "220126" to EcuChannel.ECU_DE,
            "22011C" to EcuChannel.ECU_DE, "220119" to EcuChannel.ECU_DE,
            "220113" to EcuChannel.ECU_DE, "220107" to EcuChannel.ECU_DE,
            "220105" to EcuChannel.ECU_DE,
        )
        assertEquals(canauxAttendus.keys, KnownSignals.gen4Community.map { it.requestHex }.toSet())
        for (definition in KnownSignals.gen4Community) {
            assertEquals(canauxAttendus[definition.requestHex], definition.channel, definition.id)
            assertTrue(definition.requestHex.startsWith("22"), definition.id)
            assertEquals(
                "WiCAN #468 @amwalters 2025-10-31 (profil Gen4, non validé)",
                definition.source,
                definition.id,
            )
        }
        for (definition in KnownSignals.legacy2101) {
            assertEquals(EcuChannel.LEGACY_BMU, definition.channel, definition.id)
            assertEquals("2101", definition.requestHex, definition.id)
            assertEquals("spec §3.1 F4 / R1 anko", definition.source, definition.id)
        }
    }

    @Test
    fun definitionsGen4Attendues() {
        val attendu = listOf(
            Triple("bmu.soc.did01d1", "2201D1", "%"),
            Triple("bmu.socmax.did01d0", "2201D0", "?"),
            Triple("bmu.hvVoltage.did0106", "220106", "V"),
            Triple("bmu.cellMax.did0178", "220178", "V"),
            Triple("bmu.cellMin.did0179", "220179", "V"),
            Triple("bmu.unknown.did010b", "22010B", ""),
            Triple("bmu.unknown.did022a", "22022A", "%"),
            Triple("bmu.unknown.didf0d0", "22F0D0", "km"),
            Triple("ecu.hvAv.did0112", "220112", "kW"),
            Triple("ecu.lvVoltage.did011a", "22011A", "V"),
            Triple("ecu.chargerPressure.did0126", "220126", "psi"),
            Triple("ecu.acNegotiated.did011c", "22011C", "A"),
            Triple("ecu.acPct.did0119", "220119", "%"),
            Triple("ecu.chargerConnected.did0113", "220113", ""),
            Triple("ecu.charging.did0107", "220107", ""),
            Triple("ecu.chargingTime.did0105", "220105", "min"),
        )
        assertEquals(
            attendu,
            KnownSignals.gen4Community.map { Triple(it.id, it.requestHex, it.unit) },
        )
        assertEquals("SOC (piste)", assertNotNull(KnownSignals.byId("bmu.soc.did01d1")).label)
        assertEquals(
            "SOC_MAX rapporté (non validé)",
            assertNotNull(KnownSignals.byId("bmu.socmax.did01d0")).label,
        )
        assertEquals(
            "DID 010B (piste concurrente : capacité HV ou SOC2)",
            assertNotNull(KnownSignals.byId("bmu.unknown.did010b")).label,
        )
        assertEquals(
            "SOC (piste, DID 022A)",
            assertNotNull(KnownSignals.byId("bmu.unknown.did022a")).label,
        )
        assertEquals(
            "Odomètre (piste)",
            assertNotNull(KnownSignals.byId("bmu.unknown.didf0d0")).label,
        )
    }

    @Test
    fun definitionsLegacyAttendues() {
        fun champs(id: String): List<Any?> {
            val d = assertNotNull(KnownSignals.byId(id))
            return listOf(d.byteOffset, d.byteLength, d.scale, d.offset, d.unit)
        }
        assertEquals(listOf<Any?>(0, 1, 0.5, -5.0, "%"), champs("legacy.soc.2101"))
        assertEquals(listOf<Any?>(8, 2, 0.1, 0.0, "V"), champs("legacy.packVoltage.2101"))
        assertEquals(listOf<Any?>(27, 2, 0.1, 0.0, "Ah"), champs("legacy.capacityAh.2101"))
        assertEquals(listOf<Any?>(29, 2, 0.1, 0.0, "Ah"), champs("legacy.remainingAh.2101"))
    }

    @Test
    fun byIdTrouveEtInconnu() {
        assertEquals(
            "legacy.capacityAh.2101",
            assertNotNull(KnownSignals.byId("legacy.capacityAh.2101")).id,
        )
        assertNull(KnownSignals.byId("inconnu"))
        assertNull(KnownSignals.byId(""))
    }

    // --------------------------------------- (5) couplage liste blanche §5

    @Test
    fun chaqueRequetePasseLeGardeSurSonCanal() {
        for (definition in KnownSignals.all) {
            val requete = parseHex(definition.requestHex)
            assertEquals(
                GuardResult.Allowed,
                RequestGuard.check(definition.channel, requete),
                definition.id,
            )
            // Ne doit pas lever.
            RequestGuard.assertAllowed(definition.channel, requete)
        }
        // La voie fonctionnelle émet les mêmes 22XXXX sur FUNCTIONAL_29.
        for (definition in KnownSignals.gen4Community) {
            assertEquals(
                GuardResult.Allowed,
                RequestGuard.check(EcuChannel.FUNCTIONAL_29, parseHex(definition.requestHex)),
                definition.id,
            )
        }
    }
}
