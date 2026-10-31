package ca.mrklar.outlander.phevspy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ca.mrklar.outlander.protocol.elm.AdapterCapabilities
import ca.mrklar.outlander.protocol.elm.ElmSession
import ca.mrklar.outlander.protocol.journal.DiscoveryJournal
import ca.mrklar.outlander.protocol.journal.FileJournal
import ca.mrklar.outlander.protocol.journal.JournalEvent
import ca.mrklar.outlander.protocol.scan.ScanEvent
import ca.mrklar.outlander.protocol.scan.ScanExecutor
import ca.mrklar.outlander.protocol.transport.ElmDevice
import ca.mrklar.outlander.protocol.transport.ElmTransportKind
import ca.mrklar.outlander.protocol.uds.EcuChannel
import ca.mrklar.outlander.protocol.uds.ObdClient
import ca.mrklar.outlander.protocol.uds.RequestRefusedException
import ca.mrklar.outlander.protocol.uds.UdsClient
import ca.mrklar.outlander.protocol.uds.UdsReply
import ca.mrklar.outlander.transport.AndroidElmScanner
import ca.mrklar.outlander.transport.AndroidTransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SessionUiState(
    val permissionsGranted: Boolean = false,
    val scanningBle: Boolean = false,
    val devices: List<ElmDevice> = emptyList(),
    val connectingName: String? = null,
    val connectedName: String? = null,
    val capabilities: AdapterCapabilities? = null,
    val atrvVolts: Double? = null,
    val journalPath: String? = null,
    val scanRunning: Boolean = false,
    val lines: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Écran de session (M2) : choix d'adaptateur, connexion ELM gardée,
 * console AT / lectures 22XXXX, sondes Gen4 et scan phases 1-7.
 * Aucune émission hors [ElmSession.transact] (AT/ST) et
 * [UdsClient] / [ScanExecutor] (requêtes véhicule gardées).
 */
class SessionViewModel(application: Application) : AndroidViewModel(application) {

    private val applicationContext: Application = application
    private val factory: AndroidTransportFactory by lazy { AndroidTransportFactory(applicationContext) }
    private val scanner: AndroidElmScanner by lazy { AndroidElmScanner(applicationContext, factory) }

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private var session: ElmSession? = null
    private var uds: UdsClient? = null
    private var obd: ObdClient? = null
    private var journal: DiscoveryJournal? = null
    private var fichierJournal: File? = null
    private var scanJob: Job? = null
    private var balayageJob: Job? = null

    fun refreshPermissions(granted: Boolean) {
        _state.update { it.copy(permissionsGranted = granted) }
    }

    fun listerAppaires() {
        viewModelScope.launch {
            runCatching { scanner.pairedClassicDevices() }
                .onSuccess { appaires -> fusionner(appaires) }
                .onFailure { e -> signalerErreur("Appareils appairés : ${e.message ?: e}") }
        }
    }

    fun basculerBalayageBle(demarrer: Boolean) {
        if (!demarrer) {
            balayageJob?.cancel()
            balayageJob = null
            _state.update { it.copy(scanningBle = false) }
            return
        }
        if (balayageJob?.isActive == true) return
        _state.update { it.copy(scanningBle = true, error = null) }
        balayageJob = viewModelScope.launch {
            try {
                scanner.scan().collect { device -> fusionner(listOf(device)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                signalerErreur("Balayage BLE : ${e.message ?: e}")
            } finally {
                _state.update { it.copy(scanningBle = false) }
            }
        }
    }

    fun connecter(device: ElmDevice) {
        if (_state.value.connectingName != null || session != null) return
        _state.update { it.copy(connectingName = device.displayName, error = null) }
        viewModelScope.launch {
            var nouveauJournal: FileJournal? = null
            var nouvelleSession: ElmSession? = null
            try {
                val horodatage = LocalDateTime.now().format(FORMAT_HORODATAGE)
                val dossier = File(applicationContext.filesDir, "sessions")
                val fichier = File(dossier, "session-$horodatage.jsonl")
                nouveauJournal = FileJournal(fichier, sessionId = horodatage)
                val transport = factory.create(device, viewModelScope)
                val kind = when (device.kind) {
                    ElmTransportKind.BLE -> "ble"
                    ElmTransportKind.CLASSIC_SPP -> "classic"
                    else -> device.kind.name.lowercase(Locale.ROOT)
                }
                nouvelleSession = ElmSession(viewModelScope, transport, nouveauJournal, transportKind = kind)
                val capacites = nouvelleSession.initialize()
                session = nouvelleSession
                journal = nouveauJournal
                fichierJournal = fichier
                uds = UdsClient(nouvelleSession)
                obd = ObdClient(nouvelleSession)
                _state.update {
                    it.copy(
                        connectingName = null,
                        connectedName = device.displayName,
                        capabilities = capacites,
                        atrvVolts = capacites.atrvVolts,
                        journalPath = fichier.absolutePath,
                        error = null,
                    )
                }
                ajouterLignes("Connecté à ${device.displayName} — journal : ${fichier.name}")
            } catch (e: CancellationException) {
                runCatching { nouvelleSession?.close() }
                runCatching { nouveauJournal?.close() }
                _state.update { it.copy(connectingName = null) }
                throw e
            } catch (e: Throwable) {
                runCatching { nouvelleSession?.close() }
                runCatching { nouveauJournal?.close() }
                _state.update { it.copy(connectingName = null) }
                signalerErreur("Connexion à ${device.displayName} : ${e.message ?: e}")
            }
        }
    }

    fun deconnecter() {
        viewModelScope.launch { nettoyerSession() }
    }

    fun lireTension() {
        val sessionCourante = session
        if (sessionCourante == null) {
            ajouterLignes("Non connecté : tension non lue.")
            return
        }
        viewModelScope.launch {
            try {
                val volts = sessionCourante.readAdapterVoltage()
                _state.update { it.copy(atrvVolts = volts) }
                ajouterLignes("Tension adaptateur : ${volts?.let { "%.1f V".format(it) } ?: "illisible"}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                signalerErreur("Lecture de tension : ${e.message ?: e}")
            }
        }
    }

    fun envoyerConsole(saisie: String, cible: EcuChannel) {
        val commande = saisie.trim().uppercase(Locale.ROOT)
        val estAtSt = (commande.startsWith("AT") || commande.startsWith("ST")) &&
            commande.all { it in 'A'..'Z' || it in '0'..'9' || it == ' ' }
        when {
            estAtSt -> {
                val sessionCourante = session
                if (sessionCourante == null) {
                    ajouterLignes("Non connecté : rien n'a été émis.")
                    return
                }
                viewModelScope.launch {
                    try {
                        val reponse = sessionCourante.transact(commande, phase = "manuel")
                        val lignes = buildList {
                            add("> $commande → ${reponse.status} (${reponse.durationMs} ms)")
                            reponse.lines.forEach { add("  $it") }
                            if (reponse.elmTokens.isNotEmpty()) {
                                add("  [${reponse.elmTokens.joinToString(", ")}]")
                            }
                            if (reponse.lines.isEmpty() && reponse.elmTokens.isEmpty()) {
                                add("  (aucune ligne)")
                            }
                        }
                        ajouterLignes(*lignes.toTypedArray())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        ajouterLignes("Erreur console : ${e.message ?: e}")
                    }
                }
            }
            MOTIF_LECTURE_DID.matches(commande) -> {
                val client = uds
                if (client == null) {
                    ajouterLignes("Non connecté : rien n'a été émis.")
                    return
                }
                val did = commande.substring(2).toInt(16)
                viewModelScope.launch {
                    try {
                        val reply = client.readDataByIdentifier(cible, did)
                        ajouterLignes("> $commande → ${resumeReply(reply)}")
                    } catch (e: RequestRefusedException) {
                        ajouterLignes("Refus du garde lecture seule : ${e.message ?: "refus"} — rien n'a été émis.")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        ajouterLignes("Erreur console : ${e.message ?: e}")
                    }
                }
            }
            else -> ajouterLignes(
                "Refusé par la console: seules les commandes AT/ST et les lectures 22XXXX sont permises",
            )
        }
    }

    fun lancerSondesGen4() {
        val executor = construireExecutor() ?: return
        demarrerFlux("Sondes Gen4 (fonctionnel d'abord)") { executor.gen4Probes() }
    }

    fun lancerScan(vehicleReady: Boolean) {
        val executor = construireExecutor() ?: return
        demarrerFlux("Scan phases 1-7") { executor.runPhases(vehicleReady) }
    }

    /**
     * Balayage fonctionnel borné 0100-01FF vers la cible choisie.
     * Même job que le scan (arrêt commun par [arreterScan]) ; le
     * résultat est consigné en lignes sobres : début, chaque DID
     * positif et chaque NRC, bilan avec compte des silences.
     */
    fun lancerBalayageFonctionnel(cible: EcuChannel) {
        val executor = construireExecutor() ?: return
        if (scanJob?.isActive == true) {
            ajouterLignes("Balayage fonctionnel : un scan est déjà en cours.")
            return
        }
        val libelleCible = when (cible) {
            EcuChannel.BMU_DB -> "DB"
            EcuChannel.ECU_DE -> "DE"
            EcuChannel.ECU_E5 -> "E5"
            else -> "%08X".format(cible.rxId)
        }
        _state.update { it.copy(scanRunning = true, error = null) }
        ajouterLignes("Démarrage : Balayage DID 0100-01FF (fonctionnel, cible $libelleCible)")
        scanJob = viewModelScope.launch {
            try {
                val resultat = executor.sweepFunctionalDidRange(cible, 0x0100..0x01FF)
                for (did in resultat.positives) {
                    ajouterLignes("DID %04X : positif".format(did))
                }
                var totalNegatives = 0
                for ((nrc, dids) in resultat.negativesByNrc.toSortedMap()) {
                    totalNegatives += dids.size
                    for (did in dids) {
                        ajouterLignes("DID %04X : NRC %02X".format(did, nrc))
                    }
                }
                ajouterLignes(
                    "Bilan balayage fonctionnel : ${resultat.positives.size} positif(s), " +
                        "$totalNegatives négative(s), ${resultat.noDataCount} silence(s).",
                )
                resultat.stoppedReason?.let { ajouterLignes("Balayage arrêté : $it") }
            } catch (e: CancellationException) {
                ajouterLignes("Balayage fonctionnel interrompu.")
                throw e
            } catch (e: RequestRefusedException) {
                ajouterLignes("Refus du garde lecture seule : ${e.message ?: "refus"}")
            } catch (e: Throwable) {
                signalerErreur("Balayage fonctionnel : ${e.message ?: e}")
            } finally {
                _state.update { it.copy(scanRunning = false) }
            }
        }
    }

    fun arreterScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(scanRunning = false) }
        ajouterLignes("Arrêt demandé.")
    }

    fun consignerVeriteTerrain(socPourcent: String, autonomieKm: String, vehicleReady: Boolean) {
        val soc = socPourcent.trim()
        val autonomie = autonomieKm.trim()
        if (soc.isEmpty() && autonomie.isEmpty()) {
            ajouterLignes("Vérité terrain : aucun champ rempli, rien n'a été consigné.")
            return
        }
        val journalCourant = journal
        if (journalCourant == null) {
            ajouterLignes("Non connecté : vérité terrain non consignée (aucun journal ouvert).")
            return
        }
        val parties = buildList {
            if (soc.isNotEmpty()) add("SOC affiché: $soc%")
            if (autonomie.isNotEmpty()) add("autonomie: $autonomie km")
        }
        viewModelScope.launch {
            try {
                // tsWall/tsMonoMs sont remplacés par le journal à l'append (Journal.kt).
                journalCourant.append(
                    JournalEvent(
                        phase = "verite-terrain",
                        kind = "note",
                        tsWall = "",
                        tsMonoMs = 0L,
                        vehicleState = if (vehicleReady) "READY" else "non-READY",
                        note = parties.joinToString(" ; "),
                    ),
                )
                ajouterLignes("Vérité terrain consignée : ${parties.joinToString(" ; ")}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ajouterLignes("Vérité terrain : échec d'écriture (${e.message ?: e}).")
            }
        }
    }

    fun journalFile(): File? = fichierJournal?.takeIf { it.exists() }

    override fun onCleared() {
        balayageJob?.cancel()
        scanJob?.cancel()
        val sessionAFermer = session
        val journalAFermer = journal
        session = null
        journal = null
        uds = null
        obd = null
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { sessionAFermer?.close() }
            runCatching { journalAFermer?.close() }
        }
        super.onCleared()
    }

    private fun construireExecutor(): ScanExecutor? {
        val s = session
        val c = _state.value.capabilities
        val u = uds
        val o = obd
        val j = journal
        if (s == null || c == null || u == null || o == null || j == null) {
            ajouterLignes("Non connecté : connecte un adaptateur d'abord.")
            return null
        }
        return ScanExecutor(s, c, u, o, j)
    }

    private fun demarrerFlux(libelle: String, flux: () -> Flow<ScanEvent>) {
        if (scanJob?.isActive == true) {
            ajouterLignes("$libelle : un scan est déjà en cours.")
            return
        }
        _state.update { it.copy(scanRunning = true, error = null) }
        ajouterLignes("Démarrage : $libelle")
        scanJob = viewModelScope.launch {
            try {
                flux().collect { evenement -> ajouterLignes(formater(evenement)) }
                ajouterLignes("$libelle terminé.")
            } catch (e: CancellationException) {
                ajouterLignes("$libelle interrompu.")
                throw e
            } catch (e: RequestRefusedException) {
                ajouterLignes("Refus du garde lecture seule : ${e.message ?: "refus"}")
            } catch (e: Throwable) {
                signalerErreur("$libelle : ${e.message ?: e}")
            } finally {
                _state.update { it.copy(scanRunning = false) }
            }
        }
    }

    private suspend fun nettoyerSession() {
        scanJob?.cancel()
        scanJob = null
        balayageJob?.cancel()
        balayageJob = null
        val sessionAFermer = session
        val journalAFermer = journal
        session = null
        journal = null
        uds = null
        obd = null
        runCatching { sessionAFermer?.close() }
        runCatching { journalAFermer?.close() }
        _state.update {
            it.copy(
                scanningBle = false,
                connectingName = null,
                connectedName = null,
                capabilities = null,
                atrvVolts = null,
                scanRunning = false,
            )
        }
        ajouterLignes("Déconnecté.")
    }

    private fun fusionner(nouveaux: List<ElmDevice>) {
        if (nouveaux.isEmpty()) return
        _state.update { etat ->
            val parCle = etat.devices.associateBy { it.kind to it.id }.toMutableMap()
            nouveaux.forEach { parCle.putIfAbsent(it.kind to it.id, it) }
            etat.copy(devices = parCle.values.toList())
        }
    }

    private fun signalerErreur(message: String) {
        _state.update { it.copy(error = message) }
        ajouterLignes("Erreur : $message")
    }

    private fun ajouterLignes(vararg nouvelles: String) {
        if (nouvelles.isEmpty()) return
        val prefixe = "[${LocalTime.now().format(FORMAT_HEURE)}] "
        _state.update { etat ->
            etat.copy(lines = (etat.lines + nouvelles.map { prefixe + it }).takeLast(MAX_LIGNES))
        }
    }

    private fun formater(evenement: ScanEvent): String = when (evenement) {
        is ScanEvent.PhaseStarted -> "▶ Phase ${evenement.phase}"
        is ScanEvent.PhaseFinished -> "■ Phase ${evenement.phase} terminée"
        is ScanEvent.EcuResponded -> "ECU répondu (${evenement.phase}) : ${evenement.headerHex}"
        is ScanEvent.ProbeResult -> buildString {
            append("Sonde ${evenement.signalId} : ${evenement.reply}")
            evenement.decoded?.let { append(" = $it") }
        }
        is ScanEvent.Refused -> "Refusé : ${evenement.description} — ${evenement.reason}"
        is ScanEvent.Stopped -> "Arrêt (${evenement.phase}) : ${evenement.reason}"
        is ScanEvent.Note -> evenement.message
    }

    private companion object {
        const val MAX_LIGNES = 400
        val FORMAT_HORODATAGE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val FORMAT_HEURE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val MOTIF_LECTURE_DID: Regex = Regex("^22[0-9A-F]{4}$")

        fun resumeReply(reply: UdsReply): String = when (reply) {
            is UdsReply.Positive -> {
                val hex = reply.payload.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                val tronque = if (hex.length > 32) hex.take(32) + "…" else hex
                "positive:${reply.payload.size}octets $tronque"
            }
            is UdsReply.Negative -> "nrc:%02X".format(reply.nrc)
            UdsReply.NoData -> "no_data"
            is UdsReply.ElmFailure -> "elm_failure:${reply.response.status}"
        }
    }
}
