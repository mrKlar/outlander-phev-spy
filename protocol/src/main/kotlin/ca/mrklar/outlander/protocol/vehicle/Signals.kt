package ca.mrklar.outlander.protocol.vehicle

import ca.mrklar.outlander.protocol.uds.EcuChannel

/** Statut de validation d'un décodage (spec §8.2). */
enum class SignalConfidence {
    COMMUNAUTAIRE_NON_VALIDE,
    CORRELE_TABLEAU_DE_BORD,
    VERIFIE,
}

/**
 * Définition déclarative d'un signal (spec §8.1).
 *
 * Les champs d'équation ([byteOffset], [byteLength], [scale],
 * [offset]) restent nuls tant que l'équation n'est pas établie :
 * la définition n'interroge alors qu'en brut journalisé. Les offsets
 * comptent dans la charge APRÈS l'en-tête de réponse positive
 * (ex. après `61 01` pour l'ancien `2101`, après `62 <DID>` en UDS).
 * Toute formule cite sa [source] et son statut [confidence].
 */
data class SignalDefinition(
    val id: String,
    val label: String,
    val channel: EcuChannel,
    val requestHex: String,
    val byteOffset: Int? = null,
    val byteLength: Int? = null,
    val scale: Double? = null,
    val offset: Double? = null,
    val unit: String,
    val source: String,
    val confidence: SignalConfidence,
)

/** Décodeur générique d'une [SignalDefinition] sur des données brutes. */
fun interface SignalDecoder {
    fun decode(definition: SignalDefinition, data: ByteArray): Double?
}

/**
 * Décodeur affine : assemblage big-endian non signé sur
 * [SignalDefinition.byteLength] octets à [SignalDefinition.byteOffset],
 * puis `brut × scale + offset`.
 *
 * Retourne `null` — jamais d'exception — si l'équation est incomplète
 * (`byteOffset`, `byteLength` ou `scale` nul ; `offset` nul compte
 * 0,0), si `byteLength` est hors 1..4, ou si la fenêtre demandée
 * sort de [data]. [data] est la charge APRÈS l'en-tête de réponse
 * positive, jamais la charge réassemblée complète.
 */
object AffineDecoder : SignalDecoder {

    override fun decode(definition: SignalDefinition, data: ByteArray): Double? {
        val byteOffset = definition.byteOffset ?: return null
        val byteLength = definition.byteLength ?: return null
        val scale = definition.scale ?: return null
        if (byteLength !in 1..4) return null
        if (byteOffset < 0) return null
        // Sans débordement : byteOffset peut valoir Int.MAX_VALUE.
        if (byteOffset > data.size || byteLength > data.size - byteOffset) return null

        var brut = 0L
        for (i in 0 until byteLength) {
            brut = (brut shl 8) or (data[byteOffset + i].toInt() and 0xFF).toLong()
        }
        return brut.toDouble() * scale + (definition.offset ?: 0.0)
    }
}

/**
 * Registre des signaux connus (spec §8.3 et §3.1 F4).
 *
 * À ce stade, tout est [SignalConfidence.COMMUNAUTAIRE_NON_VALIDE] :
 * aucune promotion sans corrélation sur le véhicule cible (§8.2).
 */
object KnownSignals {

    /**
     * DID Gen4 rapportés par la communauté (spec §8.3), sur
     * [EcuChannel.BMU_DB]. Aucune équation encodée : les échelles
     * sont absentes des rapports et, pour `2201D1`, l'octet visé
     * par l'équation rapportée n'est pas défini.
     */
    val gen4Community: List<SignalDefinition> = listOf(
        SignalDefinition(
            id = "bmu.soc.did01d1",
            label = "SOC (piste)",
            channel = EcuChannel.BMU_DB,
            requestHex = "2201D1",
            unit = "%",
            source = "spec §8.3 / R2 profil communautaire oct. 2025",
            confidence = SignalConfidence.COMMUNAUTAIRE_NON_VALIDE,
        ),
        SignalDefinition(
            id = "bmu.socmax.did01d0",
            label = "SOC_MAX rapporté (non validé)",
            channel = EcuChannel.BMU_DB,
            requestHex = "2201D0",
            unit = "?",
            source = "spec §8.3 / R2 profil communautaire oct. 2025",
            confidence = SignalConfidence.COMMUNAUTAIRE_NON_VALIDE,
        ),
        SignalDefinition(
            id = "bmu.hvVoltage.did0106",
            label = "Tension HV (piste)",
            channel = EcuChannel.BMU_DB,
            requestHex = "220106",
            unit = "V",
            source = "spec §8.3 / R2 profil communautaire oct. 2025",
            confidence = SignalConfidence.COMMUNAUTAIRE_NON_VALIDE,
        ),
        SignalDefinition(
            id = "bmu.cellMax.did0178",
            label = "Tension cellule max (piste)",
            channel = EcuChannel.BMU_DB,
            requestHex = "220178",
            unit = "V",
            source = "spec §8.3 / R2 profil communautaire oct. 2025",
            confidence = SignalConfidence.COMMUNAUTAIRE_NON_VALIDE,
        ),
        SignalDefinition(
            id = "bmu.cellMin.did0179",
            label = "Tension cellule min (piste)",
            channel = EcuChannel.BMU_DB,
            requestHex = "220179",
            unit = "V",
            source = "spec §8.3 / R2 profil communautaire oct. 2025",
            confidence = SignalConfidence.COMMUNAUTAIRE_NON_VALIDE,
        ),
        SignalDefinition(
            id = "bmu.unknown.did010b",
            label = "DID 010B (inconnu)",
            channel = EcuChannel.BMU_DB,
            requestHex = "22010B",
            unit = "",
            source = "spec §8.3 / R2 profil communautaire oct. 2025",
            confidence = SignalConfidence.COMMUNAUTAIRE_NON_VALIDE,
        ),
        SignalDefinition(
            id = "bmu.unknown.did022a",
            label = "DID 022A (inconnu)",
            channel = EcuChannel.BMU_DB,
            requestHex = "22022A",
            unit = "",
            source = "spec §8.3 / R2 profil communautaire oct. 2025",
            confidence = SignalConfidence.COMMUNAUTAIRE_NON_VALIDE,
        ),
        SignalDefinition(
            id = "bmu.unknown.didf0d0",
            label = "DID F0D0 (inconnu)",
            channel = EcuChannel.BMU_DB,
            requestHex = "22F0D0",
            unit = "",
            source = "spec §8.3 / R2 profil communautaire oct. 2025",
            confidence = SignalConfidence.COMMUNAUTAIRE_NON_VALIDE,
        ),
    )

    /**
     * Décodages de l'ancien `2101` (spec §3.1 F4), sur
     * [EcuChannel.LEGACY_BMU]. Items comptés à partir de 0 après
     * `61 01`. Conservés comme oracle de test et comme définitions
     * conditionnées à un positif de l'étape héritage (§8.4).
     */
    val legacy2101: List<SignalDefinition> = listOf(
        SignalDefinition(
            id = "legacy.soc.2101",
            label = "SOC ancien modèle (2101)",
            channel = EcuChannel.LEGACY_BMU,
            requestHex = "2101",
            byteOffset = 0,
            byteLength = 1,
            scale = 0.5,
            offset = -5.0,
            unit = "%",
            source = "spec §3.1 F4 / R1 anko",
            confidence = SignalConfidence.COMMUNAUTAIRE_NON_VALIDE,
        ),
        SignalDefinition(
            id = "legacy.packVoltage.2101",
            label = "Tension pack ancien modèle (2101)",
            channel = EcuChannel.LEGACY_BMU,
            requestHex = "2101",
            byteOffset = 8,
            byteLength = 2,
            scale = 0.1,
            offset = 0.0,
            unit = "V",
            source = "spec §3.1 F4 / R1 anko",
            confidence = SignalConfidence.COMMUNAUTAIRE_NON_VALIDE,
        ),
        SignalDefinition(
            id = "legacy.capacityAh.2101",
            label = "Capacité ancien modèle (2101)",
            channel = EcuChannel.LEGACY_BMU,
            requestHex = "2101",
            byteOffset = 27,
            byteLength = 2,
            scale = 0.1,
            offset = 0.0,
            unit = "Ah",
            source = "spec §3.1 F4 / R1 anko",
            confidence = SignalConfidence.COMMUNAUTAIRE_NON_VALIDE,
        ),
        SignalDefinition(
            id = "legacy.remainingAh.2101",
            label = "Charge restante ancien modèle (2101)",
            channel = EcuChannel.LEGACY_BMU,
            requestHex = "2101",
            byteOffset = 29,
            byteLength = 2,
            scale = 0.1,
            offset = 0.0,
            unit = "Ah",
            source = "spec §3.1 F4 / R1 anko",
            confidence = SignalConfidence.COMMUNAUTAIRE_NON_VALIDE,
        ),
    )

    val all: List<SignalDefinition>
        get() = gen4Community + legacy2101

    fun byId(id: String): SignalDefinition? = all.firstOrNull { it.id == id }
}
