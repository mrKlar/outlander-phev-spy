package ca.mrklar.outlander.protocol.uds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Test de propriété de la liste blanche (spec §5.1–§5.3, §10 point 5).
 *
 * La table de vérité ci-dessous est recopiée littéralement de la spec,
 * indépendamment du code de [RequestGuard] : ensembles nommés, aucun
 * partage de constante avec l'implémentation.
 */
class RequestGuardTest {

    // ---- Table de vérité (spec §5) -------------------------------------
    private val modesLegiferes = setOf(0x01, 0x02, 0x03, 0x07, 0x09, 0x0A)
    private val sousFonctions10 = setOf(0x01, 0x03)
    private val sousFonctions19 = setOf(0x01, 0x02, 0x0A)
    private val valeurs3E = setOf(0x00, 0x80)
    private val requetes21 = setOf(0x01, 0x02)

    private val canaux = listOf(
        EcuChannel.LEGISLATED_11,
        EcuChannel.FUNCTIONAL_29,
        EcuChannel.physical11(0x7E0, 0x7E8),
        EcuChannel.BMU_DB,
        EcuChannel.LEGACY_BMU,
    )

    private fun octets(vararg valeurs: Int): ByteArray =
        ByteArray(valeurs.size) { valeurs[it].toByte() }

    private fun autorise(canal: EcuChannel, requete: ByteArray): Boolean =
        RequestGuard.check(canal, requete) is GuardResult.Allowed

    private fun hex(requete: ByteArray): String =
        requete.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    // ---- Propriétés exhaustives ----------------------------------------

    /** 256 premiers octets, requête [service, 0x00], sur les 5 canaux.
     *  Attendu d'après §5 : modes légiférés → oui ; [3E 00] → oui ;
     *  0x10/0x19 avec sous-fonction 0x00 → non ; 0x22 à 2 octets → non ;
     *  [21 00] → non partout ; tout le reste → non. */
    @Test
    fun proprietePremiersOctets() {
        for (canal in canaux) {
            for (service in 0..255) {
                val requete = octets(service, 0x00)
                val attendu = service in modesLegiferes || service == 0x3E
                assertEquals(
                    attendu,
                    autorise(canal, requete),
                    "requête ${hex(requete)} sur canal tx=0x${canal.txId.toString(16)}",
                )
            }
        }
    }

    /** 256 sous-fonctions de 0x10 : {01, 03} et canal physique seulement. */
    @Test
    fun proprieteSousFonctions10() {
        for (canal in canaux) {
            for (sub in 0..255) {
                val requete = octets(0x10, sub)
                val attendu = sub in sousFonctions10 && !canal.functional
                assertEquals(
                    attendu,
                    autorise(canal, requete),
                    "requête ${hex(requete)} functional=${canal.functional}",
                )
            }
        }
    }

    /** 256 sous-fonctions de 0x19 : {01, 02, 0A}, formes 2 et 3 octets. */
    @Test
    fun proprieteSousFonctions19() {
        for (canal in canaux) {
            for (sub in 0..255) {
                val attendu = sub in sousFonctions19
                assertEquals(
                    attendu,
                    autorise(canal, octets(0x19, sub)),
                    "requête 19%02X".format(sub),
                )
                assertEquals(
                    attendu,
                    autorise(canal, octets(0x19, sub, 0xFF)),
                    "requête 19%02XFF".format(sub),
                )
            }
        }
    }

    /** 256 valeurs de 0x3E : uniquement 3E 00 et 3E 80. */
    @Test
    fun proprieteValeurs3E() {
        for (canal in canaux) {
            for (valeur in 0..255) {
                assertEquals(
                    valeur in valeurs3E,
                    autorise(canal, octets(0x3E, valeur)),
                    "requête 3E%02X".format(valeur),
                )
            }
        }
    }

    /** Longueurs de requête 0x22 de 0 à 4 octets : 3 exactement ;
     *  à 3 octets, aucun des deux octets de DID n'est contraint. */
    @Test
    fun proprieteLongueurs22() {
        for (canal in canaux) {
            assertEquals(false, autorise(canal, ByteArray(0)), "requête vide")
            assertEquals(false, autorise(canal, octets(0x22)), "22 seul")
            assertEquals(false, autorise(canal, octets(0x22, 0xF1)), "22F1")
            assertEquals(true, autorise(canal, octets(0x22, 0xF1, 0x90)), "22F190")
            assertEquals(false, autorise(canal, octets(0x22, 0xF1, 0x90, 0x00)), "22F19000")
            for (haut in 0..255) {
                assertEquals(true, autorise(canal, octets(0x22, haut, 0x90)), "DID haut %02X".format(haut))
            }
            for (bas in 0..255) {
                assertEquals(true, autorise(canal, octets(0x22, 0xF1, bas)), "DID bas %02X".format(bas))
            }
        }
    }

    /** 256 valeurs de 0x21 : {01, 02} et canal LEGACY_BMU seulement. */
    @Test
    fun proprieteException21() {
        for (canal in canaux) {
            for (valeur in 0..255) {
                val attendu = canal == EcuChannel.LEGACY_BMU && valeur in requetes21
                assertEquals(
                    attendu,
                    autorise(canal, octets(0x21, valeur)),
                    "requête 21%02X".format(valeur),
                )
            }
        }
    }

    /** Longueurs fermées des services conditionnels. */
    @Test
    fun proprieteLongueursConditionnelles() {
        for (canal in canaux) {
            assertEquals(false, autorise(canal, octets(0x10)), "10 seul")
            assertEquals(false, autorise(canal, octets(0x10, 0x01, 0x00)), "100100")
            assertEquals(false, autorise(canal, octets(0x19)), "19 seul")
            assertEquals(false, autorise(canal, octets(0x3E)), "3E seul")
            assertEquals(false, autorise(canal, octets(0x3E, 0x00, 0x00)), "3E0000")
        }
        assertEquals(false, autorise(EcuChannel.LEGACY_BMU, octets(0x21)), "21 seul sur 761")
        assertEquals(false, autorise(EcuChannel.LEGACY_BMU, octets(0x21, 0x01, 0x00)), "210100 sur 761")
    }

    // ---- Cas nommés du brief --------------------------------------------

    @Test
    fun casNommes() {
        val physique11 = EcuChannel.physical11(0x7E0, 0x7E8)

        // 2101/2102 refusés hors LEGACY_BMU, autorisés sur LEGACY_BMU ; 2103 partout refusé.
        for (canal in listOf(EcuChannel.LEGISLATED_11, EcuChannel.FUNCTIONAL_29, physique11, EcuChannel.BMU_DB)) {
            assertEquals(false, autorise(canal, octets(0x21, 0x01)), "2101 hors 761")
            assertEquals(false, autorise(canal, octets(0x21, 0x02)), "2102 hors 761")
        }
        assertEquals(true, autorise(EcuChannel.LEGACY_BMU, octets(0x21, 0x01)), "2101 sur 761")
        assertEquals(true, autorise(EcuChannel.LEGACY_BMU, octets(0x21, 0x02)), "2102 sur 761")
        for (canal in canaux) {
            assertEquals(false, autorise(canal, octets(0x21, 0x03)), "2103 refusé partout")
        }

        // 1003 refusé en fonctionnel, autorisé en physique.
        assertEquals(false, autorise(EcuChannel.FUNCTIONAL_29, octets(0x10, 0x03)), "1003 fonctionnel 29")
        assertEquals(false, autorise(EcuChannel.LEGISLATED_11, octets(0x10, 0x03)), "1003 fonctionnel 11")
        assertEquals(true, autorise(EcuChannel.BMU_DB, octets(0x10, 0x03)), "1003 physique 29")
        assertEquals(true, autorise(physique11, octets(0x10, 0x03)), "1003 physique 11")

        // Liste noire nommée : refusée comme tout le reste hors liste blanche.
        for (canal in canaux) {
            assertEquals(false, autorise(canal, octets(0x27, 0x01)), "27 SecurityAccess")
            assertEquals(false, autorise(canal, octets(0x14, 0xFF, 0xFF, 0xFF)), "14 ClearDTC")
            assertEquals(false, autorise(canal, octets(0x2E, 0xF1, 0x90, 0x00)), "2E WriteDID")
            assertEquals(false, autorise(canal, octets(0x04)), "mode 04")
            assertEquals(false, autorise(canal, octets(0x08, 0x00)), "mode 08")
        }

        // 2201D1 autorisé partout ; 22 seul refusé.
        for (canal in canaux) {
            assertEquals(true, autorise(canal, octets(0x22, 0x01, 0xD1)), "2201D1")
            assertEquals(false, autorise(canal, octets(0x22)), "22 seul")
        }

        // Formes légiférées réelles : 03/07/0A s'envoient sans PID, 0100 porte un PID.
        assertEquals(true, autorise(EcuChannel.LEGISLATED_11, octets(0x03)), "03 seul")
        assertEquals(true, autorise(EcuChannel.LEGISLATED_11, octets(0x01, 0x00)), "0100")
    }

    // ---- assertAllowed ----------------------------------------------------

    @Test
    fun assertAllowedNeLevePasSurAutorise() {
        RequestGuard.assertAllowed(EcuChannel.BMU_DB, octets(0x22, 0x01, 0xD1))
        RequestGuard.assertAllowed(EcuChannel.LEGISLATED_11, octets(0x01, 0x00))
    }

    @Test
    fun assertAllowedLeveRequestRefusedAvecMotif() {
        val exception = assertFailsWith<RequestRefusedException> {
            RequestGuard.assertAllowed(EcuChannel.BMU_DB, octets(0x27, 0x01))
        }
        assertTrue(exception.message?.isNotBlank() == true, "motif=${exception.message}")

        // RequestRefusedException est une IllegalArgumentException (contrat du brief).
        assertFailsWith<IllegalArgumentException> {
            RequestGuard.assertAllowed(EcuChannel.FUNCTIONAL_29, octets(0x10, 0x03))
        }
    }

    @Test
    fun refusPorteToujoursUnMotifNonVide() {
        val verdict = RequestGuard.check(EcuChannel.BMU_DB, octets(0x2E, 0x00))
        assertTrue(verdict is GuardResult.Refused)
        assertTrue(verdict.reason.isNotBlank(), "reason=${verdict.reason}")
    }
}
