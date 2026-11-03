package ca.mrklar.outlander.phevspy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.mrklar.outlander.protocol.transport.ElmTransportKind
import ca.mrklar.outlander.protocol.uds.EcuChannel
import ca.mrklar.outlander.protocol.vehicle.KnownSignals

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EcranSession()
                }
            }
        }
    }
}

private fun permissionsBluetoothAccordees(context: Context): Boolean =
    listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EcranSession(vm: SessionViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var soc by rememberSaveable { mutableStateOf("") }
    var autonomie by rememberSaveable { mutableStateOf("") }
    var saisieConsole by rememberSaveable { mutableStateOf("") }
    var etapeEpb by rememberSaveable { mutableStateOf("Repos, EPB serré") }
    var observationEpb by rememberSaveable { mutableStateOf("") }
    var cibleCle by rememberSaveable { mutableStateOf("DB") }
    var ecranConduite by rememberSaveable { mutableStateOf(false) }
    val cible = when (cibleCle) {
        "DE" -> EcuChannel.ECU_DE
        "E5" -> EcuChannel.ECU_E5
        else -> EcuChannel.BMU_DB
    }
    val connecte = state.connectedName != null
    val lanceurPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.refreshPermissions(permissionsBluetoothAccordees(context)) }

    LaunchedEffect(Unit) { vm.refreshPermissions(permissionsBluetoothAccordees(context)) }

    if (ecranConduite) {
        EcranConduite(vm, state) { ecranConduite = false }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Outlander PHEV Spy", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                state.connectedName != null -> "Connecté à ${state.connectedName}"
                state.connectingName != null -> "Connexion à ${state.connectingName}…"
                else -> "Non connecté"
            },
        )
        Text("Lecture seule. Ne jamais lancer un scan en roulant.", style = MaterialTheme.typography.bodySmall)
        Text(
            "DID Gen4 en registre : ${KnownSignals.gen4Community.size} (piste communautaire)",
            style = MaterialTheme.typography.bodySmall,
        )
        state.journalPath?.let { Text("Journal : $it", style = MaterialTheme.typography.bodySmall) }
        state.error?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        if (!state.permissionsGranted) {
            Text("Sans l'autorisation Bluetooth, aucun balayage ni aucune connexion n'est possible — rien n'est émis.")
            Button(onClick = {
                lanceurPermissions.launch(
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                )
            }) { Text("Autoriser le Bluetooth") }
        } else {
            Text("Bluetooth autorisé.")
        }

        Spacer(Modifier.height(16.dp))
        Text("Adaptateur", style = MaterialTheme.typography.titleMedium)
        Row {
            Button(
                onClick = { vm.listerAppaires() },
                enabled = state.permissionsGranted,
                modifier = Modifier.weight(1f).padding(end = 4.dp),
            ) { Text("Appareils appairés (Classic)") }
            Button(
                onClick = { vm.basculerBalayageBle(!state.scanningBle) },
                enabled = state.permissionsGranted,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            ) { Text(if (state.scanningBle) "Arrêter le balayage" else "Balayer (BLE)") }
        }
        if (state.devices.isEmpty()) {
            Text("Aucun adaptateur listé pour l'instant.", style = MaterialTheme.typography.bodySmall)
        }
        state.devices.forEach { device ->
            OutlinedButton(
                onClick = { vm.connecter(device) },
                enabled = !connecte && state.connectingName == null,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(device.displayName)
                    Text(
                        "${if (device.kind == ElmTransportKind.BLE) "BLE" else "Classic"} · ${device.id}",
                        fontSize = 11.sp,
                    )
                }
            }
        }

        state.capabilities?.let { cap ->
            Spacer(Modifier.height(16.dp))
            Text("Matrice de capacités", style = MaterialTheme.typography.titleMedium)
            Text("ATI : ${cap.ati.ifBlank { "—" }}")
            Text("STN : ${cap.sti ?: "non"}")
            Text("Tension à l'init (ATRV) : ${cap.atrvVolts?.let { "%.1f V".format(it) } ?: "—"}")
            Text("Protocole détecté : ${cap.detectedProtocol ?: "—"}")
            Text("Commandes OK : ${cap.okCommands.size} · refusées : ${cap.deniedCommands.size}")
            if (cap.deniedCommands.isNotEmpty()) {
                Text("Refusées : ${cap.deniedCommands.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (connecte) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.lireTension() }) { Text("Lire la tension adaptateur") }
            Text("Tension adaptateur : ${state.atrvVolts?.let { "%.1f V".format(it) } ?: "—"}")
        }

        Spacer(Modifier.height(16.dp))
        Text("Avant le scan", style = MaterialTheme.typography.titleMedium)
        Row {
            OutlinedTextField(
                value = soc,
                onValueChange = { soc = it },
                label = { Text("Barres cluster (0–16)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f).padding(end = 4.dp),
            )
            OutlinedTextField(
                value = autonomie,
                onValueChange = { autonomie = it },
                label = { Text("Autonomie (km)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }
        Button(
            onClick = { vm.consignerVeriteTerrain(soc, autonomie) },
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("Consigner au journal") }

        Spacer(Modifier.height(16.dp))
        Text("Cible des lectures DID", style = MaterialTheme.typography.titleMedium)
        Row {
            listOf("DB", "DE", "E5").forEach { cle ->
                FilterChip(
                    selected = cibleCle == cle,
                    onClick = { cibleCle = cle },
                    label = { Text(cle) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Console gardée", style = MaterialTheme.typography.titleMedium)
        Text(
            "Commandes AT/ST, ou lecture 22XXXX vers la cible choisie. Toute autre saisie est refusée sans émission.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = saisieConsole,
            onValueChange = { saisieConsole = it },
            label = { Text("Commande (ex. ATI ou 22F190)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.envoyerConsole(saisieConsole, cible) }) { Text("Envoyer") }

        Spacer(Modifier.height(16.dp))
        Text("Actions", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { vm.lancerSondesGen4() }, enabled = connecte && !state.scanRunning) {
            Text("Sondes Gen4 (fonctionnel d'abord)")
        }
        Button(
            onClick = { vm.lancerScan() },
            enabled = actionsSessionActives(connecte, state.scanRunning),
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("Lancer le scan (phases 1-7)") }
        Button(
            onClick = { vm.lancerBalayageFonctionnel(cible) },
            enabled = actionsSessionActives(connecte, state.scanRunning),
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("Balayer DID 0100-01FF (fonctionnel, cible choisie)") }
        if (state.scanRunning) {
            Button(onClick = { vm.arreterScan() }, modifier = Modifier.padding(top = 4.dp)) {
                Text("Arrêter")
            }
        }
        Button(
            onClick = {
                vm.journalFile()?.let { fichier ->
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        fichier,
                    )
                    val partage = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(partage, "Exporter le journal"))
                }
            },
            enabled = state.journalPath != null,
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("Exporter le journal") }
        Button(
            onClick = { vm.deconnecter() },
            enabled = connecte || state.connectingName != null,
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("Déconnecter") }

        Spacer(Modifier.height(16.dp))
        Text("Cellules — suivi en continu", style = MaterialTheme.typography.titleMedium)
        Text(
            "Delta entre cellule max (DID 0178) et min (0179), relus en " +
                "boucle. Conversions candidates (mV directs, pack ×0,01 V), " +
                "cohérentes entre elles aux SOC observés le 2026-10-31 " +
                "mais non étalonnées (statut communautaire non validé). " +
                "À démarrer à l'arrêt; l'écran ne demande aucune " +
                "interaction ensuite.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!state.suiviActif) {
            Button(
                onClick = { vm.lancerSuiviCellules() },
                enabled = actionsSessionActives(connecte, state.scanRunning),
                modifier = Modifier.padding(top = 4.dp),
            ) { Text("Démarrer le suivi cellules") }
        } else {
            Button(onClick = { vm.arreterScan() }, modifier = Modifier.padding(top = 4.dp)) {
                Text("Arrêter le suivi")
            }
        }
        OutlinedButton(
            onClick = { ecranConduite = true },
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("Écran conduite (plein écran)") }
        OutlinedButton(
            onClick = { vm.lancerLectureCellules() },
            enabled = actionsSessionActives(connecte, state.scanRunning),
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("Lire les 96 cellules (~1 min)") }
        val cellulesSession = state.cellulesValeurs.filterNotNull()
        if (cellulesSession.size >= 2) {
            Text(
                "Cellules : ${cellulesSession.size}/96 · min ${cellulesSession.min()} mV · " +
                    "max ${cellulesSession.max()} mV · Δ ${cellulesSession.max() - cellulesSession.min()} mV · " +
                    "maj ${state.cellulesMaj ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
            GraphiqueBarresCellules(
                valeurs = state.cellulesValeurs,
                modifier = Modifier.fillMaxWidth().height(140.dp).padding(top = 4.dp),
            )
        }
        Text(
            state.cellDeltaMv?.let { "Δ $it mV" } ?: "Δ — mV",
            style = MaterialTheme.typography.displayMedium,
        )
        Text(
            "max ${state.cellMaxMv?.let { "$it mV" } ?: "—"} · " +
                "min ${state.cellMinMv?.let { "$it mV" } ?: "—"} · " +
                "pack ${state.packVolts?.let { "%.1f V".format(it) } ?: "—"} · " +
                "SOC brut ${state.socBrut ?: "—"}",
        )
        Text(
            "Session : Δ min ${state.deltaMinSession?.let { "$it mV" } ?: "—"} · " +
                "Δ max ${state.deltaMaxSession?.let { "$it mV" } ?: "—"} · " +
                "${state.suiviCycles} cycles · maj ${state.suiviMaj ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.historiqueCellules.size >= 2) {
            val couleurMax = MaterialTheme.colorScheme.primary
            val couleurMin = MaterialTheme.colorScheme.error
            Canvas(
                modifier = Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp),
            ) {
                val points = state.historiqueCellules
                val haut = points.maxOf { it.first }.toFloat() + 5f
                val bas = points.minOf { it.second }.toFloat() - 5f
                val amplitude = (haut - bas).coerceAtLeast(1f)
                fun x(index: Int): Float =
                    if (points.size < 2) 0f else size.width * index / (points.size - 1)
                fun y(mv: Int): Float = size.height * (haut - mv) / amplitude
                val bande = Path().apply {
                    points.forEachIndexed { i, (mx, _) ->
                        if (i == 0) moveTo(x(i), y(mx)) else lineTo(x(i), y(mx))
                    }
                    points.indices.reversed().forEach { i ->
                        lineTo(x(i), y(points[i].second))
                    }
                    close()
                }
                drawPath(bande, color = couleurMax.copy(alpha = 0.18f))
                for (i in 1 until points.size) {
                    drawLine(
                        couleurMax,
                        Offset(x(i - 1), y(points[i - 1].first)),
                        Offset(x(i), y(points[i].first)),
                        strokeWidth = 3f,
                    )
                    drawLine(
                        couleurMin,
                        Offset(x(i - 1), y(points[i - 1].second)),
                        Offset(x(i), y(points[i].second)),
                        strokeWidth = 3f,
                    )
                }
            }
            Text(
                "Bande max/min (mV) dans le temps — l'écart entre les deux " +
                    "courbes est le delta. Pas encore de tensions par cellule " +
                    "individuelle : aucun DID long trouvé à ce jour.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("EPB — observation du mode service (manuel)", style = MaterialTheme.typography.titleMedium)
        Text(
            "L'app ne commande pas les freins. Tu exécutes toi-même ta séquence " +
                "manuelle connue; l'app capture seulement les états en lecture " +
                "seule. Véhicule sécurisé; ne jamais se placer sous un véhicule " +
                "non supporté.",
        )
        listOf(
            "Repos, EPB serré",
            "Repos, EPB desserré",
            "Icône maintenance affichée (après 10 cycles EPB)",
            "Après ma tentative de rétraction (noter si moteur entendu)",
            "Après ma sortie manuelle",
            "Après 2 applications/libérations normales",
        ).forEach { etape ->
            FilterChip(
                selected = etapeEpb == etape,
                onClick = { etapeEpb = etape },
                label = { Text(etape) },
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        OutlinedTextField(
            value = observationEpb,
            onValueChange = { observationEpb = it },
            label = { Text("Voyants, messages, bruits observés") },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Button(
            onClick = { vm.lancerSnapshotEpb(etapeEpb, observationEpb) },
            enabled = actionsSessionActives(connecte, state.scanRunning),
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("Capturer cet état (lecture seule)") }
        OutlinedButton(
            onClick = { vm.lancerCaptureEpbComplete(etapeEpb, observationEpb) },
            enabled = actionsSessionActives(connecte, state.scanRunning),
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("Capture complète : + balayage DID par ECU (~30 min estimées)") }
        Text(
            "Refaire la capture à chaque étape sans déconnecter; exporter le journal à la fin.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(16.dp))
        Text("Sortie", style = MaterialTheme.typography.titleMedium)
        if (state.lines.isEmpty()) {
            Text("Aucune ligne pour l'instant.", style = MaterialTheme.typography.bodySmall)
        }
        state.lines.forEach { ligne ->
            Text(ligne, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Écran conduite plein écran (demande du propriétaire, façon
 * LeafSpy) : delta en très gros, graphique des lectures en barres
 * min→max, lisible d'un coup d'œil sur support. Aucune interaction
 * requise une fois le suivi démarré à l'arrêt ; l'écran reste allumé.
 *
 * Honnêteté des données : en suivi, chaque barre est la plage
 * min→max d'un cycle (DID 0179/0178, candidats mV), pas une
 * cellule. L'instantané des 96 cellules (DID 0114–0173, séance
 * du 2026-10-31) ne s'affiche que hors suivi.
 */
@Composable
private fun EcranConduite(
    vm: SessionViewModel,
    state: SessionUiState,
    onRetour: () -> Unit,
) {
    val vue = LocalView.current
    DisposableEffect(Unit) {
        vue.keepScreenOn = true
        onDispose { vue.keepScreenOn = false }
    }
    BackHandler { onRetour() }

    fun enV(mv: Int?): String = mv?.let { "%.3f".format(it / 1000.0) } ?: "-.---"

    // Suivi actif : l'écran montre le direct ; l'instantané des 96
    // cellules seulement hors suivi (il figeait l'écran, 2026-10-31).
    val cellulesLues = if (state.suiviActif) emptyList() else state.cellulesValeurs.filterNotNull()
    val deltaCellules = if (cellulesLues.size >= 2) {
        cellulesLues.max() - cellulesLues.min()
    } else {
        null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Pack ${state.packVolts?.let { "%.1f V".format(it) } ?: "—"} · " +
                        "SOC brut ${state.socBrut ?: "—"}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                )
                Text(
                    "odo=${state.odoKm?.let { "$it km" } ?: "—"} · " +
                        "${state.suiviCycles} cycles · maj ${state.suiviMaj ?: "—"}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                )
                Text(
                    if (state.suiviActif) "Suivi en cours (lecture seule)" else "Suivi arrêté",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                )
            }
            Text(
                (deltaCellules ?: state.cellDeltaMv)?.let { "$it mV" } ?: "— mV",
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }

        if (state.suiviActif && state.deltaTransitoire) {
            Text(
                "Transitoire (pack en mouvement) : Δ affiché = dernier cycle stable",
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                color = Color(0xFFE65100),
            )
        }
        val points = state.historiqueCellules
        if (cellulesLues.size >= 2) {
            GraphiqueBarresCellules(
                valeurs = state.cellulesValeurs,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Text(
                "Une barre par cellule (DID 0114-0173, mV) : " +
                    "${cellulesLues.size}/96 lues · min en bleu · maj ${state.cellulesMaj ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (points.size < 2) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "En attente de lectures… Démarre le suivi (à l'arrêt), " +
                        "les barres min→max apparaîtront ici.",
                    fontSize = 18.sp,
                )
            }
        } else {
            val gMax = points.maxOf { it.first }.toFloat()
            val gMin = points.minOf { it.second }.toFloat()
            val centre = (gMax + gMin) / 2f
            val demiFenetre = maxOf(30f, (gMax - gMin) / 2f + 10f)
            val haut = centre + demiFenetre
            val bas = centre - demiFenetre
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(modifier = Modifier.fillMaxHeight()) {
                    Text(enV(haut.toInt()), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Text(enV(centre.toInt()), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Text(enV(bas.toInt()), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                }
                val couleurBarre = Color(0xFFD32F2F)
                val couleurMin = Color(0xFF1565C0)
                val couleurGrille = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    val amplitude = (haut - bas).coerceAtLeast(1f)
                    fun y(mv: Float): Float = size.height * (haut - mv) / amplitude
                    for (f in listOf(0.25f, 0.5f, 0.75f)) {
                        drawLine(
                            couleurGrille,
                            Offset(0f, size.height * f),
                            Offset(size.width, size.height * f),
                            strokeWidth = 1.5f,
                        )
                    }
                    val pasX = size.width / points.size
                    val largeurBarre = (pasX * 0.72f).coerceAtLeast(2.5f)
                    points.forEachIndexed { i, (max, min) ->
                        val x = pasX * (i + 0.5f)
                        // Tige du bas de la fenêtre jusqu'au min (pâle),
                        // segment min→max (plein), repère bleu au min.
                        drawLine(
                            couleurBarre.copy(alpha = 0.35f),
                            Offset(x, size.height),
                            Offset(x, y(min.toFloat())),
                            strokeWidth = largeurBarre,
                        )
                        drawLine(
                            couleurBarre,
                            Offset(x, y(min.toFloat())),
                            Offset(x, y(max.toFloat())),
                            strokeWidth = largeurBarre,
                        )
                        drawLine(
                            couleurMin,
                            Offset(x - largeurBarre / 2f, y(min.toFloat())),
                            Offset(x + largeurBarre / 2f, y(min.toFloat())),
                            strokeWidth = 3.5f,
                        )
                    }
                }
            }
        }

        if (cellulesLues.size >= 2) {
            Text(
                "min ${enV(cellulesLues.min())} · moy ${enV(cellulesLues.average().toInt())} · " +
                    "max ${enV(cellulesLues.max())} · Δ ${deltaCellules} mV",
                fontFamily = FontFamily.Monospace,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Somme des cellules lues : %.1f V (à comparer au pack)".format(
                    cellulesLues.sum() / 1000.0,
                ),
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
            )
        } else {
            Text(
                "min ${enV(state.cellMinMv)} · max ${enV(state.cellMaxMv)} · " +
                    "Δ ${state.cellDeltaMv?.let { "$it mV" } ?: "—"}",
                fontFamily = FontFamily.Monospace,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Δ session : min ${state.deltaMinSession?.let { "$it mV" } ?: "—"} · " +
                    "max ${state.deltaMaxSession?.let { "$it mV" } ?: "—"}",
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
            )
            Text(
                "Barres = plage min→max par lecture (DID 0178/0179, mV " +
                    "candidats). Lecture des 96 cellules : bouton ci-dessous.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val connecte = state.connectedName != null
        Row(modifier = Modifier.padding(top = 8.dp)) {
            if (!state.scanRunning) {
                Button(
                    onClick = { vm.lancerSuiviCellules() },
                    enabled = actionsSessionActives(connecte, state.scanRunning),
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                ) { Text("Démarrer le suivi", fontSize = 18.sp) }
            } else {
                Button(
                    onClick = { vm.arreterScan() },
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                ) { Text("Arrêter", fontSize = 18.sp) }
            }
            OutlinedButton(
                onClick = onRetour,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            ) { Text("Retour", fontSize = 18.sp) }
        }
        OutlinedButton(
            onClick = { vm.lancerLectureCellules() },
            enabled = actionsSessionActives(connecte, state.scanRunning),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) { Text("Lire les 96 cellules (~1 min)", fontSize = 18.sp) }
    }
}

/**
 * Barres par cellule (façon LeafSpy) : 96 positions DID 0114-0173,
 * fenêtre d'au moins 60 mV centrée sur les valeurs lues, barre de la
 * plus basse en bleu, manquantes laissées vides. Axe en volts à gauche.
 */
@Composable
private fun GraphiqueBarresCellules(valeurs: List<Int?>, modifier: Modifier = Modifier) {
    val lues = valeurs.filterNotNull()
    if (lues.size < 2) return
    fun enV(mv: Int): String = "%.3f".format(mv / 1000.0)
    val gMax = lues.max().toFloat()
    val gMin = lues.min().toFloat()
    val centre = (gMax + gMin) / 2f
    val demiFenetre = maxOf(30f, (gMax - gMin) / 2f + 5f)
    val haut = centre + demiFenetre
    val bas = centre - demiFenetre
    Row(modifier = modifier) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Text(enV(haut.toInt()), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(enV(centre.toInt()), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(enV(bas.toInt()), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
        val couleurBarre = Color(0xFFD32F2F)
        val couleurMin = Color(0xFF1565C0)
        val couleurGrille = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val amplitude = (haut - bas).coerceAtLeast(1f)
            fun y(mv: Float): Float = size.height * (haut - mv) / amplitude
            for (f in listOf(0.25f, 0.5f, 0.75f)) {
                drawLine(
                    couleurGrille,
                    Offset(0f, size.height * f),
                    Offset(size.width, size.height * f),
                    strokeWidth = 1.5f,
                )
            }
            val pasX = size.width / 96f
            val largeurBarre = (pasX * 0.75f).coerceAtLeast(2f)
            valeurs.forEachIndexed { i, mv ->
                if (mv == null) return@forEachIndexed
                val x = pasX * (i + 0.5f)
                drawLine(
                    if (mv == lues.min()) couleurMin else couleurBarre,
                    Offset(x, size.height),
                    Offset(x, y(mv.toFloat())),
                    strokeWidth = largeurBarre,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EcranSessionApercu() {
    MaterialTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Outlander PHEV Spy", style = MaterialTheme.typography.headlineMedium)
            Text("Non connecté")
            Text("Lecture seule. Ne jamais lancer un scan en roulant.")
        }
    }
}
