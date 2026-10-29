package ca.mrklar.outlander.phevspy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ca.mrklar.outlander.protocol.vehicle.KnownSignals
import ca.mrklar.outlander.transport.TransportAndroid

/**
 * Écran squelette M0 : statique, aucune interaction, aucune émission.
 *
 * Les compteurs affichés sont calculés depuis [KnownSignals] du
 * module `:protocol` — preuve que le cœur protocole est réellement
 * lié dans l'APK.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EcranM0()
                }
            }
        }
    }
}

@Composable
private fun EcranM0() {
    Column(modifier = Modifier.padding(24.dp)) {
        Text(
            text = "Outlander PHEV Spy",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "M0 — squelette, cœur protocole lié")
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "DID Gen4 en registre: ${KnownSignals.gen4Community.size} " +
                "(pistes communautaires non validées)",
        )
        Text(text = "Décodeurs anciens (oracle): ${KnownSignals.legacy2101.size}")
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Lecture seule. Ne jamais lancer un scan en roulant.")
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Transports Bluetooth: ${TransportAndroid.ETAT}")
    }
}

@Preview(showBackground = true)
@Composable
private fun EcranM0Apercu() {
    MaterialTheme {
        EcranM0()
    }
}
