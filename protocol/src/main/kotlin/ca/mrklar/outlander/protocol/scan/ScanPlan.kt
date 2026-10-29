package ca.mrklar.outlander.protocol.scan

import ca.mrklar.outlander.protocol.elm.AdapterCapabilities
import ca.mrklar.outlander.protocol.elm.ElmResponse
import ca.mrklar.outlander.protocol.elm.ElmSession
import ca.mrklar.outlander.protocol.elm.ElmStatus
import ca.mrklar.outlander.protocol.journal.DiscoveryJournal
import ca.mrklar.outlander.protocol.journal.JournalEvent
import ca.mrklar.outlander.protocol.uds.EcuChannel
import ca.mrklar.outlander.protocol.uds.ObdClient
import ca.mrklar.outlander.protocol.uds.RequestRefusedException
import ca.mrklar.outlander.protocol.uds.UdsClient
import ca.mrklar.outlander.protocol.uds.UdsReply
import ca.mrklar.outlander.protocol.vehicle.AffineDecoder
import ca.mrklar.outlander.protocol.vehicle.KnownSignals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Réglages du plan de scan (spec §5.5, §6, §7). */
data class ScanConfig(
    val pacingMs: Long = 150,
    val identificationTimeoutMs: Long = 1500,
    val sweepTimeoutMs: Long = 600,
    val atrvFloorVolts: Double = 12.0,
    val atrvPollMs: Long = 30_000,
    val maxConsecutiveCanErrors: Int = 3,
)

/** Progression observable d'un scan : phases, répondants, sondes, refus, arrêts. */
sealed interface ScanEvent {
    data class PhaseStarted(val phase: String) : ScanEvent
    data class PhaseFinished(val phase: String) : ScanEvent
    data class EcuResponded(val phase: String, val headerHex: String) : ScanEvent
    data class ProbeResult(val signalId: String, val reply: String, val decoded: Double?) : ScanEvent
    data class Refused(val description: String, val reason: String) : ScanEvent
    data class Stopped(val phase: String, val reason: String) : ScanEvent
    data class Note(val message: String) : ScanEvent
}

/** Bilan d'un balayage de DID (phase 8). */
data class SweepResult(
    val positives: List<Int>,
    val negativesByNrc: Map<Int, List<Int>>,
    val noDataCount: Int,
    val stoppedReason: String?,
)

/**
 * Exécuteur du plan de première session (spec §7.2) : phases légiférées,
 * héritage `2101`, présence 11 bits, cibles 29 bits, identification,
 * sondes Gen4, et balayage borné en méthode séparée. L'initialisation
 * ELM est déjà faite par l'appelant ([ElmSession.initialize]) ; cet
 * exécuteur reçoit les [AdapterCapabilities] et ne la rejoue pas.
 *
 * Toute requête véhicule passe par [UdsClient]/[ObdClient], donc par le
 * garde lecture seule ; un refus est journalisé (événement `event`) et
 * émis en [ScanEvent.Refused], jamais une exception qui tue le scan.
 * Gardes permanents (§5.5) : [ScanConfig.maxConsecutiveCanErrors]
 * `CAN ERROR` consécutifs ou `ATRV` sous le plancher hors READY arrêtent
 * le scan après la phase en cours, cause journalisée et émise.
 *
 * [clockMono] et [delayFn] sont injectables pour les tests ; en
 * production, [delayFn] est un vrai délai et le pacing de 150 ms
 * s'applique avant chaque appel, sauf le tout premier.
 */
class ScanExecutor(
    private val session: ElmSession,
    private val capabilities: AdapterCapabilities,
    private val uds: UdsClient,
    private val obd: ObdClient,
    private val journal: DiscoveryJournal,
    private val config: ScanConfig = ScanConfig(),
    private val clockMono: () -> Long = { System.nanoTime() / 1_000_000 },
    private val delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {

    private var premierAppelFait = false
    private var protocoleCourant: Int? = null // 11 ou 29, null = inconnu

    // §6.2 : ATSP7/ATCP refusés pendant l'init ⇒ phases 29 bits sautées d'emblée.
    private var protocole29Disponible: Boolean? =
        if (capabilities.deniedCommands.any { it in COMMANDES_29_BITS }) false else null

    private var erreursCanConsecutives = 0
    private var arrete = false
    private var raisonArret: String? = null
    private var arretEmis = false
    private var derniereLectureAtrv: Long? = null
    private var vehicleReady = true
    private var phaseCourante = "manuel"

    /** Canaux ayant répondu (presence-11 puis cible-29, ordre de découverte). */
    private val canauxRepondants = LinkedHashSet<EcuChannel>()
    private var cible29Executee = false

    // -----------------------------------------------------------------------
    // Helpers gardés : pacing, refus journalisé, compteur CAN ERROR.
    // -----------------------------------------------------------------------

    private suspend fun pace() {
        if (premierAppelFait) delayFn(config.pacingMs) else premierAppelFait = true
    }

    private suspend fun journalNote(note: String) {
        journal.append(
            JournalEvent(
                phase = phaseCourante,
                kind = "event",
                tsWall = "",
                tsMonoMs = 0L,
                note = note,
            ),
        )
    }

    private fun ElmResponse.estCanError(): Boolean =
        elmTokens.any { it.equals("CAN ERROR", ignoreCase = true) }

    /** Nourrit le compteur §5.5 ; tout résultat non-CAN ERROR le remet à zéro. */
    private fun nourrirCompteurCan(canError: Boolean) {
        if (!canError) {
            erreursCanConsecutives = 0
            return
        }
        erreursCanConsecutives++
        if (erreursCanConsecutives >= config.maxConsecutiveCanErrors && !arrete) {
            arrete = true
            raisonArret = "$erreursCanConsecutives CAN ERROR consécutifs"
        }
    }

    private fun nourrirCompteurCan(reponse: ElmResponse) = nourrirCompteurCan(reponse.estCanError())

    private fun nourrirCompteurCan(reply: UdsReply) =
        nourrirCompteurCan(reply is UdsReply.ElmFailure && reply.response.estCanError())

    private fun nourrirCompteurCan(replies: Map<String, UdsReply>) =
        nourrirCompteurCan(replies.values.any { it is UdsReply.ElmFailure && it.response.estCanError() })

    /**
     * Garde ATRV (§5.5) : lecture forcée en début de phase, sinon au plus
     * une fois par [ScanConfig.atrvPollMs] monotone. Retourne faux (et
     * émet [ScanEvent.Stopped]) si la tension est sous le plancher hors READY.
     */
    private suspend fun gardeAtrv(force: Boolean, emit: suspend (ScanEvent) -> Unit): Boolean {
        if (arrete) return false
        val maintenant = clockMono()
        val derniere = derniereLectureAtrv
        if (!force && derniere != null && maintenant - derniere < config.atrvPollMs) return true
        pace()
        val volts = session.readAdapterVoltage()
        derniereLectureAtrv = clockMono()
        if (!vehicleReady && volts != null && volts < config.atrvFloorVolts) {
            arrete = true
            val raison = "ATRV $volts V < ${config.atrvFloorVolts} V hors READY"
            raisonArret = raison
            journalNote("arrêt de scan: $raison")
            emit(ScanEvent.Stopped(phaseCourante, raison))
            arretEmis = true
            return false
        }
        return true
    }

    /** Appel UDS gardé : null si arrêté, garde ATRV négative ou refus du garde. */
    private suspend fun appelUds(
        description: String,
        emit: suspend (ScanEvent) -> Unit,
        bloc: suspend () -> UdsReply,
    ): UdsReply? {
        if (arrete) return null
        if (!gardeAtrv(force = false, emit = emit)) return null
        pace()
        return try {
            val reply = bloc()
            nourrirCompteurCan(reply)
            reply
        } catch (e: RequestRefusedException) {
            consignerRefus(description, e, emit)
            null
        }
    }

    /** Appel OBD gardé, même contrat que [appelUds]. */
    private suspend fun appelObd(
        description: String,
        emit: suspend (ScanEvent) -> Unit,
        bloc: suspend () -> Map<String, UdsReply>,
    ): Map<String, UdsReply>? {
        if (arrete) return null
        if (!gardeAtrv(force = false, emit = emit)) return null
        pace()
        return try {
            val replies = bloc()
            nourrirCompteurCan(replies)
            replies
        } catch (e: RequestRefusedException) {
            consignerRefus(description, e, emit)
            null
        }
    }

    /** Commande AT via la session (bascule de protocole), pacée et comptée. */
    private suspend fun appelAt(commande: String): ElmResponse? {
        if (arrete) return null
        pace()
        val reponse = session.transact(
            command = commande,
            timeoutMs = config.identificationTimeoutMs,
            phase = phaseCourante,
            kind = "at",
        )
        nourrirCompteurCan(reponse)
        return reponse
    }

    private suspend fun consignerRefus(
        description: String,
        e: RequestRefusedException,
        emit: suspend (ScanEvent) -> Unit,
    ) {
        val raison = e.message ?: "refus sans message"
        journalNote("refus liste blanche: $description: $raison")
        emit(ScanEvent.Refused(description, raison))
    }

    private fun UdsReply.estBufferFull(): Boolean =
        this is UdsReply.ElmFailure && response.status == ElmStatus.BUFFER_FULL

    /**
     * Appel UDS avec retente unique sur `BUFFER_FULL` (§6.5) : note au
     * journal, FC bridée `300A0A`, un seul nouvel essai, restauration
     * `300000` en `finally`. (Le champ `retryOf` reste null en M1 : la
     * session n'expose pas le `seq` du journal.)
     */
    private suspend fun appelUdsAvecRetente(
        description: String,
        emit: suspend (ScanEvent) -> Unit,
        bloc: suspend () -> UdsReply,
    ): UdsReply? {
        val premier = appelUds(description, emit, bloc) ?: return null
        if (!premier.estBufferFull()) return premier
        val note = "BUFFER FULL sur $description: retente unique après ATFCSD300A0A"
        journalNote(note)
        emit(ScanEvent.Note(note))
        try {
            pace()
            nourrirCompteurCan(
                uds.setFlowControlData("300A0A", timeoutMs = config.identificationTimeoutMs, phase = phaseCourante),
            )
            return appelUds(description, emit, bloc)
        } finally {
            pace()
            uds.setFlowControlData("300000", timeoutMs = config.identificationTimeoutMs, phase = phaseCourante)
        }
    }

    private suspend fun lireDid(
        channel: EcuChannel,
        did: Int,
        timeoutMs: Long,
        emit: suspend (ScanEvent) -> Unit,
    ): UdsReply? = appelUdsAvecRetente("22%04X".format(did), emit) {
        uds.readDataByIdentifier(channel, did, timeoutMs = timeoutMs, phase = phaseCourante)
    }

    // -----------------------------------------------------------------------
    // Bascule de protocole (§6.3/§6.4)
    // -----------------------------------------------------------------------

    private fun ElmResponse.refusee(): Boolean = elmTokens.any { it.trim() == "?" }

    /**
     * Assure le protocole 11 bits (`ATSP6`) ou 29 bits (`ATSP7` puis
     * `ATCP18`). Un « ? » de l'adaptateur ne tue jamais la session : en
     * 29 bits, les phases concernées sont sautées avec note journalisée.
     */
    private suspend fun assurerProtocole(
        bits29: Boolean,
        emit: suspend (ScanEvent) -> Unit,
    ): Boolean {
        if (bits29) {
            if (protocoleCourant == 29) return true
            if (protocole29Disponible == false) return false
            val sp7 = appelAt("ATSP7") ?: return false
            if (sp7.refusee()) return marquer29Refuse("ATSP7", emit)
            val cp = appelAt("ATCP18") ?: return false
            if (cp.refusee()) return marquer29Refuse("ATCP18", emit)
            protocoleCourant = 29
            protocole29Disponible = true
            return true
        }
        if (protocoleCourant == 11) return true
        val sp6 = appelAt("ATSP6") ?: return false
        if (sp6.refusee()) {
            val note = "ATSP6 refusée par l'adaptateur: étape 11 bits sautée"
            journalNote(note)
            emit(ScanEvent.Note(note))
            return false
        }
        protocoleCourant = 11
        return true
    }

    private suspend fun marquer29Refuse(commande: String, emit: suspend (ScanEvent) -> Unit): Boolean {
        protocole29Disponible = false
        val note = "$commande refusée par l'adaptateur: phases 29 bits sautées"
        journalNote(note)
        emit(ScanEvent.Note(note))
        return false
    }

    // -----------------------------------------------------------------------
    // runPhases : étapes 2 à 7 de la spec §7.2
    // -----------------------------------------------------------------------

    /**
     * Déroule les phases `legifere-11`, `legifere-29`, `heritage-2101`,
     * `presence-11`, `cible-29`, `identification`, `did-gen4`, chacune
     * entourée de [ScanEvent.PhaseStarted]/[ScanEvent.PhaseFinished],
     * même quand elle est sautée. [vehicleReady] est l'état READY déclaré
     * par l'appelant : hors READY, la garde ATRV arrête le scan.
     */
    fun runPhases(vehicleReady: Boolean = true): Flow<ScanEvent> = flow {
        this@ScanExecutor.vehicleReady = vehicleReady
        val collecteur = this
        val emit: suspend (ScanEvent) -> Unit = { collecteur.emit(it) }
        val phases: List<Pair<String, suspend (suspend (ScanEvent) -> Unit) -> Unit>> = listOf(
            "legifere-11" to { e -> phaseLegiferee(EcuChannel.LEGISLATED_11, bits29 = false, e) },
            "legifere-29" to { e -> phaseLegiferee(EcuChannel.FUNCTIONAL_29, bits29 = true, e) },
            "heritage-2101" to { e -> phaseHeritage(e) },
            "presence-11" to { e -> phasePresence11(e) },
            "cible-29" to { e -> phaseCible29(e) },
            "identification" to { e -> phaseIdentification(e) },
            "did-gen4" to { e -> corpsGen4(e, sonderSiInconnu = false) },
        )
        for ((nom, corps) in phases) {
            if (arrete) break
            phaseCourante = nom
            emit(ScanEvent.PhaseStarted(nom))
            if (gardeAtrv(force = true, emit = emit)) {
                corps(emit)
            }
            emettreArretSiNecessaire(emit)
            emit(ScanEvent.PhaseFinished(nom))
            if (arrete) break
        }
    }

    private suspend fun FlowCollector<ScanEvent>.emettreArretSiNecessaire(
        emit: suspend (ScanEvent) -> Unit,
    ) {
        if (arrete && !arretEmis) {
            val raison = raisonArret ?: "arrêt demandé"
            journalNote("arrêt de scan: $raison")
            emit(ScanEvent.Stopped(phaseCourante, raison))
            arretEmis = true
        }
    }

    /**
     * Phase légiférée (§7.2 étape 2) : `0100` et chaînage des masques,
     * bloc fixe `09xx`/`03`/`07`/`0A`, puis chaque PID Mode 01 déclaré
     * (`015B` seulement si déclaré). Un [ScanEvent.EcuResponded] par
     * en-tête distinct vu dans les réponses `0100`.
     */
    private suspend fun phaseLegiferee(
        channel: EcuChannel,
        bits29: Boolean,
        emit: suspend (ScanEvent) -> Unit,
    ) {
        if (!assurerProtocole(bits29, emit)) return
        val timeout = config.identificationTimeoutMs
        val reponses0100 = appelObd("0100", emit) {
            obd.request(channel, "0100", timeoutMs = timeout, phase = phaseCourante)
        } ?: return
        for ((entete, reply) in reponses0100) {
            if (reply is UdsReply.Positive) {
                emit(ScanEvent.EcuResponded(phaseCourante, entete))
            }
        }

        val declares = sortedSetOf<Int>()
        var bloc = 0x00
        var reponsesBloc: Map<String, UdsReply>? = reponses0100
        while (reponsesBloc != null) {
            var blocVu = false
            for (reply in reponsesBloc.values) {
                if (reply !is UdsReply.Positive) continue
                val masque = extraireMasque(reply.payload, bloc) ?: continue
                blocVu = true
                declares += pidsDuMasque(masque, bloc)
            }
            val blocSuivant = bloc + 0x20
            if (!blocVu || blocSuivant > 0x80 || blocSuivant !in declares) break
            val requete = "01%02X".format(blocSuivant)
            reponsesBloc = appelObd(requete, emit) {
                obd.request(channel, requete, timeoutMs = timeout, phase = phaseCourante)
            }
            bloc = blocSuivant
        }
        if (arrete) return

        for (requete in listOf("0900", "0902", "0904", "0906", "090A", "03", "07", "0A")) {
            if (arrete) return
            appelObd(requete, emit) {
                obd.request(channel, requete, timeoutMs = timeout, phase = phaseCourante)
            }
        }

        // PID Mode 01 : déclarés ∪ {01, 42}, hors blocs de masque déjà demandés.
        val pidsMode01 = (declares + setOf(0x01, 0x42)).filter { it !in BLOCS_MASQUE }.sorted()
        for (pid in pidsMode01) {
            if (arrete) return
            val requete = "01%02X".format(pid)
            appelObd(requete, emit) {
                obd.request(channel, requete, timeoutMs = timeout, phase = phaseCourante)
            }
        }
    }

    /** Masque de 4 octets d'une charge `41 <bloc> <4 octets>` ; null si autre forme. */
    private fun extraireMasque(payload: ByteArray, bloc: Int): ByteArray? {
        if (payload.size < 6) return null
        if ((payload[0].toInt() and 0xFF) != 0x41) return null
        if ((payload[1].toInt() and 0xFF) != bloc) return null
        return payload.copyOfRange(2, 6)
    }

    /** PID déclarés par un masque : bloc+1..bloc+32, bit de poids fort d'abord. */
    private fun pidsDuMasque(masque: ByteArray, bloc: Int): Set<Int> {
        val pids = mutableSetOf<Int>()
        for (i in 0 until 32) {
            val octet = masque[i / 8].toInt() and 0xFF
            if (octet and (0x80 shr (i % 8)) != 0) pids += bloc + 1 + i
        }
        return pids
    }

    /** Étape 3 : un seul `2101` ; `2102` seulement si positif (§5.3). */
    private suspend fun phaseHeritage(emit: suspend (ScanEvent) -> Unit) {
        if (!assurerProtocole(bits29 = false, emit = emit)) return
        val timeout = config.identificationTimeoutMs
        val reply = appelUds("2101", emit) {
            uds.legacyRead2101(timeoutMs = timeout, phase = phaseCourante)
        } ?: return
        if (reply is UdsReply.Positive) {
            appelUds("2102", emit) {
                uds.legacyRead2102(timeoutMs = timeout, phase = phaseCourante)
            }
        }
    }

    /**
     * Étape 4 : paires §6.3 dans l'ordre. `testerPresent` d'abord ; sur
     * [UdsReply.NoData]/[UdsReply.ElmFailure], `diagnosticSessionControl(01)`.
     * Répondant si l'un des deux est positif.
     */
    private suspend fun phasePresence11(emit: suspend (ScanEvent) -> Unit) {
        if (!assurerProtocole(bits29 = false, emit = emit)) return
        val timeout = config.identificationTimeoutMs
        for ((tx, rx) in PAIRES_PRESENCE_11) {
            if (arrete) return
            val channel = EcuChannel.physical11(tx, rx)
            var repond = false
            val tp = appelUds("3E00", emit) {
                uds.testerPresent(channel, timeoutMs = timeout, phase = phaseCourante)
            }
            when (tp) {
                is UdsReply.Positive -> repond = true
                is UdsReply.NoData, is UdsReply.ElmFailure -> {
                    val dsc = appelUds("1001", emit) {
                        uds.diagnosticSessionControl(channel, 1, timeoutMs = timeout, phase = phaseCourante)
                    }
                    if (dsc is UdsReply.Positive) repond = true
                }
                else -> Unit // Negative ou refus : pas un positif, on passe.
            }
            if (repond) {
                canauxRepondants += channel
                emit(ScanEvent.EcuResponded(phaseCourante, "%03X".format(rx)))
            }
        }
    }

    /**
     * Étape 5 : cibles 29 bits `0xDB`, `0xDE`, `0xE5` (§6.4). Physique
     * d'abord (`testerPresent` puis `22F190`) ; répondant si au moins
     * un [UdsReply.Positive] ou [UdsReply.Negative] (un NRC prouve une
     * présence). Si les deux sont [UdsReply.NoData], variante
     * fonctionnelle : `22F190` sur [EcuChannel.FUNCTIONAL_29] via
     * [ObdClient] (le garde autorise `0x22` et les charges reviennent
     * éclatées par en-tête), réponse cherchée sous `18DAF1xx`.
     */
    private suspend fun phaseCible29(emit: suspend (ScanEvent) -> Unit) {
        cible29Executee = true
        if (!assurerProtocole(bits29 = true, emit = emit)) return
        val timeout = config.identificationTimeoutMs
        for ((channel, adresse) in CIBLES_29) {
            if (arrete) return
            val entete = "18DAF1%02X".format(adresse)
            val tp = appelUds("3E00", emit) {
                uds.testerPresent(channel, timeoutMs = timeout, phase = phaseCourante)
            }
            val f190 = if (arrete) null else lireDid(channel, 0xF190, timeout, emit)
            var repond = tp is UdsReply.Positive || tp is UdsReply.Negative ||
                f190 is UdsReply.Positive || f190 is UdsReply.Negative
            if (!repond && tp is UdsReply.NoData && f190 is UdsReply.NoData) {
                // Détour conforme au garde : jamais de transact brut « 22F190 ».
                val fonctionnel = appelObd("22F190", emit) {
                    obd.request(
                        EcuChannel.FUNCTIONAL_29, "22F190",
                        timeoutMs = timeout, phase = phaseCourante,
                    )
                }
                val charge = fonctionnel?.get(entete)
                if (charge is UdsReply.Positive || charge is UdsReply.Negative) repond = true
            }
            if (repond) {
                canauxRepondants += channel
                emit(ScanEvent.EcuResponded(phaseCourante, entete))
            }
        }
    }

    /**
     * Étape 6 : identification de chaque répondant, un DID à la fois
     * (`F186, F190, F187, F189, F191, F18C`, puis le reste de
     * `F180`–`F19F`), puis lectures DTC `1901FF`, `1902FF`, `190A`.
     */
    private suspend fun phaseIdentification(emit: suspend (ScanEvent) -> Unit) {
        val timeout = config.identificationTimeoutMs
        for (channel in canauxRepondants.toList()) {
            if (arrete) return
            if (!assurerProtocole(channel.extended29Bit, emit)) continue
            for (did in DIDS_IDENTIFICATION) {
                if (arrete) return
                lireDid(channel, did, timeout, emit)
            }
            if (arrete) return
            appelUdsAvecRetente("1901FF", emit) {
                uds.readDtc(channel, 0x01, 0xFF, timeoutMs = timeout, phase = phaseCourante)
            }
            if (arrete) return
            appelUdsAvecRetente("1902FF", emit) {
                uds.readDtc(channel, 0x02, 0xFF, timeoutMs = timeout, phase = phaseCourante)
            }
            if (arrete) return
            appelUdsAvecRetente("190A", emit) {
                uds.readDtc(channel, 0x0A, null, timeoutMs = timeout, phase = phaseCourante)
            }
        }
    }

    /**
     * Sondes DID Gen4 communautaires sur `0xDB` (§7.2 étape 7),
     * réappelable hors [runPhases] (l'intervalle entre deux passes est
     * affaire d'orchestration d'app). Appelé isolément, `0xDB` est
     * re-sondé par `testerPresent` ; sans réponse, note et fin — jamais
     * une déclaration d'absence.
     */
    fun gen4Probes(): Flow<ScanEvent> = flow {
        this@ScanExecutor.vehicleReady = true
        val collecteur = this
        val emit: suspend (ScanEvent) -> Unit = { collecteur.emit(it) }
        phaseCourante = "did-gen4"
        emit(ScanEvent.PhaseStarted("did-gen4"))
        if (gardeAtrv(force = true, emit = emit)) {
            corpsGen4(emit, sonderSiInconnu = !cible29Executee)
        }
        emettreArretSiNecessaire(emit)
        emit(ScanEvent.PhaseFinished("did-gen4"))
    }

    private suspend fun corpsGen4(
        emit: suspend (ScanEvent) -> Unit,
        sonderSiInconnu: Boolean,
    ) {
        val timeout = config.identificationTimeoutMs
        if (EcuChannel.BMU_DB !in canauxRepondants) {
            var joined = false
            if (sonderSiInconnu && assurerProtocole(bits29 = true, emit = emit)) {
                val tp = appelUds("3E00", emit) {
                    uds.testerPresent(EcuChannel.BMU_DB, timeoutMs = timeout, phase = phaseCourante)
                }
                if (tp is UdsReply.Positive || tp is UdsReply.Negative) {
                    canauxRepondants += EcuChannel.BMU_DB
                    joined = true
                }
            }
            if (!joined) {
                val note = "cible 0xDB sans réponse: sondes Gen4 sautées (silence non concluant)"
                journalNote(note)
                emit(ScanEvent.Note(note))
                return
            }
        }
        if (!assurerProtocole(bits29 = true, emit = emit)) return
        for (definition in KnownSignals.gen4Community) {
            if (arrete) return
            val did = definition.requestHex.substring(2).toInt(16)
            var reply = lireDid(EcuChannel.BMU_DB, did, timeout, emit)
            if (reply is UdsReply.Negative && reply.nrc == 0x31) {
                // Hypothèse consignée : un essai sur DE puis E5 s'ils ont répondu.
                for (autre in listOf(EcuChannel.ECU_DE, EcuChannel.ECU_E5)) {
                    if (autre !in canauxRepondants || arrete) continue
                    journalNote(
                        "hypothèse: DID %04X essayé sur un autre répondant après NRC 31 sur 0xDB".format(did),
                    )
                    val essai = lireDid(autre, did, timeout, emit)
                    if (essai is UdsReply.Positive) {
                        reply = essai
                        break
                    }
                }
            }
            val decode = if (reply is UdsReply.Positive) {
                AffineDecoder.decode(definition, reply.payload)
            } else {
                null
            }
            emit(ScanEvent.ProbeResult(definition.id, resumeReply(reply), decode))
        }
    }

    /** Résumé court et stable d'une réponse, pour [ScanEvent.ProbeResult]. */
    private fun resumeReply(reply: UdsReply?): String = when (reply) {
        is UdsReply.Positive -> "positive:${reply.payload.size}octets"
        is UdsReply.Negative -> "nrc:%02X".format(reply.nrc)
        UdsReply.NoData -> "no_data"
        is UdsReply.ElmFailure -> "elm_failure:${reply.response.status}"
        null -> "non_emis"
    }

    // -----------------------------------------------------------------------
    // Phase 8 : balayage borné, jamais dans runPhases
    // -----------------------------------------------------------------------

    /**
     * Balayage borné d'une plage de DID sur un canal (§7.2 étape 8) :
     * un DID par requête, `ATST64` posé puis `ATSTFF` restitué en
     * `finally`, reprise après [startAfter], au plus [maxCount] DID.
     * Les gardes §5.5 s'appliquent à chaque DID (ATRV relu au rythme
     * [ScanConfig.atrvPollMs]).
     *
     * Si [retestInExtendedSession], les DID ayant répondu NRC `7F`,
     * `7E` ou `33` sont relus après `10 03` physique, avec keepalive
     * `3E 00` toutes les 2 s (délai réel ; `3E00` maintient S3 comme
     * `3E80`, écart assumé) et retour `10 01` explicite en `finally`.
     * Un DID devenu positif quitte les négatifs pour les positifs.
     * Un [UdsReply.ElmFailure] résiduel compte comme silence
     * ([SweepResult.noDataCount]) : le statut brut reste au journal.
     */
    suspend fun sweepDidRange(
        channel: EcuChannel,
        range: IntRange,
        startAfter: Int? = null,
        maxCount: Int = 500,
        vehicleReady: Boolean = true,
        retestInExtendedSession: Boolean = false,
    ): SweepResult {
        this.vehicleReady = vehicleReady
        phaseCourante = "balayage"
        val emit: suspend (ScanEvent) -> Unit = { } // pas de flux ici : le journal fait foi
        val positives = sortedSetOf<Int>()
        val negatives = LinkedHashMap<Int, MutableList<Int>>()
        var noData = 0

        fun consigner(did: Int, reply: UdsReply?) {
            when (reply) {
                is UdsReply.Positive -> positives += did
                is UdsReply.Negative -> negatives.getOrPut(reply.nrc) { mutableListOf() } += did
                is UdsReply.NoData, is UdsReply.ElmFailure -> noData++
                null -> Unit
            }
        }

        try {
            pace()
            nourrirCompteurCan(session.setResponseTimeoutHex("64"))
            if (!assurerProtocole(channel.extended29Bit, emit)) {
                return SweepResult(emptyList(), emptyMap(), 0, raisonArret ?: "protocole refusé par l'adaptateur")
            }
            if (!gardeAtrv(force = true, emit = emit)) {
                return SweepResult(emptyList(), emptyMap(), 0, raisonArret)
            }
            val dids = range.filter { startAfter == null || it > startAfter }.take(maxCount.coerceAtLeast(0))
            for (did in dids) {
                if (arrete) break
                consigner(did, lireDid(channel, did, config.sweepTimeoutMs, emit))
            }

            if (retestInExtendedSession && !arrete) {
                val aRelire = negatives.entries
                    .filter { it.key in NRC_RETEST_SESSION_ETENDUE }
                    .flatMap { it.value }
                if (aRelire.isNotEmpty()) {
                    val ouverture = appelUds("1003", emit) {
                        uds.diagnosticSessionControl(
                            channel, 3, timeoutMs = config.sweepTimeoutMs, phase = phaseCourante,
                        )
                    }
                    if (ouverture is UdsReply.Positive) {
                        try {
                            coroutineScope {
                                val keepalive = launch {
                                    while (isActive) {
                                        kotlinx.coroutines.delay(2000L)
                                        try {
                                            uds.testerPresent(
                                                channel,
                                                timeoutMs = config.sweepTimeoutMs,
                                                phase = phaseCourante,
                                            )
                                        } catch (e: RequestRefusedException) {
                                            // Keepalive refusé : la session retombera seule (S3).
                                        }
                                    }
                                }
                                try {
                                    for (did in aRelire) {
                                        if (arrete) break
                                        when (val relu = lireDid(channel, did, config.sweepTimeoutMs, emit)) {
                                            is UdsReply.Positive -> {
                                                retirerDesNegatifs(negatives, did)
                                                positives += did
                                            }
                                            is UdsReply.Negative -> {
                                                retirerDesNegatifs(negatives, did)
                                                negatives.getOrPut(relu.nrc) { mutableListOf() } += did
                                            }
                                            else -> Unit // silence en retest : verdict initial conservé
                                        }
                                    }
                                } finally {
                                    keepalive.cancel()
                                }
                            }
                        } finally {
                            appelUds("1001", emit) {
                                uds.diagnosticSessionControl(
                                    channel, 1, timeoutMs = config.sweepTimeoutMs, phase = phaseCourante,
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            pace()
            try {
                session.setResponseTimeoutHex("FF")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Restitution best-effort : le résultat du balayage fait foi.
            }
        }
        return SweepResult(
            positives = positives.toList(),
            negativesByNrc = negatives.filterValues { it.isNotEmpty() }.mapValues { it.value.sorted() },
            noDataCount = noData,
            stoppedReason = if (arrete) raisonArret else null,
        )
    }

    private fun retirerDesNegatifs(negatives: MutableMap<Int, MutableList<Int>>, did: Int) {
        for (liste in negatives.values) liste.remove(did)
    }

    private companion object {
        val COMMANDES_29_BITS = setOf("ATSP7", "ATCP", "ATCP18")
        val BLOCS_MASQUE = setOf(0x00, 0x20, 0x40, 0x60, 0x80)
        val NRC_RETEST_SESSION_ETENDUE = setOf(0x7F, 0x7E, 0x33)

        /** Paires de présence 11 bits (tx, rx), ordre §6.3. */
        val PAIRES_PRESENCE_11: List<Pair<Long, Long>> = buildList {
            for (tx in 0x7E0..0x7E7) add(tx.toLong() to (tx + 8).toLong())
            add(0x797L to 0x79AL)
            add(0x79BL to 0x7BBL)
            add(0x784L to 0x78CL)
            add(0x743L to 0x763L)
            add(0x740L to 0x760L)
        }

        val CIBLES_29: List<Pair<EcuChannel, Int>> = listOf(
            EcuChannel.BMU_DB to 0xDB,
            EcuChannel.ECU_DE to 0xDE,
            EcuChannel.ECU_E5 to 0xE5,
        )

        /** DID d'identification : 6 nommés, puis le reste de F180–F19F en ordre. */
        val DIDS_IDENTIFICATION: List<Int> = run {
            val premiers = listOf(0xF186, 0xF190, 0xF187, 0xF189, 0xF191, 0xF18C)
            premiers + (0xF180..0xF19F).filter { it !in premiers }
        }
    }
}

