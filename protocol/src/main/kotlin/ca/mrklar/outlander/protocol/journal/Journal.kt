package ca.mrklar.outlander.protocol.journal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Frontière de chunk brut (réutilisé RX et TX) : octets en hexadécimal
 *  reçus/émis à l'instant monotone [atMonoMs]. */
data class TxChunk(val atMonoMs: Long, val hex: String)

/** Une ligne du journal de découverte (spec §9.1) avant horodatage.
 *
 *  [tsWall]/[tsMonoMs] sont présents pour porter les valeurs finales,
 *  mais c'est le journal qui horodate : à l'`append`, il les remplace
 *  par ses propres horloges et attribue le `seq`. L'appelant peut donc
 *  y mettre n'importe quoi ; aucune ligne ne peut être désordonnée. */
data class JournalEvent(
    val phase: String,
    val kind: String, // at | uds | obd | event | note
    val tsWall: String,
    val tsMonoMs: Long,
    val adapterAti: String? = null,
    val adapterStn: Boolean? = null,
    val transport: String? = null,
    // Clés attendues : protocol, sh, cra, fcsh, fcsd, caf, st_ms (valeurs en String).
    val elm: Map<String, String?> = emptyMap(),
    val vehicleState: String? = null,
    val atrvVolts: Double? = null,
    val requestTxId: String? = null,
    val requestBytes: String? = null,
    val requestService: Int? = null,
    val requestDid: String? = null,
    val responseRawText: String? = null,
    val responseFrames: List<String> = emptyList(),
    val responseReassembled: String? = null,
    val responseStatus: String? = null,
    val responseNrc: String? = null,
    val responseElmTokens: List<String> = emptyList(),
    val durationMs: Long? = null,
    val retryOf: Int? = null,
    val txChunks: List<TxChunk> = emptyList(),
    val rxChunks: List<TxChunk> = emptyList(),
    val note: String? = null,
)

/** Journal de découverte JSONL v1 (spec §9). */
interface DiscoveryJournal {
    /** Horodate (horloges du journal), numérote et consigne
     *  l'événement. Ne retourne qu'une fois la ligne consignée. */
    suspend fun append(event: JournalEvent)

    /** Ferme le journal ; idempotent. Tout `append` ultérieur lève
     *  [IllegalStateException]. */
    suspend fun close()
}

private fun horlogeMuraleSysteme(): String =
    OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

private fun horlogeMonoSysteme(): Long = System.nanoTime() / 1_000_000

/** Hex normalisé pour le journal : majuscules, espaces retirés. */
private fun normaliseHex(hex: String?): String? =
    hex?.filterNot { it.isWhitespace() }?.uppercase()

/** Clés de l'objet `elm`, dans l'ordre du schéma ; toutes présentes. */
private val CLES_ELM = listOf("protocol", "sh", "cra", "fcsh", "fcsd", "caf", "st_ms")

/** Sérialise une ligne JSONL v1, clés dans l'ordre exact de la spec §9.1.
 *  [event] doit déjà porter les horodatages du journal. */
internal fun renderJournalLine(sessionId: String, seq: Long, event: JournalEvent): String {
    val request: Any? = if (event.requestBytes == null) {
        null
    } else {
        JsonWriter.obj(
            "tx_id" to normaliseHex(event.requestTxId),
            "bytes" to normaliseHex(event.requestBytes),
            "service" to event.requestService?.let { "%02X".format(it) },
            "did" to normaliseHex(event.requestDid),
        )
    }

    val response: Any? =
        if (event.responseStatus == null && event.responseRawText == null) {
            null
        } else {
            JsonWriter.obj(
                "raw_text" to event.responseRawText,
                "frames" to event.responseFrames,
                "reassembled" to normaliseHex(event.responseReassembled),
                // Statut en minuscules tel que fourni (positive, no_data…).
                "status" to event.responseStatus,
                "nrc" to normaliseHex(event.responseNrc),
                "elm_tokens" to event.responseElmTokens,
            )
        }

    fun chunks(chunks: List<TxChunk>): JsonRaw = JsonWriter.arr(
        chunks.map { c ->
            JsonWriter.obj(
                "at_mono_ms" to c.atMonoMs,
                "hex" to normaliseHex(c.hex),
            )
        },
    )

    val fields = mutableListOf<Pair<String, Any?>>(
        "schema" to 1,
        "session_id" to sessionId,
        "seq" to seq,
        "ts_wall" to event.tsWall,
        "ts_mono_ms" to event.tsMonoMs,
        "phase" to event.phase,
        "kind" to event.kind,
        "adapter" to JsonWriter.obj(
            "ati" to event.adapterAti,
            "stn" to event.adapterStn,
            "transport" to event.transport,
        ),
        "elm" to JsonWriter.obj(CLES_ELM.map { cle -> cle to event.elm[cle] }),
        "vehicle" to JsonWriter.obj(
            "state" to event.vehicleState,
            "atrv_v" to event.atrvVolts,
        ),
        "request" to request,
        "response" to response,
        "duration_ms" to event.durationMs,
        "retry_of" to event.retryOf,
        "tx_chunks" to chunks(event.txChunks),
        "rx_chunks" to chunks(event.rxChunks),
    )
    if (event.note != null) {
        fields += "note" to event.note
    }
    return JsonWriter.obj(fields).text
}

/** Journal en mémoire (tests et rejeu) : conserve événements
 *  horodatés et lignes rendues. Horloges injectables, défauts système. */
class InMemoryJournal(
    val sessionId: String = "in-memory",
    private val clockWall: () -> String = { horlogeMuraleSysteme() },
    private val clockMono: () -> Long = { horlogeMonoSysteme() },
) : DiscoveryJournal {

    private val mutex = Mutex()
    private val garde = Any()
    private val evenements = mutableListOf<JournalEvent>()
    private val lignes = mutableListOf<String>()
    private var prochainSeq = 0L
    private var ferme = false

    /** Instantané des événements consignés, horodatés par le journal. */
    val events: List<JournalEvent>
        get() = synchronized(garde) { evenements.toList() }

    /** Lignes JSONL rendues à l'`append`, dans l'ordre des `seq`. */
    fun renderedLines(): List<String> = synchronized(garde) { lignes.toList() }

    override suspend fun append(event: JournalEvent) {
        mutex.withLock {
            check(!ferme) { "append sur journal fermé" }
            val horodate = event.copy(tsWall = clockWall(), tsMonoMs = clockMono())
            val ligne = renderJournalLine(sessionId, prochainSeq, horodate)
            synchronized(garde) {
                evenements += horodate
                lignes += ligne
            }
            prochainSeq++
        }
    }

    override suspend fun close() {
        mutex.withLock { ferme = true }
    }
}

/** Journal sur fichier : JSONL UTF-8 en append, flush après chaque
 *  ligne (spec §9.2 — une coupure ne perd que la transaction en cours).
 *  Le dossier parent est créé au besoin. Appends sérialisés par [Mutex]. */
class FileJournal(
    file: File,
    val sessionId: String,
    private val clockWall: () -> String = { horlogeMuraleSysteme() },
    private val clockMono: () -> Long = { horlogeMonoSysteme() },
) : DiscoveryJournal {

    private val mutex = Mutex()
    private var prochainSeq = 0L
    private var ferme = false

    private val writer: BufferedWriter

    init {
        file.parentFile?.mkdirs()
        writer = BufferedWriter(
            OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8),
        )
    }

    override suspend fun append(event: JournalEvent) {
        mutex.withLock {
            check(!ferme) { "append sur journal fermé" }
            val horodate = event.copy(tsWall = clockWall(), tsMonoMs = clockMono())
            writer.write(renderJournalLine(sessionId, prochainSeq, horodate))
            writer.write("\n")
            writer.flush()
            prochainSeq++
        }
    }

    override suspend fun close() {
        mutex.withLock {
            if (!ferme) {
                ferme = true
                writer.close()
            }
        }
    }
}
