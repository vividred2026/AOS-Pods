package pro.aospods.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice

/** 플레이 스토어 갈래: 에어팟 전용 채널(AACP)은 숨김 API가 필요해 지원하지 않는다.
 *  run()은 안내만 남기고 바로 끝나 AapSession이 "연결 실패"로 처리한다(청취 모드·에어팟 정보 메뉴는 보이지 않음). */
class AapClient(
    @Suppress("UNUSED_PARAMETER") adapter: BluetoothAdapter,
    @Suppress("UNUSED_PARAMETER") device: BluetoothDevice,
    private val log: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") onConnected: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onMessage: (Aap.Msg) -> Unit,
) {
    val everConnected: Boolean get() = false
    fun run() { log("플레이 스토어 버전에서는 에어팟 전용 채널(실시간 착용·1% 배터리·청취 모드)을 지원하지 않아요. 전체 버전(깃허브 릴리스)을 설치하면 쓸 수 있어요.") }
    fun send(@Suppress("UNUSED_PARAMETER") p: ByteArray): Boolean = false
    fun close() {}
}
