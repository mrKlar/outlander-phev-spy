package ca.mrklar.outlander.protocol.isotp

/** Une ligne CAN parsée : en-tête normalisé majuscules ("" si absent) et octets de la ligne. */
data class CanFrame(val headerHex: String, val bytes: ByteArray) {

    // Égalité par contenu : le equals généré comparerait ByteArray par référence.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanFrame) return false
        return headerHex == other.headerHex && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * headerHex.hashCode() + bytes.contentHashCode()
}

/** Résultat du réassemblage d'un groupe de trames partageant le même en-tête. */
sealed interface Reassembly {

    /** Charge complète, PCI ISO-TP (ou octet de longueur) retiré, en-tête applicatif conservé. */
    data class Complete(val payload: ByteArray) : Reassembly {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Complete) return false
            return payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = payload.contentHashCode()
    }

    /** Charge ISO-TP multi-trames incomplète ; comptages en octets de charge, hors PCI. */
    data class Incomplete(val expectedBytes: Int, val receivedBytes: Int) : Reassembly

    /** Charge brute qui ne correspond à aucune présentation ISO-TP reconnue. */
    data class NotIsoTp(val payload: ByteArray) : Reassembly {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NotIsoTp) return false
            return payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = payload.contentHashCode()
    }
}

/**
 * Parse des lignes CAN espacées (ATS1) en trames typées, sans jamais lever d'exception.
 *
 * - `7E8 06 …` / `18DAF1DB 03 …` : premier token de 3 ou 8 hex = en-tête (majuscules).
 * - `0: 62 …` / `12: …` : ligne indexée, en-tête absent (headerHex = "").
 * - Sinon : ligne sans en-tête, tous les tokens sont des octets (headerHex = "").
 * - Un token d'octet qui n'est pas exactement 2 hex invalide la ligne entière.
 */
fun parseCanLines(lines: List<String>): List<CanFrame> {
    val espaces = Regex("\\s+")
    val prefixeIndexe = Regex("""^\d+:\s*(.*)$""")

    fun estHex(caractere: Char): Boolean =
        caractere in '0'..'9' || caractere in 'A'..'F' || caractere in 'a'..'f'

    fun estEntete(token: String): Boolean =
        (token.length == 3 || token.length == 8) && token.all { estHex(it) }

    fun estOctet(token: String): Boolean = token.length == 2 && token.all { estHex(it) }

    val frames = mutableListOf<CanFrame>()
    for (ligneBrute in lines) {
        val ligne = ligneBrute.trim()
        if (ligne.isEmpty()) continue

        val headerHex: String
        val tokensOctets: List<String>
        val indexee = prefixeIndexe.matchEntire(ligne)
        if (indexee != null) {
            val reste = indexee.groupValues[1].trim()
            if (reste.isEmpty()) continue
            headerHex = ""
            tokensOctets = reste.split(espaces)
        } else {
            val tokens = ligne.split(espaces)
            if (estEntete(tokens.first())) {
                headerHex = tokens.first().uppercase()
                tokensOctets = tokens.drop(1)
            } else {
                headerHex = ""
                tokensOctets = tokens
            }
        }

        if (tokensOctets.isEmpty()) continue
        if (tokensOctets.any { !estOctet(it) }) continue
        frames += CanFrame(
            headerHex,
            ByteArray(tokensOctets.size) { tokensOctets[it].toInt(16).toByte() },
        )
    }
    return frames
}

/**
 * Réassemble les trames par en-tête, dans l'ordre d'arrivée (groupes et trames).
 *
 * Règles, dans l'ordre : (1) First Frame PCI 0x1x puis Consecutive Frames 0x2n
 * séquentielles modulo 16 à partir de 1, tronquée à la longueur annoncée ;
 * (2) trame unique Single Frame PCI 0x0L cohérente ; (3) trame unique en
 * présentation longueur (premier octet = nombre d'octets suivants) ;
 * (4) sinon charge brute telle quelle (concaténée si plusieurs trames).
 */
fun reassemble(frames: List<CanFrame>): Map<String, Reassembly> {

    fun concatene(groupe: List<CanFrame>): ByteArray {
        val sortie = ByteArray(groupe.sumOf { it.bytes.size })
        var position = 0
        for (frame in groupe) {
            frame.bytes.copyInto(sortie, position)
            position += frame.bytes.size
        }
        return sortie
    }

    fun groupe(groupe: List<CanFrame>): Reassembly {
        val premiers = groupe.first().bytes
        if (premiers.isEmpty()) return Reassembly.NotIsoTp(concatene(groupe))
        val b0 = premiers[0].toInt() and 0xFF

        // Règle 1 : First Frame.
        if ((b0 and 0xF0) == 0x10) {
            if (premiers.size < 2) {
                return Reassembly.Incomplete((b0 and 0x0F) shl 8, 0)
            }
            val expectedBytes = ((b0 and 0x0F) shl 8) or (premiers[1].toInt() and 0xFF)
            val charge = mutableListOf<Byte>()
            for (i in 2 until premiers.size) charge.add(premiers[i])
            if (charge.size >= expectedBytes) {
                return Reassembly.Complete(ByteArray(expectedBytes) { charge[it] })
            }
            var sequenceAttendue = 1
            for (index in 1 until groupe.size) {
                val cf = groupe[index].bytes
                if (cf.isEmpty()) {
                    return Reassembly.Incomplete(expectedBytes, charge.size)
                }
                val cf0 = cf[0].toInt() and 0xFF
                if ((cf0 and 0xF0) != 0x20 || (cf0 and 0x0F) != sequenceAttendue) {
                    return Reassembly.Incomplete(expectedBytes, charge.size)
                }
                for (i in 1 until cf.size) charge.add(cf[i])
                if (charge.size >= expectedBytes) {
                    return Reassembly.Complete(ByteArray(expectedBytes) { charge[it] })
                }
                sequenceAttendue = (sequenceAttendue + 1) and 0x0F
            }
            return Reassembly.Incomplete(expectedBytes, charge.size)
        }

        // Règles 2 et 3 : trame unique seulement.
        if (groupe.size == 1) {
            if ((b0 and 0xF0) == 0x00 && (b0 and 0x0F) == premiers.size - 1) {
                return Reassembly.Complete(premiers.copyOfRange(1, premiers.size))
            }
            if (b0 == premiers.size - 1) {
                return Reassembly.Complete(premiers.copyOfRange(1, premiers.size))
            }
            return Reassembly.NotIsoTp(premiers.copyOf())
        }

        // Règle 4 : charge brute telle quelle.
        return Reassembly.NotIsoTp(concatene(groupe))
    }

    val groupes = LinkedHashMap<String, MutableList<CanFrame>>()
    for (frame in frames) {
        groupes.getOrPut(frame.headerHex) { mutableListOf() }.add(frame)
    }
    val resultat = LinkedHashMap<String, Reassembly>()
    for ((headerHex, groupeFrames) in groupes) {
        resultat[headerHex] = groupe(groupeFrames)
    }
    return resultat
}
