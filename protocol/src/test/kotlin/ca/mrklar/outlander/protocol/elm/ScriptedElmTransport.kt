package ca.mrklar.outlander.protocol.elm

import ca.mrklar.outlander.protocol.transport.ElmTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Transport ELM scripté pour les tests : chaque écriture consomme
 * l'échange suivant et enfile ses chunks réponse dans le flux entrant.
 * Une discordance entre l'écriture reçue et [Exchange.expectedWriteContains]
 * n'empêche pas la réponse : elle est consignée dans [mismatches] pour
 * assertion. Sans échange restant, rien n'est enfilé (test de timeout).
 */
class ScriptedElmTransport(exchanges: List<Exchange>) : ElmTransport {

    data class Exchange(
        val expectedWriteContains: String?,
        val responseChunks: List<ByteArray>,
    )

    private val script: List<Exchange> = exchanges.map { e ->
        Exchange(e.expectedWriteContains, e.responseChunks.map { it.copyOf() })
    }
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    private val lock = Any()
    private var prochain = 0
    private var ferme = false
    private val ecritures = mutableListOf<String>()
    private val discordances = mutableListOf<String>()

    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()

    /** Textes écrits, dans l'ordre, décodés en ISO-8859-1. */
    val writtenTexts: List<String>
        get() = synchronized(lock) { ecritures.toList() }

    /** Descriptions des écritures ne contenant pas le motif attendu. */
    val mismatches: List<String>
        get() = synchronized(lock) { discordances.toList() }

    override suspend fun write(data: ByteArray) {
        val texte = String(data, Charsets.ISO_8859_1)
        synchronized(lock) {
            check(!ferme) { "ScriptedElmTransport déjà fermé" }
            ecritures += texte
            if (prochain < script.size) {
                val echange = script[prochain++]
                val attendu = echange.expectedWriteContains
                if (attendu != null && !texte.contains(attendu)) {
                    discordances += "écriture #${ecritures.size}: attendu contenant " +
                        "'$attendu', reçu '$texte'"
                }
                for (chunk in echange.responseChunks) {
                    channel.trySend(chunk.copyOf())
                }
            }
        }
    }

    override suspend fun close() {
        synchronized(lock) {
            if (ferme) return
            ferme = true
            channel.close()
        }
    }
}
