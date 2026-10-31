package pro.aospods.app

import org.junit.Assert.*
import org.junit.Test

class AapTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test fun parsesBattery() {
        // 오른쪽 100% 방전 · 왼쪽 99% 충전 · 케이스 17% 방전
        val m = Aap.parse(hex("04 00 04 00 04 00 03 02 01 64 02 01 04 01 63 01 01 08 01 11 02 01"))
        assertTrue(m is Aap.Msg.Batteries)
        val list = (m as Aap.Msg.Batteries).list
        assertEquals(3, list.size)
        assertEquals(Aap.Battery(Aap.Part.RIGHT, 100, charging = false, connected = true), list[0])
        assertEquals(Aap.Battery(Aap.Part.LEFT, 99, charging = true, connected = true), list[1])
        assertEquals(Aap.Battery(Aap.Part.CASE, 17, charging = false, connected = true), list[2])
    }

    @Test fun batteryDisconnectedPart() {
        val m = Aap.parse(hex("04 00 04 00 04 00 01 08 01 00 04 01")) as Aap.Msg.Batteries
        assertFalse(m.list[0].connected)
    }

    @Test fun truncatedBatteryEntryDropped() {
        val m = Aap.parse(hex("04 00 04 00 04 00 02 02 01 50 02 01 04 01")) as Aap.Msg.Batteries
        assertEquals(1, m.list.size)
    }

    @Test fun parsesEarDetection() {
        val m = Aap.parse(hex("04 00 04 00 06 00 00 01")) as Aap.Msg.Ear
        assertEquals(Aap.Place.IN_EAR, m.primary)
        assertEquals(Aap.Place.OUT, m.secondary)
        assertEquals(1, m.wornCount)
        assertEquals(2, (Aap.parse(hex("04 00 04 00 06 00 00 00")) as Aap.Msg.Ear).wornCount)
        assertEquals(0, (Aap.parse(hex("04 00 04 00 06 00 02 02")) as Aap.Msg.Ear).wornCount)
    }

    @Test fun parsesListenMode() {
        assertEquals(Aap.Msg.ListenMode(Aap.MODE_ANC), Aap.parse(hex("04 00 04 00 09 00 0D 02 00 00 00")))
        assertEquals(Aap.Msg.ListenMode(Aap.MODE_TRANSPARENCY), Aap.parse(Aap.listenModePacket(Aap.MODE_TRANSPARENCY)))
        assertEquals("04 00 04 00 09 00 0D 04 00 00 00",
            Aap.listenModePacket(Aap.MODE_ADAPTIVE).joinToString(" ") { "%02X".format(it) })
    }

    @Test fun otherAndInvalid() {
        assertEquals(Aap.Msg.Other(0x09), Aap.parse(hex("04 00 04 00 09 00 28 01 00 00 00")))
        assertNull(Aap.parse(hex("01 00 04 00 06 00 00 00")))
        assertNull(Aap.parse(hex("04 00 04")))
    }

    @Test fun outgoingPacketsHaveExpectedShape() {
        assertEquals(16, Aap.HANDSHAKE.size)
        assertEquals(0x0F, Aap.opcode(Aap.REQUEST_NOTIFICATIONS))
        assertEquals(0x4D, Aap.opcode(Aap.FEATURE_FLAGS))
    }
}
