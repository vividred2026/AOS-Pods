package pro.aospods.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.MutableStateFlow

/** 연결된 에어팟과의 AACP 상시 연결(앱 전역 하나). ScanService가 연결/해제에 맞춰 시작·정지한다.
 *  연결에 실패한 기기는 다음 연결(재연결) 전까지 다시 시도하지 않는다(배터리·잦은 실패 방지). */
object AapSession {
    /** AACP 채널이 열려 있는지 — 청취 모드 메뉴는 이때만 보인다. */
    val connected = MutableStateFlow(false)
    /** 에어팟이 알려준 현재 청취 모드(Aap.MODE_*). null = 아직 보고 없음. */
    val listenMode = MutableStateFlow<Int?>(null)
    /** 진단·실험 화면용 로그(최근 80줄). */
    val log = MutableStateFlow("")

    /** 서비스가 등록하는 수신 콜백(AACP 수신 스레드에서 호출됨). */
    @Volatile var onEar: ((wornCount: Int) -> Unit)? = null
    @Volatile var onBattery: ((List<Aap.Battery>) -> Unit)? = null

    private var client: AapClient? = null
    private var device: BluetoothDevice? = null
    private var failedFor: BluetoothDevice? = null

    /** 이 기기와 연결 시도. 이미 연결(시도) 중이거나, 이번 연결에서 이미 실패했다면 force 없이는 무시. */
    @Synchronized
    fun start(adapter: BluetoothAdapter, dev: BluetoothDevice, force: Boolean = false) {
        if (!force && (dev == failedFor || (client != null && dev == device))) return
        stopLocked()
        device = dev; failedFor = null
        append("— AACP 연결 시도 —")
        val c = AapClient(adapter, dev, ::append, onConnected = { connected.value = true }) { m ->
            when (m) {
                is Aap.Msg.ListenMode -> listenMode.value = m.mode
                is Aap.Msg.Ear -> onEar?.invoke(m.wornCount)
                is Aap.Msg.Batteries -> onBattery?.invoke(m.list)
                is Aap.Msg.Other -> {}
            }
        }
        client = c
        Thread {
            try { c.run() } finally {
                synchronized(this) {
                    if (client === c) {
                        client = null
                        if (!c.everConnected) failedFor = dev
                        connected.value = false; listenMode.value = null
                    }
                }
            }
        }.apply { isDaemon = true; name = "aacp" }.start()
    }

    /** 연결 해제(에어팟 연결이 끊겼거나 서비스 종료). 실패 기록도 지워 다음 연결 때 다시 시도. */
    @Synchronized
    fun stop() { stopLocked(); failedFor = null }

    private fun stopLocked() {
        client?.close(); client = null; device = null
        connected.value = false; listenMode.value = null
    }

    /** 청취 모드 변경 요청. 전송 실패 시 false. 실제 반영은 에어팟이 보내는 확인(listenMode)으로 판단. */
    fun setListenMode(mode: Int): Boolean = client?.send(Aap.listenModePacket(mode)) ?: false

    private fun append(s: String) {
        val lines = (log.value + s + "\n").lines()
        log.value = lines.takeLast(80).joinToString("\n")
    }
}
