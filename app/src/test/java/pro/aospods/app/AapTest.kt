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

    @Test fun parsesInfo() {
        // 실제 형식: 바이너리 헤더(02 D5 00 04 00) 뒤에 0으로 끝나는 문자열들
        fun z(s: String) = s.toByteArray() + byteArrayOf(0)
        val p = hex("04 00 04 00 1D 00 02 D5 00 04 00") +
            z("내 에어팟") + z("A3048") + z("Apple Inc.") + z("SERIAL00") + z("81.2675") +
            z("81.2675") + z("1.0.0") + z("com.apple.accessory.updater.app.71") + z("LEFT0001") + z("RIGHT001")
        val m = Aap.parse(p) as Aap.Msg.Info
        assertEquals("내 에어팟", m.name)
        assertEquals("A3048", m.modelNumber)
        assertEquals("Apple Inc.", m.manufacturer)
        assertEquals("SERIAL00", m.serial)
        assertEquals("81.2675", m.firmware)
        assertEquals("LEFT0001", m.leftSerial)
        assertEquals("RIGHT001", m.rightSerial)
        assertFalse("기기 정보 로그에 일련번호를 남기지 않음", Aap.describe(m).contains("SERIAL00"))
    }

    @Test fun renamePacket() {
        assertEquals("04 00 04 00 1A 00 01 03 00 41 42 43",
            Aap.renamePacket(" ABC ")!!.joinToString(" ") { "%02X".format(it) })
        assertEquals("한글은 UTF-8 바이트 수로 길이 표시", 6, Aap.renamePacket("에어")!![7].toInt())
        assertNull(Aap.renamePacket("   "))
        assertNull(Aap.renamePacket("가".repeat(11)))   // 33바이트 > 32
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
