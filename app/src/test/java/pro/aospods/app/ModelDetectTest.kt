package pro.aospods.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelDetectTest {
    private fun msg(id: String) = byteArrayOf(0x07, 0x19, 0x01, id.substring(0, 2).toInt(16).toByte(), id.substring(2, 4).toInt(16).toByte(), 0, 0, 0)

    @Test fun bleIds() {
        assertEquals("에어팟 프로", ModelDetect.fromProximity(msg("0E20")))
        assertEquals("에어팟 4", ModelDetect.fromProximity(msg("1920")))
        assertEquals("에어팟 4", ModelDetect.fromProximity(msg("1B20")))
        assertEquals("에어팟 3", ModelDetect.fromProximity(msg("1320")))
        assertEquals("에어팟 1·2", ModelDetect.fromProximity(msg("0F20")))
        assertEquals("파워비츠 프로", ModelDetect.fromProximity(msg("0B20")))
        assertNull("목록에 없는 제품(에어팟 맥스)은 건드리지 않음", ModelDetect.fromProximity(msg("0A20")))
        assertNull(ModelDetect.fromProximity(byteArrayOf(0x07, 0x19)))
        assertNull(ModelDetect.fromProximity(byteArrayOf(0x10, 0x05, 0, 0, 0)))
    }

    @Test fun modelNumbers() {
        assertEquals("에어팟 프로", ModelDetect.fromModelNumber("A3048"))
        assertEquals("에어팟 프로", ModelDetect.fromModelNumber(" a2084 "))
        assertEquals("에어팟 4", ModelDetect.fromModelNumber("A3053"))
        assertEquals("에어팟 1·2", ModelDetect.fromModelNumber("A2032"))
        assertNull(ModelDetect.fromModelNumber("A2096"))   // 에어팟 맥스
        assertNull(ModelDetect.fromModelNumber(""))
        assertNull(ModelDetect.fromModelNumber(null))
    }

    @Test fun everyResultIsAKnownModelName() {
        listOf("0220", "0F20", "1320", "1920", "1B20", "0E20", "1420", "2420", "0B20")
            .forEach { assert(ModelDetect.fromProximity(msg(it)) in Prefs.MODELS) }
    }
}
