package pro.aospods.app

/** 애플 AACP(=AAP) 프로토콜 — 에어팟과 블루투스 클래식 L2CAP(PSM 0x1001)으로 주고받는 메시지.
 *  BLE 광고(0x07)와 달리 연결 중 실시간 착용 상태와 1% 단위 배터리를 준다.
 *  프로토콜 사실(바이트 값)은 공개 자료를 참고해 직접 구현함. 안드로이드 의존 없음. */
object Aap {
    const val PSM = 0x1001
    const val UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a"

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** 연결 직후 보내야 에어팟이 응답하기 시작함. */
    val HANDSHAKE = bytes(0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    val FEATURE_FLAGS = bytes(0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, 0xD7, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    /** 착용·배터리 등 알림을 받겠다는 요청. */
    val REQUEST_NOTIFICATIONS = bytes(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00, 0xFF, 0xFF, 0xFF, 0xFF)

    const val OP_BATTERY = 0x04
    const val OP_EAR = 0x06
    const val OP_CONTROL = 0x09
    /** 제어 메시지(0x09) 안의 '청취 모드' 항목. */
    const val CTRL_LISTEN_MODE = 0x0D

    /** 청취 모드 값. */
    const val MODE_OFF = 0x01
    const val MODE_ANC = 0x02
    const val MODE_TRANSPARENCY = 0x03
    const val MODE_ADAPTIVE = 0x04
    val MODES = listOf(MODE_ANC, MODE_TRANSPARENCY, MODE_ADAPTIVE, MODE_OFF)

    fun modeName(m: Int?) = when (m) {
        MODE_OFF -> "끔"; MODE_ANC -> "노이즈 캔슬링"; MODE_TRANSPARENCY -> "주변음 허용"
        MODE_ADAPTIVE -> "적응형"; null -> "확인 중"; else -> "알 수 없음(0x%02X)".format(m)
    }

    /** 청취 모드 변경 요청: 04 00 04 00 09 00 0D [mode] 00 00 00 (에어팟도 같은 형식으로 현재 모드를 알림). */
    fun listenModePacket(mode: Int) = bytes(0x04, 0x00, 0x04, 0x00, OP_CONTROL, 0x00, CTRL_LISTEN_MODE, mode, 0x00, 0x00, 0x00)
    /** 주소·일련번호가 들어 있어 진단 화면에 원문을 보이지 않는 메시지. */
    val PRIVATE_OPS = setOf(0x0B, 0x0C, 0x0E, 0x1D)

    enum class Part { LEFT, RIGHT, CASE, SINGLE, UNKNOWN }
    data class Battery(val part: Part, val level: Int, val charging: Boolean, val connected: Boolean)

    /** 0 = 귀, 1 = 밖, 2 = 케이스, 그 외 = 모름 */
    enum class Place { IN_EAR, OUT, IN_CASE, UNKNOWN }

    sealed class Msg {
        data class Batteries(val list: List<Battery>) : Msg()
        data class Ear(val primary: Place, val secondary: Place) : Msg() {
            val wornCount: Int get() = listOf(primary, secondary).count { it == Place.IN_EAR }
        }
        data class ListenMode(val mode: Int) : Msg()
        data class Other(val opcode: Int) : Msg()
    }

    /** 메시지 opcode. 형식([04 00 04 00][op lo][op hi])이 아니면 null. */
    fun opcode(p: ByteArray): Int? {
        if (p.size < 6 || p[0].u() != 0x04 || p[1].u() != 0x00 || p[2].u() != 0x04 || p[3].u() != 0x00) return null
        return p[4].u() or (p[5].u() shl 8)
    }

    fun parse(p: ByteArray): Msg? {
        val op = opcode(p) ?: return null
        return when (op) {
            OP_BATTERY -> {
                if (p.size < 7) return null
                val n = p[6].u()
                val list = (0 until n).mapNotNull { i ->
                    val o = 7 + i * 5
                    if (o + 3 >= p.size) return@mapNotNull null   // 잘린 항목은 버림
                    val part = when (p[o].u()) {
                        0x01 -> Part.SINGLE; 0x02 -> Part.RIGHT; 0x04 -> Part.LEFT; 0x08 -> Part.CASE
                        else -> Part.UNKNOWN
                    }
                    val level = p[o + 2].u()
                    val state = p[o + 3].u()
                    Battery(part, level.coerceIn(0, 100), charging = state == 0x01, connected = state != 0x04)
                }
                Msg.Batteries(list)
            }
            OP_EAR -> {
                if (p.size < 8) return null
                Msg.Ear(place(p[6].u()), place(p[7].u()))
            }
            OP_CONTROL -> {
                if (p.size >= 8 && p[6].u() == CTRL_LISTEN_MODE) Msg.ListenMode(p[7].u()) else Msg.Other(op)
            }
            else -> Msg.Other(op)
        }
    }

    private fun place(v: Int) = when (v) {
        0x00 -> Place.IN_EAR; 0x01 -> Place.OUT; 0x02 -> Place.IN_CASE; else -> Place.UNKNOWN
    }

    private fun Byte.u() = toInt() and 0xFF

    fun describe(m: Msg): String = when (m) {
        is Msg.Batteries -> "배터리: " + m.list.joinToString(" · ") { b ->
            val name = when (b.part) {
                Part.LEFT -> "왼"; Part.RIGHT -> "오"; Part.CASE -> "케이스"; Part.SINGLE -> "본체"; Part.UNKNOWN -> "?"
            }
            if (!b.connected) "$name 없음" else "$name ${b.level}%" + if (b.charging) "⚡" else ""
        }
        is Msg.Ear -> "착용: 주 유닛 ${placeName(m.primary)} · 보조 ${placeName(m.secondary)} → 끼고 있는 개수 ${m.wornCount}"
        is Msg.ListenMode -> "청취 모드: ${modeName(m.mode)}"
        is Msg.Other -> "기타 메시지 0x%02X".format(m.opcode)
    }

    private fun placeName(p: Place) = when (p) {
        Place.IN_EAR -> "귀"; Place.OUT -> "밖"; Place.IN_CASE -> "케이스"; Place.UNKNOWN -> "?"
    }
}
