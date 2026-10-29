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
)

/** BLE découvert par scan + Classic déjà appairés au téléphone. */
interface ElmScanner {
    fun scan(): Flow<ElmDevice>
    suspend fun pairedClassicDevices(): List<ElmDevice>
}
