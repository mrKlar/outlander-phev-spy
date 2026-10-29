package ca.mrklar.outlander.protocol.uds

/** Verdict du garde lecture seule (spec §5.4). */
sealed interface GuardResult {
    data object Allowed : GuardResult
    data class Refused(val reason: String) : GuardResult
}

/**
 * Levée par [RequestGuard.assertAllowed] quand la requête est refusée :
 * le refus a eu lieu AVANT émission et rien n'a été écrit sur le
 * transport. La journalisation du refus (événement de session) est
 * l'affaire de l'appelant.
 */
class RequestRefusedException(reason: String) : IllegalArgumentException(reason)

/**
 * Liste blanche lecture seule (spec §5.1–§5.3) : unique autorité
 * d'émission des requêtes non-AT. La règle est une liste blanche —
 * tout ce qui n'est pas explicitement ci-dessous est refusé, qu'il
 * figure ou non dans la liste noire nommée (§5.2).
 *
 * - Modes légiférés `01, 02, 03, 07, 09, 0A` (premier octet) : autorisés.
 * - `0x22` ReadDataByIdentifier : exactement 3 octets (un DID 16 bits).
 * - `0x10` DiagnosticSessionControl : 2 octets, sous-fonction `01` ou
 *   `03`, et canal physique seulement (jamais en fonctionnel).
 * - `0x19` ReadDTCInformation : sous-fonction `01`, `02` ou `0A`.
 * - `0x3E` TesterPresent : exactement `3E 00` ou `3E 80`.
 * - `0x21` : uniquement `21 01`/`21 02` sur [EcuChannel.LEGACY_BMU]
 *   (exception fermée §5.3).
 */
object RequestGuard {

    fun check(channel: EcuChannel, request: ByteArray): GuardResult {
        if (request.isEmpty()) return GuardResult.Refused("requête vide")
        val service = request[0].toInt() and 0xFF
        // -1 = sous-fonction absente (requête d'un seul octet).
        val sousFonction = if (request.size >= 2) request[1].toInt() and 0xFF else -1
        return when (service) {
            0x01, 0x02, 0x03, 0x07, 0x09, 0x0A -> GuardResult.Allowed

            0x22 -> if (request.size == 3) {
                GuardResult.Allowed
            } else {
                GuardResult.Refused(
                    "0x22 exige exactement 3 octets (service + DID 16 bits), reçu ${request.size}",
                )
            }

            0x10 -> when {
                request.size != 2 ->
                    GuardResult.Refused("0x10 exige exactement 2 octets, reçu ${request.size}")
                sousFonction !in SOUS_FONCTIONS_10 ->
                    GuardResult.Refused("0x10 sous-fonction 0x%02X hors {01, 03}".format(sousFonction))
                channel.functional ->
                    GuardResult.Refused("0x10 interdit en adressage fonctionnel")
                else -> GuardResult.Allowed
            }

            0x19 -> when {
                request.size < 2 ->
                    GuardResult.Refused("0x19 sans sous-fonction")
                sousFonction !in SOUS_FONCTIONS_19 ->
                    GuardResult.Refused("0x19 sous-fonction 0x%02X hors {01, 02, 0A}".format(sousFonction))
                else -> GuardResult.Allowed
            }

            0x3E -> if (request.size == 2 && sousFonction in VALEURS_3E) {
                GuardResult.Allowed
            } else {
                GuardResult.Refused("0x3E autorise uniquement 3E 00 et 3E 80")
            }

            0x21 -> when {
                channel != EcuChannel.LEGACY_BMU ->
                    GuardResult.Refused(
                        "0x21 autorisé uniquement sur le canal 761→762 (exception fermée §5.3)",
                    )
                !(request.size == 2 && sousFonction in REQUETES_21) ->
                    GuardResult.Refused("0x21 autorise uniquement 21 01 et 21 02")
                else -> GuardResult.Allowed
            }

            else -> GuardResult.Refused("premier octet 0x%02X hors liste blanche".format(service))
        }
    }

    /** Ne fait rien si [check] autorise ; lève [RequestRefusedException] sinon. */
    fun assertAllowed(channel: EcuChannel, request: ByteArray) {
        val verdict = check(channel, request)
        if (verdict is GuardResult.Refused) throw RequestRefusedException(verdict.reason)
    }

    private val SOUS_FONCTIONS_10 = setOf(0x01, 0x03)
    private val SOUS_FONCTIONS_19 = setOf(0x01, 0x02, 0x0A)
    private val VALEURS_3E = setOf(0x00, 0x80)
    private val REQUETES_21 = setOf(0x01, 0x02)
}
