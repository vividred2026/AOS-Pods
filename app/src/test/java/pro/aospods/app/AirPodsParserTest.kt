package pro.aospods.app

import org.junit.Assert.*
import org.junit.Test

class AirPodsParserTest {
    // byte5=0x10, byte6=0x5A, byte7=0x40, type=0x07, 모델코드 non-Pro
    private fun packet(): ByteArray {
        val b = ByteArray(27)
        b[0] = 0x07
        b[1] = 0x19          // 실제 패킷과 같은 길이(25)
        b[3] = 0x00          // s[7] != 'E'
        b[5] = 0x10          // s[10]='1' (flip=true), s[11]='0'
        b[6] = 0x5A          // s[12]='5'(왼5), s[13]='A'(오10)
        b[7] = 0x40          // s[14]='4'(케이스 충전), s[15]='0'(케이스raw0)
        return b
    }

    @Test fun parsesBatteryAndCharging() {
        val p = AirPodsParser.parse(packet(), "테스트")!!
        assertEquals(55, p.left)    // raw 5 -> 55%
        assertEquals(100, p.right)  // raw 10 -> 100%
        assertEquals(5, p.case)     // raw 0 -> 5%
        assertTrue(p.caseCharging)
        assertFalse(p.leftCharging)
        assertFalse(p.rightCharging)
        assertTrue(p.hasAny)
    }

    @Test fun rejectsWrongType() {
        val b = ByteArray(27); b[0] = 0x0C
        assertNull(AirPodsParser.parse(b, "x"))
    }

    @Test fun rejectsShort() {
        assertNull(AirPodsParser.parse(ByteArray(8), "x"))
    }

    @Test fun batteryUnknownIs15() {
        val b = packet(); b[7] = 0x4F   // s[15]='F'(15) -> 없음(0)
        val p = AirPodsParser.parse(b, "x")!!
        assertEquals(0, p.case)
    }
}

class ProximityTlvTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @org.junit.Test fun findsProximityAfterOtherMessage() {
        // 0x10(Nearby, 길이 2) 다음에 0x07 메시지가 이어붙은 패킷
        val raw = hex("10021A2B" + "0719" + "01" + "0E20" + "2B" + "99" + "8F" + "01".repeat(20))
        val p = AirPodsParser.findProximity(raw)
        org.junit.Assert.assertNotNull(p)
        org.junit.Assert.assertEquals(0x07, p!![0].toInt() and 0xFF)
        org.junit.Assert.assertEquals(listOf(0x10, 0x07), AirPodsParser.messageTypes(raw))
    }

    @org.junit.Test fun handoffOnlyHasNoProximity() {
        org.junit.Assert.assertNull(AirPodsParser.findProximity(hex("0C0E00AAC339" + "00".repeat(10))))
    }
}

class WornCountTest {
    @org.junit.Test fun countsBuds() {
        val p = Pods("x", 50, 50, 50, false, false, false, true, false)
        org.junit.Assert.assertEquals(1, p.wornCount)
        org.junit.Assert.assertEquals(2, p.copy(rightInEar = true).wornCount)
    }
}

class ProximityLengthTest {
    @org.junit.Test fun length0x11IsNotBattery() {
        // 실기기(삼성 SM-S937N) 진단에서 받은 길이 0x11 메시지 — 내용이 매번 달라 배터리 형식이 아님
        val raw = "0711060A03D32E3CB82514D94334812C832EA9".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        org.junit.Assert.assertNull(AirPodsParser.parse(raw, "에어팟 4"))
        org.junit.Assert.assertNull(ModelDetect.fromProximity(raw))
    }
}

class DecryptTailTest {
    @org.junit.Test fun roundTrip() {
        val key = ByteArray(16) { (it * 7).toByte() }
        val plain = ByteArray(16) { (0x10 + it).toByte() }
        val c = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
        val msg = byteArrayOf(0x07, 0x11, 0x06) + c.doFinal(plain)   // 에어팟 4 형식: 1바이트 + 암호화 16바이트
        org.junit.Assert.assertArrayEquals(plain, AirPodsParser.decryptTail(msg, key))
        org.junit.Assert.assertNull(AirPodsParser.decryptTail(msg, ByteArray(15)))
        org.junit.Assert.assertNull(AirPodsParser.decryptTail(byteArrayOf(0x07, 0x02, 0x01), key))
    }
}
