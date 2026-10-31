package pro.aospods.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.MutableStateFlow

/** 연결된 에어팟과의 AACP 상시 연결(앱 전역 하나). ScanService가 연결/해제에 맞춰 시작·정지한다.
 *  연결에 실패한 기기는 다음 연결(재연결) 전까지 다시 시도하지 않는다(배터리·잦은 실패 방지). */
object AapSession {
    /** AACP 채널이 열려 있는지 — 청취 모드 메뉴는 이때만 보인다. */
    val connected = MutableStateFlow(false)
    /** 에어팟이 알려준 현재 청취 모드(Aap.MODE_*). null = 아직 보고 없음. */
    val listenMode = MutableStateFlow<Int?>(null)
    /** 에어팟이 알려준 기기 정보(이름·모델·펌웨어·일련번호). null = 아직 없음. */
    val info = MutableStateFlow<Aap.Msg.Info?>(null)
    /** true = 이번 연결에서 에어팟이 보낸 정보 / false = 예전에 저장해 둔 정보. */
    val infoFresh = MutableStateFlow(false)
    /** 이름 변경을 요청했지만 에어팟이 아직 새 정보로 확인해 주지 않은 이름. */
    val pendingName = MutableStateFlow<String?>(null)
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
    @SuppressLint("MissingPermission")
    fun start(ctx: Context, adapter: BluetoothAdapter, dev: BluetoothDevice, force: Boolean = false) {
        if (!force && (dev == failedFor || (client != null && dev == device))) return
        stopLocked()
        device = dev; failedFor = null
        val prefs = Prefs(ctx.applicationContext)
        val key = try { dev.address } catch (_: Exception) { "" }
        // 에어팟은 정보(0x1D)를 연결 직후에만 보내므로, 저장해 둔 값을 먼저 보여 준다
        if (info.value == null) { info.value = prefs.aapInfo(key); infoFresh.value = false }
        append("— AACP 연결 시도 —")
        val c = AapClient(adapter, dev, ::append, onConnected = { connected.value = true }) { m ->
            when (m) {
                is Aap.Msg.ListenMode -> listenMode.value = m.mode
                is Aap.Msg.Info -> {
                    info.value = m; infoFresh.value = true
                    try { prefs.saveAapInfo(key, m) } catch (_: Exception) {}
                    // 에어팟이 알려준 모델 번호로 모델 설정 자동 반영
                    if (prefs.applyDetectedModel(ModelDetect.fromModelNumber(m.modelNumber))) PodsRepo.modelVersion.value++
                    if (m.name.isNotBlank()) {
                        if (m.name == pendingName.value) pendingName.value = null
                        BtState.connectedName.value = m.name   // 에어팟이 알려준 실제 이름으로 표시
                    }
                }
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
        connected.value = false; listenMode.value = null; info.value = null; infoFresh.value = false; pendingName.value = null
    }

    /** 청취 모드 변경 요청. 전송 실패 시 false. 실제 반영은 에어팟이 보내는 확인(listenMode)으로 판단. */
    fun setListenMode(mode: Int): Boolean = client?.send(Aap.listenModePacket(mode)) ?: false

    /** 이름 변경 요청. 형식이 맞지 않거나(빈 이름·너무 김) 전송 실패 시 false. */
    fun rename(name: String): Boolean {
        val p = Aap.renamePacket(name) ?: return false
        val ok = client?.send(p) ?: false
        if (ok) pendingName.value = name.trim()
        return ok
    }

    private fun append(s: String) {
        val lines = (log.value + s + "\n").lines()
        log.value = lines.takeLast(200).joinToString("\n")
    }
}
