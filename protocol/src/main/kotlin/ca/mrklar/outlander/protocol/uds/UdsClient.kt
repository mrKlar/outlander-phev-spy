package ca.mrklar.outlander.protocol.uds

import ca.mrklar.outlander.protocol.elm.ElmResponse
import ca.mrklar.outlander.protocol.elm.ElmSession
import ca.mrklar.outlander.protocol.elm.ElmStatus
import ca.mrklar.outlander.protocol.isotp.CanFrame
import ca.mrklar.outlander.protocol.isotp.Reassembly
import ca.mrklar.outlander.protocol.isotp.parseCanLines
import ca.mrklar.outlander.protocol.isotp.reassemble
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Un canal = une cible d'interrogation (spec §4.3). Les identifiants
 * sont des entiers CAN : [txId] `0x761` (11 bits) ou `0x18DADBF1`
 * (29 bits), [rxId] symétrique (`0` = tout en-tête accepté, aucun
 * `ATCRA` émis), [fcHeader] = ID de requête physique sur lequel
 * émettre le Flow Control (inutilisé si [functional]).
 */
data class EcuChannel(
    val txId: Long,
    val rxId: Long,
    val fcHeader: Long,
    val extended29Bit: Boolean,
    val functional: Boolean,
) {
    companion object {
        /** Légiféré 11 bits : requêtes fonctionnelles `7DF`, réponses libres. */
        val LEGISLATED_11 = EcuChannel(
            txId = 0x7DF, rxId = 0, fcHeader = 0x7DF,
            extended29Bit = false, functional = true,
        )

        /** Fonctionnel 29 bits `18DB33F1`, réponses libres. */
        val FUNCTIONAL_29 = EcuChannel(
            txId = 0x18DB33F1, rxId = 0, fcHeader = 0x18DB33F1,
            extended29Bit = true, functional = true,
        )

        /** Héritage ancien modèle `761→762` (exception fermée `2101`/`2102`, §5.3). */
        val LEGACY_BMU = EcuChannel(
            txId = 0x761, rxId = 0x762, fcHeader = 0x761,
            extended29Bit = false, functional = false,
        )

        /** Canal physique 11 bits ; le Flow Control part sur [txId]. */
        fun physical11(txId: Long, rxId: Long): EcuChannel = EcuChannel(
            txId = txId, rxId = rxId, fcHeader = txId,
            extended29Bit = false, functional = false,
        )

        /** Canal physique 29 bits d'adresse logique [addr] (`18DA<addr>F1` ↔ `18DAF1<addr>`). */
        fun logical29(addr: Int): EcuChannel {
            val a = (addr and 0xFF).toLong()
            val tx = 0x18DA00F1L or (a shl 8)
            val rx = 0x18DAF100L or a
            return EcuChannel(
                txId = tx, rxId = rx, fcHeader = tx,
                extended29Bit = true, functional = false,
            )
        }

        val BMU_DB: EcuChannel = logical29(0xDB)
        val ECU_DE: EcuChannel = logical29(0xDE)
        val ECU_E5: EcuChannel = logical29(0xE5)
    }
}

/** Réponse classifiée d'une requête UDS ou OBD légiférée (spec §4.3). */
sealed interface UdsReply {

    /**
     * Réponse positive. Pour [UdsClient] : données APRÈS l'en-tête de
     * réponse positive (`62 <DID>`, `50 <sub>`, …). Pour [ObdClient] :
     * charge COMPLÈTE, écho de mode inclus (`41 00 …`). Égalité par contenu.
     */
    data class Positive(val payload: ByteArray) : UdsReply {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Positive) return false
            return payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = payload.contentHashCode()
    }

    /** Réponse négative `7F <service> <nrc>`. */
    data class Negative(val service: Int, val nrc: Int) : UdsReply

    /** Statut ELM `NO DATA` : jamais interprété comme « ECU absent ». */
    data object NoData : UdsReply

    /** Échec de transport/classification ; porte la réponse ELM brute. */
    data class ElmFailure(val response: ElmResponse) : UdsReply
}

/**
 * Programmation des en-têtes CAN ELM d'un [EcuChannel], partagée par
 * [UdsClient] et [ObdClient] (une instance par client). Le canal
 * courant est mémorisé : reprogammer le même canal (égalité data
 * class) n'émet rien. Les réponses aux commandes AT ne sont pas
 * vérifiées ici — un refus (« ? ») reste visible au journal et la
 * matrice de capacités gouverne l'usage (tâche 8).
 *
 * Formats : 11 bits collés (`ATSH761`, `ATCRA762`, `ATFCSH761`,
 * `ATFCSD300000`, `ATFCSM1`) ; 29 bits, 3 octets bas espacés
 * (`ATSH DA DB F1`, `ATCRA DA F1 DB`, `ATFCSH DA DB F1`), `ATFCSD`
 * reste collé. Canal fonctionnel : `ATFCSM0` seul côté Flow Control
 * (répondant inconnu d'avance, FC automatique de l'ELM).
 */
internal class ChannelProgrammer(private val session: ElmSession) {

    private var canalProgramme: EcuChannel? = null

    suspend fun program(channel: EcuChannel, timeoutMs: Long, phase: String) {
        if (canalProgramme == channel) return
        for (commande in commandes(channel)) {
            session.transact(commande, timeoutMs = timeoutMs, phase = phase, kind = "at")
        }
        canalProgramme = channel
    }

    /** Émet `ATFCSD` + [fcsdHex] collé (FC bridée `300A0A`) ; ne touche pas au cache de canal. */
    suspend fun setFlowControlData(fcsdHex: String, timeoutMs: Long, phase: String): ElmResponse =
        session.transact(
            "ATFCSD" + fcsdHex.filterNot { it.isWhitespace() }.uppercase(),
            timeoutMs = timeoutMs,
            phase = phase,
            kind = "at",
        )

    private fun commandes(channel: EcuChannel): List<String> = buildList {
        add(commandeEntete("ATSH", channel.txId, channel.extended29Bit))
        if (channel.rxId != 0L) {
            add(commandeEntete("ATCRA", channel.rxId, channel.extended29Bit))
        }
        if (channel.functional) {
            add("ATFCSM0")
        } else {
            add(commandeEntete("ATFCSH", channel.fcHeader, channel.extended29Bit))
            add("ATFCSD300000")
            add("ATFCSM1")
        }
    }

    private fun commandeEntete(prefixe: String, id: Long, extended29Bit: Boolean): String =
        if (extended29Bit) {
            "%s %02X %02X %02X".format(
                prefixe,
                (id shr 16) and 0xFF,
                (id shr 8) and 0xFF,
                id and 0xFF,
            )
        } else {
            "%s%03X".format(prefixe, id)
        }
}

/**
 * Client UDS lecture seule : unique point d'émission de requêtes UDS.
 *
 * Chaque méthode construit ses octets, les soumet à
 * [RequestGuard.assertAllowed] — un refus lève
 * [RequestRefusedException] AVANT toute émission, en-têtes AT compris,
 * et sa journalisation est l'affaire de l'appelant — puis programme
 * le canal et délègue l'aller-retour à [ElmSession.transact]
 * (`kind = "uds"`). Un `7F <service> 78` prolonge l'attente dans la
 * même transaction, sans réécrire la requête.
 *
 * Ce client ne bascule pas le protocole ELM (`ATSP6`/`ATSP7`/`ATCP` :
 * tâche 8) ; l'appelant règle le protocole avant de s'en servir.
 * Les appels d'une même instance sont sérialisés (programmation +
 * requête atomiques entre eux).
 */
class UdsClient(private val session: ElmSession) {

    private val programmateur = ChannelProgrammer(session)
    private val mutex = Mutex()

    /** Programme les en-têtes du [channel] (aucune émission si déjà en vigueur). */
    suspend fun programChannel(channel: EcuChannel, timeoutMs: Long = 2000, phase: String = "manuel") {
        mutex.withLock { programmateur.program(channel, timeoutMs, phase) }
    }

    /**
     * Émet `ATFCSD` + [fcsdHex] collé (ex. `300A0A` pour la FC bridée).
     * Ne change pas le canal mémorisé : une relecture immédiate sur le
     * même canal conserve ces données FC ; programmer un autre canal
     * (ou rappeler avec `300000`) rétablit la valeur par défaut.
     */
    suspend fun setFlowControlData(
        fcsdHex: String,
        timeoutMs: Long = 2000,
        phase: String = "manuel",
    ): ElmResponse = mutex.withLock { programmateur.setFlowControlData(fcsdHex, timeoutMs, phase) }

    /** `22 <did>` : lit un DID 16 bits. */
    suspend fun readDataByIdentifier(
        channel: EcuChannel,
        did: Int,
        timeoutMs: Long = 2000,
        phase: String = "manuel",
    ): UdsReply {
        val did16 = did and 0xFFFF
        val haut = (did16 shr 8).toByte()
        val bas = did16.toByte()
        return executer(
            channel, byteArrayOf(0x22, haut, bas), timeoutMs, phase,
            AttentePositive(0x62, byteArrayOf(haut, bas)),
        )
    }

    /** `3E 00` : sonde de présence, avec réponse. */
    suspend fun testerPresent(
        channel: EcuChannel,
        timeoutMs: Long = 2000,
        phase: String = "manuel",
    ): UdsReply = executer(
        channel, byteArrayOf(0x3E, 0x00), timeoutMs, phase,
        AttentePositive(0x7E, byteArrayOf(0x00)),
    )

    /** `10 <sub>` : sous-fonctions fermées `01`/`03`, physique seulement (garde). */
    suspend fun diagnosticSessionControl(
        channel: EcuChannel,
        subFunction: Int,
        timeoutMs: Long = 2000,
        phase: String = "manuel",
    ): UdsReply {
        val sub = (subFunction and 0xFF).toByte()
        return executer(
            channel, byteArrayOf(0x10, sub), timeoutMs, phase,
            AttentePositive(0x50, byteArrayOf(sub)),
        )
    }

    /**
     * `19 <sub> [<mask>]` : sous-fonctions fermées `01`/`02`/`0A` (garde).
     * [mask] `null` omet l'octet de masque (forme 2 octets, ex. `190A`).
     */
    suspend fun readDtc(
        channel: EcuChannel,
        subFunction: Int,
        mask: Int? = 0xFF,
        timeoutMs: Long = 2000,
        phase: String = "manuel",
    ): UdsReply {
        val sub = (subFunction and 0xFF).toByte()
        val requete = if (mask == null) {
            byteArrayOf(0x19, sub)
        } else {
            byteArrayOf(0x19, sub, (mask and 0xFF).toByte())
        }
        return executer(
            channel, requete, timeoutMs, phase,
            AttentePositive(0x59, byteArrayOf(sub)),
        )
    }

    /** Exception fermée §5.3 : `21 01` vers `761` (canal imposé). */
    suspend fun legacyRead2101(timeoutMs: Long = 2000, phase: String = "manuel"): UdsReply =
        executer(
            EcuChannel.LEGACY_BMU, byteArrayOf(0x21, 0x01), timeoutMs, phase,
            AttentePositive(0x61, byteArrayOf(0x01)),
        )

    /**
     * Exception fermée §5.3 : `21 02` vers `761`. La condition « `2101`
     * positif d'abord » est imposée par le plan de scan, pas ici.
     */
    suspend fun legacyRead2102(timeoutMs: Long = 2000, phase: String = "manuel"): UdsReply =
        executer(
            EcuChannel.LEGACY_BMU, byteArrayOf(0x21, 0x02), timeoutMs, phase,
            AttentePositive(0x61, byteArrayOf(0x02)),
        )

    private suspend fun executer(
        channel: EcuChannel,
        requete: ByteArray,
        timeoutMs: Long,
        phase: String,
        attente: AttentePositive,
    ): UdsReply {
        // Garde AVANT toute émission, en-têtes AT compris : un refus
        // lève et rien n'est écrit (journalisation par l'appelant).
        RequestGuard.assertAllowed(channel, requete)
        return mutex.withLock {
            programmateur.program(channel, timeoutMs, phase)
            val reponse = session.transact(
                command = requete.enHex(),
                timeoutMs = timeoutMs,
                phase = phase,
                kind = "uds",
                continuesWhile = { bloc ->
                    val charge = chargeChoisie(bloc.lines, channel, filtrerEnAttente = false)
                    charge != null && estNrc(charge) && octet(charge, 2) == 0x78
                },
                enrich = { evenement ->
                    val charge = chargeChoisie(evenement.responseFrames, channel, filtrerEnAttente = true)
                    if (charge == null) {
                        evenement
                    } else {
                        evenement.copy(
                            responseReassembled = charge.enHex(),
                            responseNrc = if (estNrc(charge)) "%02X".format(octet(charge, 2)) else null,
                        )
                    }
                },
            )
            classifier(reponse, channel, attente)
        }
    }

    private fun classifier(reponse: ElmResponse, channel: EcuChannel, attente: AttentePositive): UdsReply {
        if (reponse.status == ElmStatus.NO_DATA) return UdsReply.NoData
        // BUFFER_FULL/TIMEOUT/ELM_ERROR : échec même si des lignes existent.
        if (reponse.status in STATUTS_ECHEC_ELM) return UdsReply.ElmFailure(reponse)
        val charge = chargeChoisie(reponse.lines, channel, filtrerEnAttente = true)
            ?: return UdsReply.ElmFailure(reponse)
        if (charge.isEmpty()) return UdsReply.ElmFailure(reponse)
        if (estNrc(charge)) return UdsReply.Negative(octet(charge, 1), octet(charge, 2))
        if (octet(charge, 0) != attente.premierOctet) return UdsReply.ElmFailure(reponse)
        val longueurEntete = 1 + attente.echo.size
        if (charge.size < longueurEntete) return UdsReply.ElmFailure(reponse)
        for (i in attente.echo.indices) {
            if (charge[1 + i] != attente.echo[i]) return UdsReply.ElmFailure(reponse)
        }
        return UdsReply.Positive(charge.copyOfRange(longueurEntete, charge.size))
    }

    /** En-tête de réponse positive attendue : premier octet + écho exigé après lui. */
    private class AttentePositive(val premierOctet: Int, val echo: ByteArray)
}

/**
 * Client OBD légiféré : mêmes garde et programmation de canal que
 * [UdsClient] (via [ChannelProgrammer]), mais la réponse est éclatée
 * par en-tête CAN — plusieurs ECU peuvent répondre à une requête
 * fonctionnelle (`7DF`).
 */
class ObdClient(private val session: ElmSession) {

    private val programmateur = ChannelProgrammer(session)
    private val mutex = Mutex()

    /**
     * Émet [modePidHex] (hexadécimal pair, ex. `0100`, `03`) sur [channel]
     * et retourne une réponse par en-tête CAN répondant, dans l'ordre
     * d'arrivée. [UdsReply.Positive] porte la charge COMPLÈTE (écho de
     * mode inclus, ex. `41 00 …`). `NO DATA` donne une map vide.
     * Un échec ELM donne [UdsReply.ElmFailure] sur chaque en-tête reçu —
     * ou, sans aucune trame, sur la clé `""` (pour ne jamais le
     * confondre avec `NO DATA`).
     *
     * @throws IllegalArgumentException si [modePidHex] n'est pas de
     *   l'hexadécimal pair non vide.
     * @throws RequestRefusedException si le garde refuse (avant émission).
     */
    suspend fun request(
        channel: EcuChannel,
        modePidHex: String,
        timeoutMs: Long = 2000,
        phase: String = "manuel",
    ): Map<String, UdsReply> {
        val requete = decoderHex(modePidHex)
        RequestGuard.assertAllowed(channel, requete)
        return mutex.withLock {
            programmateur.program(channel, timeoutMs, phase)
            val reponse = session.transact(
                command = requete.enHex(),
                timeoutMs = timeoutMs,
                phase = phase,
                kind = "obd",
            )
            classifierParEntete(reponse)
        }
    }

    private fun classifierParEntete(reponse: ElmResponse): Map<String, UdsReply> {
        if (reponse.status == ElmStatus.NO_DATA) return emptyMap()
        val parEntete = chargesParEntete(reponse.lines, filtrerEnAttente = true)
        if (reponse.status in STATUTS_ECHEC_ELM) {
            if (parEntete.isEmpty()) return mapOf("" to UdsReply.ElmFailure(reponse))
            return parEntete.mapValues { UdsReply.ElmFailure(reponse) }
        }
        val resultat = LinkedHashMap<String, UdsReply>()
        for ((entete, reassemblage) in parEntete) {
            val charge = chargeDe(reassemblage)
            resultat[entete] = when {
                charge == null || charge.isEmpty() -> UdsReply.ElmFailure(reponse)
                estNrc(charge) -> UdsReply.Negative(octet(charge, 1), octet(charge, 2))
                else -> UdsReply.Positive(charge)
            }
        }
        return resultat
    }
}

// ---------------------------------------------------------------------------
// Extraction et classification de charges, commune aux deux clients.
// ---------------------------------------------------------------------------

/** Statuts ELM qui donnent toujours un échec, même avec des lignes reçues. */
private val STATUTS_ECHEC_ELM = setOf(ElmStatus.BUFFER_FULL, ElmStatus.TIMEOUT, ElmStatus.ELM_ERROR)

private fun octet(charge: ByteArray, index: Int): Int = charge[index].toInt() and 0xFF

private fun ByteArray.enHex(): String =
    joinToString("") { "%02X".format(it.toInt() and 0xFF) }

/** Vrai si [charge] est une réponse négative `7F <service> <nrc>`. */
private fun estNrc(charge: ByteArray): Boolean =
    charge.size >= 3 && octet(charge, 0) == 0x7F

/**
 * Vrai si [frame], réassemblée seule, est un avis `7F <service> 78`
 * (responsePending) complet. La session ELM fusionne les lignes de
 * tous les blocs d'une transaction : sans ce filtrage, le
 * réassemblage concaténerait l'avis d'attente à la réponse finale
 * du même en-tête et la rendrait inclassable.
 */
private fun estTrameReponseEnAttente(frame: CanFrame): Boolean {
    val unique = reassemble(listOf(frame)).values.singleOrNull() ?: return false
    val charge = chargeDe(unique) ?: return false
    return charge.size == 3 && octet(charge, 0) == 0x7F && octet(charge, 2) == 0x78
}

/** Parse les lignes CAN et réassemble par en-tête, avis d'attente filtrés sur demande. */
private fun chargesParEntete(lines: List<String>, filtrerEnAttente: Boolean): Map<String, Reassembly> {
    var frames = parseCanLines(lines)
    if (filtrerEnAttente) frames = frames.filterNot { estTrameReponseEnAttente(it) }
    return reassemble(frames)
}

/** Charge d'un réassemblage ; `null` si incomplet (échec pour l'appelant). */
private fun chargeDe(reassemblage: Reassembly?): ByteArray? = when (reassemblage) {
    is Reassembly.Complete -> reassemblage.payload
    is Reassembly.NotIsoTp -> reassemblage.payload
    is Reassembly.Incomplete -> null
    null -> null
}

/** Clé d'en-tête attendue : `rxId` formaté (`%03X` en 11 bits, `%08X` en 29 bits). */
private fun cleEnteteAttendue(channel: EcuChannel): String =
    if (channel.extended29Bit) "%08X".format(channel.rxId) else "%03X".format(channel.rxId)

/**
 * Charge choisie pour [channel] : l'entrée d'en-tête attendu, sinon
 * l'unique entrée si elle est seule ; `null` si absente, ambiguë
 * (0 ou plusieurs entrées) ou incomplète.
 */
private fun chargeChoisie(lines: List<String>, channel: EcuChannel, filtrerEnAttente: Boolean): ByteArray? {
    val parEntete = chargesParEntete(lines, filtrerEnAttente)
    val choisi = parEntete[cleEnteteAttendue(channel)]
        ?: if (parEntete.size == 1) parEntete.values.single() else null
    return chargeDe(choisi)
}

/** Décode une chaîne hexadécimale paire (espaces tolérés) ; lève [IllegalArgumentException] sinon. */
private fun decoderHex(hex: String): ByteArray {
    val propre = hex.filterNot { it.isWhitespace() }.uppercase()
    require(
        propre.isNotEmpty() && propre.length % 2 == 0 &&
            propre.all { it in '0'..'9' || it in 'A'..'F' },
    ) { "modePidHex doit être une chaîne hexadécimale de longueur paire, reçu « $hex »" }
    return ByteArray(propre.length / 2) { propre.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
