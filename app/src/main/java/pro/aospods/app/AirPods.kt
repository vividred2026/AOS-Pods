package pro.aospods.app

/** AirPods BLE 광고(Apple 제조사 0x004C, 0x07 0x19) 파싱 결과. */
data class Pods(
    val model: String,     // 표시용 모델 이름
    val left: Int,         // 0 = 없음, 1..100
    val right: Int,
    val case: Int,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
    val leftInEar: Boolean,
    val rightInEar: Boolean,
) {
    val hasAny: Boolean get() = left > 0 || right > 0 || case > 0

    /** 지금 귀에 끼고 있는 개수(0~2). */
    val wornCount: Int get() = (if (leftInEar) 1 else 0) + (if (rightInEar) 1 else 0)
}

object AirPodsParser {
    private const val APPLE_ID = 76 // 0x004C

    /** 애플 광고는 [유형][길이][내용] 메시지가 이어붙어 올 수 있음 — 0x07(근접 페어링) 메시지만 추출. */
    fun findProximity(data: ByteArray): ByteArray? {
        var i = 0
        while (i + 1 < data.size) {
            val type = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            if (type == 0x07) return data.copyOfRange(i, minOf(data.size, i + 2 + len))
            i += 2 + len
        }
        return null
    }

    /** 이 패킷에 들어있는 애플 메시지 유형 목록 (진단용). */
    fun messageTypes(data: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (i + 1 < data.size) {
            out += data[i].toInt() and 0xFF
            i += 2 + (data[i + 1].toInt() and 0xFF)
        }
        return out
    }

    /** manufacturerSpecificData(76) 바이트 배열을 Pods로 해석. 형식이 아니면 null. */
    fun parse(raw: ByteArray, modelName: String): Pods? {
        val data = findProximity(raw) ?: return null
        if (data.size < 16) return null
        if (data[0].toInt() and 0xFF != 0x07) return null // 타입 0x07 (Proximity Pairing)
        // 길이 0x19(25)인 메시지만 배터리 형식. 실기기에서 길이 0x11(17)에 내용이 매번 바뀌는
        // 0x07 메시지(암호화된 것으로 보임)가 잡혀 엉뚱한 배터리로 읽힌 적 있음 → "0719"만 해석
        if (data[1].toInt() and 0xFF != 0x19) return null

        val s = data.joinToString("") { "%02X".format(it) } // 16진수 문자열

        fun hex(i: Int): Int = Character.digit(s[i], 16)
        fun battery(raw: Int): Int = when {
            raw == 10 -> 100
            raw < 10 -> raw * 10 + 5
            else -> 0 // 15 = 없음
        }

        // 좌우 반전 플래그: s[10] 니블의 0x02 비트
        val flip = (hex(10) and 0x02) == 0

        val leftRaw = hex(if (flip) 12 else 13)
        val rightRaw = hex(if (flip) 13 else 12)
        val caseRaw = hex(15)

        val chargeBits = hex(14)
        val leftCharging = (chargeBits and (if (flip) 0x1 else 0x2)) != 0
        val rightCharging = (chargeBits and (if (flip) 0x2 else 0x1)) != 0
        val caseCharging = (chargeBits and 0x4) != 0

        // 착용 플래그: s[10..11]을 이진 문자열로
        val statusBits = Integer.parseInt(s.substring(10, 12), 16)
            .toString(2).padStart(8, '0')
        val leftInEar = statusBits.getOrNull(if (flip) 4 else 6) == '1'
        val rightInEar = statusBits.getOrNull(if (flip) 6 else 4) == '1'

        return Pods(
            model = modelName,
            left = battery(leftRaw),
            right = battery(rightRaw),
            case = battery(caseRaw),
            leftCharging = leftCharging,
            rightCharging = rightCharging,
            caseCharging = caseCharging,
            leftInEar = leftInEar,
            rightInEar = rightInEar,
        )
    }

    fun appleId() = APPLE_ID
}
