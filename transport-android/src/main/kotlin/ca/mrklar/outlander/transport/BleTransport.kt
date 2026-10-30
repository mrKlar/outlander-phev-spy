@file:OptIn(ExperimentalUuidApi::class)

package ca.mrklar.outlander.transport

import ca.mrklar.outlander.protocol.transport.BleChannel
import ca.mrklar.outlander.protocol.transport.BleCharacteristicProfile
import ca.mrklar.outlander.protocol.transport.BleProfiles
import ca.mrklar.outlander.protocol.transport.BleProperty
import ca.mrklar.outlander.protocol.transport.BleServiceProfile
import ca.mrklar.outlander.protocol.transport.ElmTransport
import com.juul.kable.Advertisement
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Characteristic
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import com.juul.kable.indicate
import com.juul.kable.notify
import com.juul.kable.read
import com.juul.kable.write
import com.juul.kable.writeWithoutResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.ExperimentalUuidApi

/**
 * Transport ELM327 sur BLE via Kable.
 *
 * API Kable 0.45 réellement utilisée (voir rapport M2, tâche A) :
 * - `Peripheral(advertisement) {}` (fonction fabrique, package `com.juul.kable`) ;
 * - `peripheral.connect()` suspend jusqu'à l'état connecté, découverte des
 *   services incluse — il n'y a pas de `discoverServices()` séparé : le
 *   profil découvert se lit dans `peripheral.services` (`StateFlow`) ;
 * - notifications par `peripheral.observe(characteristic)` (flux froid) ;
 * - écritures par `peripheral.write(characteristic, data, WriteType)`,
 *   découpées selon `maximumWriteValueLengthForType` (= MTU − 3) ;
 * - MTU demandé à 247 via `AndroidPeripheral.requestMtu` (best-effort).
 *
 * Aucun framing métier ici : les chunks BLE partent et arrivent tels quels,
 * le marqueur `>` de l'ELM fait foi côté `:protocol`.
 */
class BleTransport private constructor(
    private val peripheral: Peripheral,
    private val channel: BleChannel,
    private val notifyCharacteristic: Characteristic,
    private val writeCharacteristic: Characteristic,
) : ElmTransport {

    private val ferme = AtomicBoolean(false)

    private val writeType: WriteType =
        if (channel.writeWithoutResponse) WriteType.WithoutResponse else WriteType.WithResponse

    /** Flux froid : l'abonnement aux notifications démarre à la collecte. */
    override val incoming: Flow<ByteArray> = peripheral.observe(notifyCharacteristic)

    override suspend fun write(data: ByteArray) {
        check(!ferme.get()) { "BleTransport déjà fermé" }
        if (data.isEmpty()) return
        val tailleChunk = runCatching {
            peripheral.maximumWriteValueLengthForType(writeType)
        }.getOrDefault(20).coerceAtLeast(1)
        var offset = 0
        while (offset < data.size) {
            val fin = minOf(offset + tailleChunk, data.size)
            peripheral.write(writeCharacteristic, data.copyOfRange(offset, fin), writeType)
            offset = fin
        }
    }

    override suspend fun close() {
        if (!ferme.compareAndSet(false, true)) return
        runCatching { peripheral.disconnect() }
        runCatching { peripheral.close() }
    }

    companion object {
        /**
         * Connecte le périphérique annoncé par [advertisement], choisit le
         * canal série via [BleProfiles] et retourne le transport prêt.
         * Le [scope] de l'appelant borne l'attente de connexion (annulation).
         *
         * @throws IllegalStateException si aucun canal BLE compatible.
         */
        suspend fun connect(advertisement: Advertisement, scope: CoroutineScope): BleTransport {
            val peripheral = Peripheral(advertisement) {}
            try {
                peripheral.connect()
                val discovered = peripheral.services.first { it != null }.orEmpty()

                val profils = discovered.map { service ->
                    BleServiceProfile(
                        uuid = service.serviceUuid.toString(),
                        characteristics = service.characteristics.map { char ->
                            BleCharacteristicProfile(
                                uuid = char.characteristicUuid.toString(),
                                properties = char.toBleProperties(),
                            )
                        },
                    )
                }
                val channel = BleProfiles.choose(profils)
                    ?: throw IllegalStateException(
                        "aucun canal BLE compatible: services=${profils.map { it.uuid }}",
                    )

                val serviceChoisi = discovered.first {
                    it.serviceUuid.toString() == channel.serviceUuid
                }
                val notifyChar = serviceChoisi.characteristics.first {
                    it.characteristicUuid.toString() == channel.notifyUuid
                }
                val writeChar = serviceChoisi.characteristics.first {
                    it.characteristicUuid.toString() == channel.writeUuid
                }

                // MTU élargi si accepté ; un échec laisse le MTU par défaut (23).
                (peripheral as? AndroidPeripheral)?.let { androidPeripheral ->
                    runCatching { androidPeripheral.requestMtu(247) }
                }

                return BleTransport(peripheral, channel, notifyChar, writeChar)
            } catch (e: Exception) {
                runCatching { peripheral.disconnect() }
                runCatching { peripheral.close() }
                throw e
            }
        }

        private fun DiscoveredCharacteristic.toBleProperties(): Set<BleProperty> =
            buildSet {
                if (properties.notify) add(BleProperty.NOTIFY)
                if (properties.indicate) add(BleProperty.INDICATE)
                if (properties.write) add(BleProperty.WRITE)
                if (properties.writeWithoutResponse) add(BleProperty.WRITE_WITHOUT_RESPONSE)
                if (properties.read) add(BleProperty.READ)
            }
    }
}
