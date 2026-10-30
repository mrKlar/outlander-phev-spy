package ca.mrklar.outlander.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import ca.mrklar.outlander.protocol.transport.ElmTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transport ELM327 sur Bluetooth Classic (RFCOMM/SPP).
 *
 * Lecture sur [Dispatchers.IO] en blocs de 256 octets via [callbackFlow] ;
 * écritures synchronisées avec flush immédiat ; [close] idempotent.
 * Une [SecurityException] (permission BLUETOOTH_CONNECT manquante) se
 * propage telle quelle : c'est l'UI qui gère la demande de permission.
 */
@SuppressLint("MissingPermission")
class ClassicSppTransport private constructor(
    private val socket: BluetoothSocket,
) : ElmTransport {

    private val output = socket.outputStream
    private val ecritureLock = Any()
    private val ferme = AtomicBoolean(false)

    override val incoming: Flow<ByteArray> = callbackFlow {
        val tampon = ByteArray(256)
        val entree = socket.inputStream
        try {
            while (currentCoroutineContext().isActive) {
                val lu = withContext(Dispatchers.IO) { entree.read(tampon) }
                if (lu < 0) break
                if (lu > 0) trySend(tampon.copyOf(lu))
            }
        } catch (e: IOException) {
            // Socket fermée (par close() ou par le pair) : fin normale du flux.
        } finally {
            close()
        }
    }

    override suspend fun write(data: ByteArray) {
        check(!ferme.get()) { "ClassicSppTransport déjà fermé" }
        withContext(Dispatchers.IO) {
            synchronized(ecritureLock) {
                output.write(data)
                output.flush()
            }
        }
    }

    override suspend fun close() {
        if (!ferme.compareAndSet(false, true)) return
        try {
            socket.close()
        } catch (e: IOException) {
            // Fermeture best-effort : la socket est déjà morte.
        }
    }

    companion object {
        /** UUID du profil série SPP standard. */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Ouvre une connexion RFCOMM vers l'appareil à [macAddress]. */
        suspend fun connect(context: Context, macAddress: String): ClassicSppTransport {
            val manager = context.getSystemService(BluetoothManager::class.java)
                ?: throw IllegalStateException("BluetoothManager indisponible")
            val adapter = manager.adapter
                ?: throw IllegalStateException("Aucun adaptateur Bluetooth sur cet appareil")
            val device = adapter.getRemoteDevice(macAddress)
            adapter.cancelDiscovery()
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                withContext(Dispatchers.IO) { socket.connect() }
            } catch (e: IOException) {
                try {
                    socket.close()
                } catch (ignore: IOException) {
                    // Échec de connexion : l'exception d'origine fait foi.
                }
                throw e
            }
            return ClassicSppTransport(socket)
        }
    }
}
