package ca.mrklar.outlander.protocol.transport

/** Propriétés d'une caractéristique GATT, indépendantes de toute API Android. */
enum class BleProperty { NOTIFY, INDICATE, WRITE, WRITE_WITHOUT_RESPONSE, READ }

/** Vue pure JVM d'une caractéristique découverte (UUID texte, propriétés). */
data class BleCharacteristicProfile(
    val uuid: String,
    val properties: Set<BleProperty>,
)

/** Vue pure JVM d'un service GATT découvert. */
data class BleServiceProfile(
    val uuid: String,
    val characteristics: List<BleCharacteristicProfile>,
)

/** Canal série BLE choisi : où écouter (notify) et où écrire. UUID normalisés
 *  en forme 128 bits minuscule. */
data class BleChannel(
    val serviceUuid: String,
    val notifyUuid: String,
    val writeUuid: String,
    val writeWithoutResponse: Boolean,
)

/**
 * Sélection du canal série d'un adaptateur ELM327 BLE à partir du profil GATT
 * découvert. Logique pure JVM, testable sans Android.
 *
 * Priorité 1 : les services série connus, dans l'ordre de [knownServiceUuids]
 * (HM-10 FFE0, 18F0, Nordic UART, ISSC). Priorité 2 : le premier service
 * quelconque offrant une caractéristiques notify/indicate et une
 * caractéristique en écriture (pouvant être la même). Sinon, null.
 */
object BleProfiles {

    private const val BASE_SUFFIXE = "-0000-1000-8000-00805f9b34fb"
    private const val NORDIC_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    private const val NORDIC_WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    private const val NORDIC_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    /** Services série BLE connus, forme 128 bits minuscule, ordre de priorité. */
    val knownServiceUuids: List<String> = listOf(
        "0000ffe0-0000-1000-8000-00805f9b34fb",
        "000018f0-0000-1000-8000-00805f9b34fb",
        NORDIC_SERVICE,
        "49535343-fe7d-4ae5-8fa9-9fafd205e455",
    )

    fun choose(services: List<BleServiceProfile>): BleChannel? {
        val normalises = services.map { service ->
            BleServiceProfile(
                uuid = normaliser(service.uuid),
                characteristics = service.characteristics.map { char ->
                    BleCharacteristicProfile(normaliser(char.uuid), char.properties)
                },
            )
        }

        for (connu in knownServiceUuids) {
            for (service in normalises) {
                if (service.uuid == connu) {
                    canalPour(service)?.let { return it }
                }
            }
        }
        for (service in normalises) {
            canalPour(service)?.let { return it }
        }
        return null
    }

    private fun canalPour(service: BleServiceProfile): BleChannel? {
        val notify = choisirNotify(service) ?: return null
        val write = choisirWrite(service) ?: return null
        return BleChannel(
            serviceUuid = service.uuid,
            notifyUuid = notify.uuid,
            writeUuid = write.uuid,
            writeWithoutResponse = BleProperty.WRITE_WITHOUT_RESPONSE in write.properties,
        )
    }

    private fun choisirNotify(service: BleServiceProfile): BleCharacteristicProfile? {
        val candidates = service.characteristics.filter {
            BleProperty.NOTIFY in it.properties || BleProperty.INDICATE in it.properties
        }
        if (service.uuid == NORDIC_SERVICE) {
            candidates.firstOrNull { it.uuid == NORDIC_NOTIFY }?.let { return it }
        }
        return candidates.firstOrNull { BleProperty.NOTIFY in it.properties }
            ?: candidates.firstOrNull()
    }

    private fun choisirWrite(service: BleServiceProfile): BleCharacteristicProfile? {
        val candidates = service.characteristics.filter {
            BleProperty.WRITE in it.properties || BleProperty.WRITE_WITHOUT_RESPONSE in it.properties
        }
        if (service.uuid == NORDIC_SERVICE) {
            candidates.firstOrNull { it.uuid == NORDIC_WRITE }?.let { return it }
        }
        return candidates.firstOrNull { BleProperty.WRITE_WITHOUT_RESPONSE in it.properties }
            ?: candidates.firstOrNull()
    }

    /** Minuscules + expansion des UUID 16 bits (« ffe0 ») vers la base Bluetooth. */
    private fun normaliser(uuid: String): String {
        val nettoye = uuid.trim().lowercase()
        return if (nettoye.length == 4 && nettoye.all { it in '0'..'9' || it in 'a'..'f' }) {
            "0000$nettoye$BASE_SUFFIXE"
        } else {
            nettoye
        }
    }
}
