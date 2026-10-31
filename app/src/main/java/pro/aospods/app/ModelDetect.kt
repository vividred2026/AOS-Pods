package pro.aospods.app

/**
 * 연결된 에어팟의 모델을 신호에서 알아내 [Prefs.MODELS] 중 하나로 맞춘다 (안드로이드 의존 없음).
 *
 * - BLE 0x07 광고: `07 19 01 [모델 ID 2바이트] …` → 16진수 문자열 s[6..9] (예: `0E20` = AirPods Pro)
 * - AACP 기기 정보(0x1D)의 모델 번호 (예: `A3048`)
 *
 * 모델 설정은 표시 이름(알림·팝업·카드)에만 쓰이므로, 모르는 값이면 null을 돌려주고 설정을 건드리지 않는다.
 */
object ModelDetect {
    private const val AP12 = "에어팟 1·2"
    private const val PRO = "에어팟 프로"
    private const val PBP = "파워비츠 프로"
    private const val AP3 = "에어팟 3"
    private const val AP4 = "에어팟 4"

    /** BLE 0x07 모델 ID(s[6..9], 대문자) → 모델 이름. 비츠 등 목록에 없는 제품은 null. */
    private val BLE_IDS = mapOf(
        "0220" to AP12, "0F20" to AP12,
        "1320" to AP3,
        "1920" to AP4, "1B20" to AP4,
        "0E20" to PRO, "1420" to PRO, "2420" to PRO,
        "0B20" to PBP,
    )

    /** 애플 모델 번호(A로 시작) → 모델 이름. 이어버드·케이스 번호 모두 포함. */
    private val MODEL_NUMBERS = mapOf(
        "A1523" to AP12, "A1722" to AP12, "A1602" to AP12,            // AirPods 1
        "A2031" to AP12, "A2032" to AP12, "A1938" to AP12,            // AirPods 2
        "A2564" to AP3, "A2565" to AP3, "A2566" to AP3, "A2897" to AP3,
        "A3053" to AP4, "A3050" to AP4, "A3056" to AP4, "A3054" to AP4, "A3058" to AP4,
        "A2083" to PRO, "A2084" to PRO, "A2190" to PRO,               // Pro 1
        "A2698" to PRO, "A2699" to PRO, "A2700" to PRO,               // Pro 2
        "A3047" to PRO, "A3048" to PRO, "A2968" to PRO,               // Pro 2 (USB-C)
        "A2047" to PBP, "A2048" to PBP, "A2078" to PBP,
    )

    /** 0x07 메시지(타입 바이트부터)에서 모델 이름. */
    fun fromProximity(msg: ByteArray): String? {
        if (msg.size < 5 || (msg[0].toInt() and 0xFF) != 0x07 || (msg[1].toInt() and 0xFF) != 0x19) return null
        val id = "%02X%02X".format(msg[3], msg[4])
        return BLE_IDS[id]
    }

    /** AACP 기기 정보의 모델 번호에서 모델 이름. */
    fun fromModelNumber(modelNumber: String?): String? {
        val key = modelNumber?.trim()?.uppercase()?.takeWhile { it.isLetterOrDigit() } ?: return null
        return MODEL_NUMBERS[key]
    }
}
