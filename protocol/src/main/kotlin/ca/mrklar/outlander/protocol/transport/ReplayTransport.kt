package ca.mrklar.outlander.protocol.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Transport de rejeu : émet [chunks] dans l'ordre, tels quels (copies défensives).
 *
 * Si [gateOnWrite] est vrai (défaut), les chunks ne sont émis qu'après la
 * première écriture — tous, d'un coup — ce qui mime un adaptateur qui ne
 * répond qu'à une commande. Sinon, ils sont déjà en attente dans le canal
 * et émis dès la collecte. [close] termine le flux et est idempotent ;
 * écrire après [close] lève [IllegalStateException].
 */
class ReplayTransport(
    chunks: List<ByteArray>,
    private val gateOnWrite: Boolean = true,
) : ElmTransport {

    private val storedChunks: List<ByteArray> = chunks.map { it.copyOf() }
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    private val lock = Any()
    private val writtenMutable = mutableListOf<ByteArray>()
    private var pumped = false
    private var closed = false

    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()

    /** Écritures reçues, octet pour octet (instantané de copies défensives). */
    val written: List<ByteArray>
        get() = synchronized(lock) { writtenMutable.map { it.copyOf() } }

    init {
        if (!gateOnWrite) {
            synchronized(lock) { pumpLocked() }
        }
    }

    override suspend fun write(data: ByteArray) {
        synchronized(lock) {
            check(!closed) { "ReplayTransport déjà fermé" }
            writtenMutable.add(data.copyOf())
            pumpLocked()
        }
    }

    override suspend fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            channel.close()
        }
    }

    /** Verse tous les chunks dans le canal, une seule fois. Appelé sous [lock]. */
    private fun pumpLocked() {
        if (pumped || closed) return
        pumped = true
        for (chunk in storedChunks) {
            channel.trySend(chunk.copyOf())
        }
    }
}
