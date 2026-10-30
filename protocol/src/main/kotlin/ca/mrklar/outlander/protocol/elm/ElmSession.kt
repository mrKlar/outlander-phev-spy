package ca.mrklar.outlander.protocol.elm

import ca.mrklar.outlander.protocol.journal.DiscoveryJournal
import ca.mrklar.outlander.protocol.journal.JournalEvent
import ca.mrklar.outlander.protocol.journal.TxChunk
import ca.mrklar.outlander.protocol.transport.ElmTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Matrice de capacités de l'adaptateur, établie par [ElmSession.initialize]. */
data class AdapterCapabilities(
    val ati: String,
    val sti: String?,
    val atrvVolts: Double?,
    val okCommands: Set<String>,
    val deniedCommands: Set<String>,
    val detectedProtocol: String?,
)

/**
 * Session ELM327 : point d'émission unique vers l'adaptateur.
 *
 * Un [Mutex] sérialise toutes les transactions (jamais deux en vol).
 * Une pompe lancée à la construction verse les octets entrants, horodatés
 * par [clockMono], dans un canal illimité que [transact] consomme bloc par
 * bloc (découpage [ElmCodec]). Chaque transaction est appendue au journal
 * avant son retour — y compris timeouts et erreurs de lien.
 *
 * [close] annule la pompe et ferme le transport ; le journal appartient à
 * l'appelant et n'est pas fermé ici.
 */
class ElmSession(
    private val scope: CoroutineScope,
    private val transport: ElmTransport,
    private val journal: DiscoveryJournal,
    private val transportKind: String,
    private val clockMono: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    private val codec = ElmCodec()
    private val mutex = Mutex()
    private val rxChannel = Channel<Pair<Long, ByteArray>>(Channel.UNLIMITED)
    private val blocsPrets = ArrayDeque<String>()

    private val elmState = linkedMapOf<String, String?>(
        "protocol" to null,
        "sh" to null,
        "cra" to null,
        "fcsh" to null,
        "fcsd" to null,
        "fcsm" to null,
        "caf" to null,
        "st_ms" to null,
    )

    @Volatile
    private var adapterAti: String? = null

    @Volatile
    private var adapterSti: String? = null

    @Volatile
    private var adapterStn: Boolean? = null

    @Volatile
    private var atrvConnu: Double? = null

    private val fermeLock = Any()
    private var ferme = false

    private val pompe: Job = scope.launch {
        try {
            transport.incoming.collect { chunk ->
                rxChannel.trySend(clockMono() to chunk.copyOf())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Lien entrant en erreur : le canal se ferme, transact en fera
            // une réponse ELM_ERROR plutôt qu'une exception.
            if (e is Error) throw e
        } finally {
            rxChannel.close()
        }
    }

    /** Instantané de l'état ELM suivi (clés du journal + `fcsm`). */
    fun elmSnapshot(): Map<String, String?> =
        synchronized(elmState) { LinkedHashMap(elmState) }

    /** Annule la pompe et ferme le transport. Idempotent ; ne ferme pas le journal. */
    suspend fun close() {
        synchronized(fermeLock) {
            if (ferme) return
            ferme = true
        }
        pompe.cancel()
        transport.close()
        pompe.join()
    }

    /**
     * Écrit `command + "\r"` et collecte la réponse jusqu'au prompt, dans
     * un budget total de [timeoutMs] compté depuis juste avant l'écriture.
     * Si [continuesWhile] est vrai pour le bloc reçu, le bloc suivant est
     * collecté sans réécrire (10 blocs au plus, même budget) ; la réponse
     * retournée re-classifie le brut concaténé. L'événement journalisé est
     * passé par [enrich] juste avant l'append, qui précède toujours le retour.
     */
    suspend fun transact(
        command: String,
        timeoutMs: Long = 2000,
        phase: String = "manuel",
        kind: String? = null,
        continuesWhile: (ElmResponse) -> Boolean = { false },
        enrich: (JournalEvent) -> JournalEvent = { it },
    ): ElmResponse = mutex.withLock {
        val elmAvant = elmSnapshot()
        val octets = (command + "\r").toByteArray(Charsets.ISO_8859_1)
        val debut = clockMono()
        val txAt = debut
        val rxChunks = mutableListOf<TxChunk>()
        val blocs = mutableListOf<String>()
        var canalFerme = false

        val dansLesTemps = withTimeoutOrNull(timeoutMs) {
            transport.write(octets)
            while (true) {
                val bloc = recevoirBloc(rxChunks)
                if (bloc == null) {
                    canalFerme = true
                    break
                }
                blocs += bloc
                val repBloc = codec.classify(command, bloc, dureeDepuis(debut))
                if (!(blocs.size < 10 && continuesWhile(repBloc))) break
            }
            true
        }

        val reponse = when {
            dansLesTemps == null -> {
                val brut = brutAvecPending(blocs)
                codec.reset()
                blocsPrets.clear()
                codec.classify(command, brut, maxOf(dureeDepuis(debut), timeoutMs))
                    .copy(status = ElmStatus.TIMEOUT)
            }
            canalFerme -> {
                val brut = brutAvecPending(blocs)
                codec.reset()
                blocsPrets.clear()
                codec.classify(command, brut, dureeDepuis(debut))
                    .copy(status = ElmStatus.ELM_ERROR)
            }
            else -> codec.classify(command, blocs.joinToString("\r"), dureeDepuis(debut))
        }

        majEtatApresReponse(command, reponse)
        journal.append(enrich(evenement(command, phase, kind, elmAvant, reponse, debut, txAt, octets, rxChunks)))
        reponse
    }

    /** Prochain bloc complet (prompt reçu), ou null si le canal RX est fermé.
     *  Appelé uniquement sous le [Mutex] de session. */
    private suspend fun recevoirBloc(rxChunks: MutableList<TxChunk>): String? {
        while (blocsPrets.isEmpty()) {
            val entree = rxChannel.receiveCatching().getOrNull() ?: return null
            rxChunks += TxChunk(entree.first, hexBytes(entree.second))
            blocsPrets.addAll(codec.feed(entree.second))
        }
        return blocsPrets.removeFirst()
    }

    private fun dureeDepuis(debut: Long): Long = (clockMono() - debut).coerceAtLeast(0L)

    private fun brutAvecPending(blocs: List<String>): String {
        val pending = codec.pending()
        val parties = if (pending.isEmpty()) blocs else blocs + pending
        return parties.joinToString("\r")
    }

    /** Événement journal de la transaction ; [elmAvant] est l'état en vigueur
     *  pendant l'envoi (instantané pris avant l'écriture). */
    private fun evenement(
        command: String,
        phase: String,
        kind: String?,
        elmAvant: Map<String, String?>,
        reponse: ElmResponse,
        debut: Long,
        txAt: Long,
        octets: ByteArray,
        rxChunks: List<TxChunk>,
    ): JournalEvent {
        val compacte = compacte(command)
        val estAt = compacte.startsWith("AT") || compacte.startsWith("ST")
        val service = if (compacte.length >= 2 && compacte.take(2).all { estHex(it) }) {
            compacte.substring(0, 2).toInt(16)
        } else {
            null
        }
        val did = if (service == 0x22 && compacte.length == 6 && compacte.all { estHex(it) }) {
            compacte.substring(2, 6)
        } else {
            null
        }
        return JournalEvent(
            phase = phase,
            kind = kind ?: if (estAt) "at" else "obd",
            tsWall = "",
            tsMonoMs = debut,
            adapterAti = adapterAti,
            adapterStn = adapterStn,
            transport = transportKind,
            elm = elmAvant,
            atrvVolts = atrvConnu,
            requestBytes = if (estAt) command else compacte,
            requestService = service,
            requestDid = did,
            responseRawText = reponse.rawText,
            responseFrames = reponse.lines,
            responseStatus = when (reponse.status) {
                ElmStatus.POSITIVE -> "positive"
                ElmStatus.NRC -> "nrc"
                ElmStatus.NO_DATA -> "no_data"
                ElmStatus.TIMEOUT -> "timeout"
                ElmStatus.ELM_ERROR -> "elm_error"
                ElmStatus.BUFFER_FULL -> "buffer_full"
            },
            responseNrc = nrcJournal(reponse),
            responseElmTokens = reponse.elmTokens,
            durationMs = reponse.durationMs,
            txChunks = listOf(TxChunk(txAt, hexBytes(octets))),
            rxChunks = rxChunks.toList(),
        )
    }

    private fun nrcJournal(reponse: ElmResponse): String? {
        if (reponse.status != ElmStatus.NRC) return null
        val compacte = compacte(reponse.lines.firstOrNull() ?: return null)
        if (compacte.length < 6 || !compacte.take(6).all { estHex(it) }) return null
        return compacte.substring(4, 6)
    }

    /** Suit l'état ELM et l'identité de l'adaptateur d'après une réponse. */
    private fun majEtatApresReponse(command: String, reponse: ElmResponse) {
        val compacte = compacte(command)
        val ok = reponse.status == ElmStatus.POSITIVE ||
            reponse.elmTokens.any { it.equals("OK", ignoreCase = true) }
        if (ok) {
            fun maj(cle: String, valeur: String) {
                synchronized(elmState) { elmState[cle] = valeur }
            }
            when {
                compacte.startsWith("ATFCSH") -> maj("fcsh", compacte.drop(6))
                compacte.startsWith("ATFCSD") -> maj("fcsd", compacte.drop(6))
                compacte.startsWith("ATFCSM") -> maj("fcsm", compacte.drop(6))
                compacte.startsWith("ATSH") -> maj("sh", compacte.drop(4))
                compacte.startsWith("ATCRA") -> maj("cra", compacte.drop(5))
                compacte.startsWith("ATCAF") -> maj("caf", compacte.drop(5))
                compacte.startsWith("ATSP") -> maj("protocol", compacte.drop(4))
                compacte.startsWith("ATST") ->
                    compacte.drop(4).toIntOrNull(16)?.let { maj("st_ms", (it * 4).toString()) }
            }
        }
        if (compacte == "ATI" && reponse.status == ElmStatus.POSITIVE) {
            val ati = analyserAti(reponse)
            if (ati.isNotBlank()) adapterAti = ati
        }
        if (compacte == "STI" && reponse.status != ElmStatus.TIMEOUT) {
            val sti = extraireSti(reponse)
            adapterSti = sti
            adapterStn = sti != null
        }
        if (compacte == "ATRV") {
            analyserVoltage(reponse)?.let { atrvConnu = it }
        }
    }

    /**
     * Séquence d'initialisation (spec §6.1), phase `init` au journal :
     * purge du flux entrant, `ATZ`, identité (`ATI`, `STI`, `ATRV`),
     * configuration consignée dans la matrice de capacités, `ATSP0`,
     * auto-détection par `0100`, puis `ATDPN`. Un refus (« ? ») est
     * consigné sans interrompre la séquence.
     */
    suspend fun initialize(): AdapterCapabilities {
        purger()
        val okCommands = mutableSetOf<String>()
        val deniedCommands = mutableSetOf<String>()
        fun consigner(commande: String, reponse: ElmResponse) {
            when {
                reponse.status == ElmStatus.POSITIVE ||
                    reponse.elmTokens.any { it.equals("OK", ignoreCase = true) } ->
                    okCommands += commande
                reponse.elmTokens.any { it == "?" } -> deniedCommands += commande
                else -> Unit
            }
        }

        transact("ATZ", timeoutMs = 2500, phase = "init")

        val repAti = transact("ATI", phase = "init")
        val ati = analyserAti(repAti)
        if (ati.isNotBlank()) adapterAti = ati

        val repSti = transact("STI", phase = "init")
        val sti = extraireSti(repSti)
        adapterSti = sti
        adapterStn = sti != null

        val repAtrv = transact("ATRV", phase = "init")
        val atrv = analyserVoltage(repAtrv)
        if (atrv != null) atrvConnu = atrv

        for (commande in listOf(
            "ATE0", "ATL0", "ATS1", "ATH1", "ATCAF1", "ATCFC1", "ATAT0", "ATSTFF", "ATCEA",
        )) {
            consigner(commande, transact(commande, phase = "init"))
        }
        consigner("ATSP0", transact("ATSP0", phase = "init"))
        // La recherche automatique de protocole (ATSP0) d'un clone lent peut
        // prendre plusieurs secondes (timeout observé sur véhicule réel
        // 2026-10-30) : budget large, une seule fois à l'init.
        transact("0100", timeoutMs = 10_000, phase = "init", kind = "obd")
        val repDpn = transact("ATDPN", phase = "init")

        return AdapterCapabilities(
            ati = ati,
            sti = sti,
            atrvVolts = atrv,
            okCommands = okCommands.toSet(),
            deniedCommands = deniedCommands.toSet(),
            detectedProtocol = repDpn.lines.firstOrNull(),
        )
    }

    /** Vide le flux entrant : drain immédiat puis 300 ms d'écoute jetée. */
    private suspend fun purger() {
        mutex.withLock {
            while (rxChannel.tryReceive().isSuccess) {
                // Chunk antérieur à l'init : jeté.
            }
            codec.reset()
            blocsPrets.clear()
            try {
                withTimeoutOrNull(300L) {
                    while (true) {
                        rxChannel.receive()
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                // Lien déjà fermé : rien à purger.
            }
            codec.reset()
            blocsPrets.clear()
        }
    }

    /** Relit la tension adaptateur (`ATRV`) ; null si illisible. */
    suspend fun readAdapterVoltage(): Double? {
        val voltage = analyserVoltage(transact("ATRV"))
        if (voltage != null) atrvConnu = voltage
        return voltage
    }

    /** Envoie `ATSTxx` via [transact] (délai de réponse ELM, hex × 4 ms). */
    suspend fun setResponseTimeoutHex(stHex: String): ElmResponse =
        transact("ATST${stHex.trim().uppercase()}")

    private fun analyserAti(reponse: ElmResponse): String =
        reponse.lines.firstOrNull() ?: reponse.rawText.trim().take(64)

    private fun extraireSti(reponse: ElmResponse): String? =
        if (reponse.elmTokens.any { it == "?" }) null else reponse.lines.firstOrNull()

    private fun analyserVoltage(reponse: ElmResponse): Double? {
        for (ligne in reponse.lines) {
            val motif = MOTIF_VOLTAGE.find(ligne) ?: continue
            return motif.groupValues[1].replace(',', '.').toDoubleOrNull()
        }
        return null
    }

    private companion object {
        val MOTIF_VOLTAGE: Regex =
            Regex("""(\d+(?:[.,]\d+)?)\s*V?""", RegexOption.IGNORE_CASE)

        fun compacte(texte: String): String =
            texte.filterNot { it.isWhitespace() }.uppercase()

        fun estHex(c: Char): Boolean = c in '0'..'9' || c in 'A'..'F'

        fun hexBytes(data: ByteArray): String =
            data.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }
}
