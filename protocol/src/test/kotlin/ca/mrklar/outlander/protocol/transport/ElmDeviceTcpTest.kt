package ca.mrklar.outlander.protocol.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ElmDeviceTcpTest {

    @Test
    fun fabriqueTcpEncodeHotePort() {
        val device = ElmDevice.tcp("192.168.0.10", 35000)
        assertEquals(ElmTransportKind.TCP, device.kind)
        assertEquals("192.168.0.10:35000", device.id)
        assertEquals("192.168.0.10" to 35000, ElmDevice.parseTcpId(device.id))
    }

    @Test
    fun fabriqueTcpNettoieEtValide() {
        assertEquals("10.0.0.5:3333", ElmDevice.tcp(" 10.0.0.5 ", 3333).id)
        assertFailsWith<IllegalArgumentException> { ElmDevice.tcp("", 35000) }
        assertFailsWith<IllegalArgumentException> { ElmDevice.tcp("hôte invalide", 35000) }
        assertFailsWith<IllegalArgumentException> { ElmDevice.tcp("192.168.0.10", 0) }
        assertFailsWith<IllegalArgumentException> { ElmDevice.tcp("192.168.0.10", 65536) }
    }

    @Test
    fun parseTcpIdInvalideRetourneNull() {
        assertNull(ElmDevice.parseTcpId("AA:BB:CC:DD:EE:FF"))
        assertNull(ElmDevice.parseTcpId("192.168.0.10"))
        assertNull(ElmDevice.parseTcpId("192.168.0.10:abc"))
        assertNull(ElmDevice.parseTcpId("192.168.0.10:70000"))
        assertNull(ElmDevice.parseTcpId(":35000"))
    }
}
