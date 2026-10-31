package ca.mrklar.outlander.phevspy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
    var dehors by rememberSaveable { mutableStateOf(false) }
    var stationne by rememberSaveable { mutableStateOf(false) }
    var ready by rememberSaveable { mutableStateOf(false) }
    var soc by rememberSaveable { mutableStateOf("") }
    var autonomie by rememberSaveable { mutableStateOf("") }
    var saisieConsole by rememberSaveable { mutableStateOf("") }
    var cibleCle by rememberSaveable { mutableStateOf("DB") }
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
        LigneCase("Véhicule dehors ou lieu ventilé", dehors) { dehors = it }
        LigneCase("Stationné, frein de parc serré", stationne) { stationne = it }
        LigneCase("État READY", ready) { ready = it }
        Row {
            OutlinedTextField(
                value = soc,
                onValueChange = { soc = it },
                label = { Text("SOC affiché (%)") },
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
            onClick = { vm.consignerVeriteTerrain(soc, autonomie, ready) },
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
            onClick = { vm.lancerScan(ready) },
            enabled = connecte && dehors && stationne && ready && !state.scanRunning,
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("Lancer le scan (phases 1-7)") }
        Button(
            onClick = { vm.lancerBalayageFonctionnel(cible) },
            enabled = connecte && dehors && stationne && ready && !state.scanRunning,
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

@Composable
private fun LigneCase(libelle: String, cochee: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!cochee) },
    ) {
        Checkbox(checked = cochee, onCheckedChange = onChange)
        Text(libelle)
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
            LigneCase("Véhicule dehors ou lieu ventilé", false) {}
            LigneCase("Stationné, frein de parc serré", false) {}
            LigneCase("État READY", false) {}
        }
    }
}
