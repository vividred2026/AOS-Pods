package pro.aospods.app

/** 플레이 스토어 갈래: 숨김 API 우회를 쓰지 않는다(구글 플레이 정책). 항상 "사용 안 함". */
object HiddenApi {
    val result: String get() = "플레이 버전 · 사용 안 함"
    fun allowBluetooth(): Boolean = false
}
