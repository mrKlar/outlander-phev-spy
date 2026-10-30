package ca.mrklar.outlander.protocol.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BleProfilesTest {

    private fun char(uuid: String, vararg props: BleProperty) =
        BleCharacteristicProfile(uuid, props.toSet())

    private fun service(uuid: String, vararg chars: BleCharacteristicProfile) =
        BleServiceProfile(uuid, chars.toList())

    // (1) Le couple HM-10 classique FFE0/FFE1 est choisi.
    @Test
    fun ffe0Ffe1Choisi() {
        val canal = BleProfiles.choose(
            listOf(
                service(
                    "0000ffe0-0000-1000-8000-00805f9b34fb",
                    char(
                        "0000ffe1-0000-1000-8000-00805f9b34fb",
                        BleProperty.NOTIFY,
                        BleProperty.WRITE,
                        BleProperty.WRITE_WITHOUT_RESPONSE,
                    ),
                ),
            ),
        )

        assertNotNull(canal)
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", canal.serviceUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", canal.notifyUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", canal.writeUuid)
        assertTrue(canal.writeWithoutResponse)
    }

    // (2) Nordic UART : notify = ...0003, écriture = ...0002.
    @Test
    fun uartNordiqueChoisiAvecBonsRoles() {
        val canal = BleProfiles.choose(
            listOf(
                service(
                    "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
                    char("6e400002-b5a3-f393-e0a9-e50e24dcca9e", BleProperty.WRITE_WITHOUT_RESPONSE),
                    char("6e400003-b5a3-f393-e0a9-e50e24dcca9e", BleProperty.NOTIFY),
                ),
            ),
        )

        assertNotNull(canal)
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", canal.serviceUuid)
        assertEquals("6e400003-b5a3-f393-e0a9-e50e24dcca9e", canal.notifyUuid)
        assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e", canal.writeUuid)
        assertTrue(canal.writeWithoutResponse)
    }

    // (3) Un service connu gagne contre un générique listé avant lui.
    @Test
    fun connuPrefereAuGeneriqueMemeListeApres() {
        val generique = service(
            "12345678-1234-1234-1234-1234567890ab",
            char("12345678-1234-1234-1234-1234567890ac", BleProperty.NOTIFY, BleProperty.WRITE),
        )
        val connu = service(
            "0000ffe0-0000-1000-8000-00805f9b34fb",
            char("0000ffe1-0000-1000-8000-00805f9b34fb", BleProperty.NOTIFY, BleProperty.WRITE),
        )

        val canal = BleProfiles.choose(listOf(generique, connu))

        assertNotNull(canal)
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", canal.serviceUuid)
    }

    // (4) Sans service connu, repli sur le premier service compatible.
    @Test
    fun repliGeneriqueSansServiceConnu() {
        val canal = BleProfiles.choose(
            listOf(
                service(
                    "12345678-1234-1234-1234-1234567890ab",
                    char("12345678-1234-1234-1234-1234567890ac", BleProperty.NOTIFY, BleProperty.WRITE),
                ),
            ),
        )

        assertNotNull(canal)
        assertEquals("12345678-1234-1234-1234-1234567890ab", canal.serviceUuid)
        assertEquals("12345678-1234-1234-1234-1234567890ac", canal.notifyUuid)
    }

    // (5) Un service sans notify/indicate est ignoré.
    @Test
    fun serviceSansNotifyIgnore() {
        val canal = BleProfiles.choose(
            listOf(
                service(
                    "0000ffe0-0000-1000-8000-00805f9b34fb",
                    char("0000ffe1-0000-1000-8000-00805f9b34fb", BleProperty.WRITE, BleProperty.READ),
                ),
            ),
        )

        assertNull(canal)
    }

    // (6) Liste vide → null.
    @Test
    fun listeVideDonneNull() {
        assertNull(BleProfiles.choose(emptyList()))
    }

    // (7) Casse mixte et UUID court « FFE0 » reconnus comme le service connu.
    @Test
    fun casseMixteEtUuidCourtReconnus() {
        val canal = BleProfiles.choose(
            listOf(
                service(
                    "FFE0",
                    char("FFE1", BleProperty.NOTIFY, BleProperty.WRITE),
                ),
            ),
        )

        assertNotNull(canal)
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", canal.serviceUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", canal.notifyUuid)
    }

    // (8) À service égal, write-without-response est préféré à write.
    @Test
    fun writeWithoutResponsePrefere() {
        val canal = BleProfiles.choose(
            listOf(
                service(
                    "12345678-1234-1234-1234-1234567890ab",
                    char("12345678-1234-1234-1234-1234567890aa", BleProperty.WRITE),
                    char("12345678-1234-1234-1234-1234567890bb", BleProperty.NOTIFY, BleProperty.WRITE_WITHOUT_RESPONSE),
                ),
            ),
        )

        assertNotNull(canal)
        assertEquals("12345678-1234-1234-1234-1234567890bb", canal.writeUuid)
        assertTrue(canal.writeWithoutResponse)
    }
}
