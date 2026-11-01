package pro.aospods.app

/** 플레이 스토어 갈래: 숨김 API 우회 라이브러리를 쓰지 않는다(구글 플레이 정책).
 *  AACP 소켓·배터리 조회는 일반 반사로만 시도하므로, 안드로이드가 허용하는 폰에서는 그대로 동작하고 막힌 폰에서는 실패로 끝난다. */
object HiddenApi {
    val result: String get() = "플레이 버전 · 우회 사용 안 함"
    fun allowBluetooth(): Boolean = false
}
