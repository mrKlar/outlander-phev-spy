package ca.mrklar.outlander.protocol.transport

import kotlinx.coroutines.flow.Flow

/** Flux d'octets bruts vers/depuis l'adaptateur. Les chunks sont arbitraires :
 *  aucun alignement sur les lignes ELM n'est jamais supposé (R4). */
interface ElmTransport {
    val incoming: Flow<ByteArray>
    suspend fun write(data: ByteArray)
    suspend fun close()
}

/** Type de lien physique (ou de rejeu) d'un transport ELM327. */
enum class ElmTransportKind { BLE, CLASSIC_SPP, TCP, REPLAY }

/** Adaptateur ELM327 découvert ou appairé. */
data class ElmDevice(
    val id: String,            // adresse MAC ou identifiant stable
    val displayName: String,
    val kind: ElmTransportKind,
) {
    companion object {
        /**
         * Adaptateur Wi-Fi saisi à la main (ex. WiCAN PRO en point
         * d'accès : `192.168.0.10`, port ELM327 `35000`). L'id
         * encode `hôte:port` ; [parseTcpId] fait l'inverse.
         */
        fun tcp(hote: String, port: Int): ElmDevice {
            val h = hote.trim()
            require(h.isNotEmpty() && h.none { it.isWhitespace() || it == ':' }) {
                "hôte TCP invalide : « $hote »"
            }
            require(port in 1..65535) { "port TCP invalide : $port" }
            val id = "$h:$port"
            return ElmDevice(id = id, displayName = "Wi-Fi $id", kind = ElmTransportKind.TCP)
        }

        /** Décode un id `hôte:port` produit par [tcp] ; `null` si invalide. */
        fun parseTcpId(id: String): Pair<String, Int>? {
            val hote = id.substringBeforeLast(':', "")
            val port = id.substringAfterLast(':', "").toIntOrNull() ?: return null
            if (hote.isEmpty() || hote.contains(':') ||
                hote.any { it.isWhitespace() } || port !in 1..65535
            ) {
                return null
            }
            return hote to port
        }
    }
}

/** BLE découvert par scan + Classic déjà appairés au téléphone. */
interface ElmScanner {
    fun scan(): Flow<ElmDevice>
    suspend fun pairedClassicDevices(): List<ElmDevice>
}
