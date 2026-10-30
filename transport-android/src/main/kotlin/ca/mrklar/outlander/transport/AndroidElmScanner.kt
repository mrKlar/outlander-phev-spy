package ca.mrklar.outlander.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import ca.mrklar.outlander.protocol.transport.ElmDevice
import ca.mrklar.outlander.protocol.transport.ElmScanner
import ca.mrklar.outlander.protocol.transport.ElmTransportKind
import com.juul.kable.Scanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Scanner Android : appareils Classic déjà appairés + balayage BLE (Kable).
 *
 * [pairedClassicDevices] ne lève jamais : adaptateur absent/éteint ou
 * permission manquante donnent une liste vide (le contrôle d'erreur est
 * consigné ici, l'UI gère l'explication à l'utilisateur). Chaque annonce
 * BLE vue est conservée dans la [factory] pour la connexion ultérieure.
 */
@SuppressLint("MissingPermission")
class AndroidElmScanner(
    context: Context,
    private val factory: AndroidTransportFactory,
) : ElmScanner {

    private val appContext: Context = context.applicationContext

    override suspend fun pairedClassicDevices(): List<ElmDevice> =
        runCatching {
            val manager = appContext.getSystemService(BluetoothManager::class.java)
                ?: return emptyList()
            val adapter = manager.adapter ?: return emptyList()
            adapter.bondedDevices.orEmpty().map { device ->
                val nom = runCatching { device.name }.getOrNull()
                ElmDevice(
                    id = device.address,
                    displayName = nom ?: device.address,
                    kind = ElmTransportKind.CLASSIC_SPP,
                )
            }
        }.getOrElse { emptyList() }

    override fun scan(): Flow<ElmDevice> = flow {
        val vus = mutableSetOf<String>()
        Scanner {}.advertisements.collect { advertisement ->
            factory.remember(advertisement)
            val id = advertisement.identifier.toString()
            if (vus.add(id)) {
                emit(
                    ElmDevice(
                        id = id,
                        displayName = advertisement.name
                            ?: advertisement.peripheralName
                            ?: id,
                        kind = ElmTransportKind.BLE,
                    ),
                )
            }
        }
    }
}
