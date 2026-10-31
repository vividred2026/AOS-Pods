package pro.aospods.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** AirPods 배터리 상태를 앱 전체에 공유하는 저장소. */
object PodsRepo {
    private val _pods = MutableStateFlow<Pods?>(null)
    val pods: StateFlow<Pods?> = _pods
    val running = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    /** 서비스를 끄지 않는 비치명적 안내(스캔 상태 등). */
    val status = MutableStateFlow<String?>(null)
    /** 진단용: 마지막 에어팟(0x07) 패킷 원문 16진수 · 받은 시각 · 신호 세기. */
    @Volatile var lastRawHex: String = ""
    @Volatile var lastRawAt: Long = 0L
    @Volatile var lastRssi: Int = 0
    @Volatile var rawCount: Int = 0
    @Volatile var diagLine: String = ""
    fun update(p: Pods?) { _pods.value = p }
}

/** Apple 제조사 광고(0x004C, 07 19)를 스캔하는 포그라운드 서비스. */
class ScanService : Service() {

    private var scanner: BluetoothLeScanner? = null
    private val prefs by lazy { Prefs(this) }
    private val overlay by lazy { PodsOverlay(this) }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    // STEP 3: 직전 '양쪽 착용' 상태 · 현재 보유 중인 오디오 포커스 요청
    private var prevWorn: Int? = null          // 직전에 확정된 '끼고 있는 개수'(0~2)
    private var pendingWorn: Int? = null       // 오신호 방지: 같은 값이 2번 연속 와야 확정
    private var pendingCount = 0
    private var focusRequest: AudioFocusRequest? = null

    /** 스캔을 시작했지만 일정 시간 내 실제 기기를 못 찾았을 때의 폴백.
     *  에뮬레이터처럼 BLE 스캔 결과가 오지 않는 환경에서도 화면이 비지 않게 한다. */
    // 진단 카운터: 전체 BLE / 애플(0x4C) / 에어팟(0x07) 패킷 수
    private var cTotal = 0
    private var cApple = 0
    private var cPods = 0
    private var lastAppleHex = ""
    private var c07 = 0
    private var last07Hex = ""
    private val typeCounts = sortedMapOf<Int, Int>()

    /** 실제 에어팟을 찾을 때까지 5초마다 수신 현황을 알리고, 그동안만 데모를 표시. */
    private val demoFallback = object : Runnable {
        override fun run() {
            if (cPods > 0) return
            // 연결 전에는 진단 토스트를 띄우지 않음(앱 화면의 연결 상태 안내로 대신)
            if (BtState.connectedName.value == null) { handler.postDelayed(this, 5000); return }
            val types = typeCounts.entries.sortedByDescending { it.value }.take(5)
                .joinToString(" ") { "%02X×%d".format(it.key, it.value) }
            PodsRepo.diagLine = "진단: BLE $cTotal · 애플 $cApple · 07 $c07 · 에어팟 0" +
                "\n유형: $types" +
                (if (last07Hex.isNotEmpty()) "\n07: ${last07Hex.take(20)}…" else "")
            // 데모(가짜 값)는 표시하지 않음 — 실제 에어팟을 찾을 때까지 '찾는 중' 상태 유지
            handler.postDelayed(this, 5000)
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                cTotal++
                val data = result.scanRecord?.getManufacturerSpecificData(AirPodsParser.appleId())
                    ?: return
                cApple++
                lastAppleHex = data.joinToString("") { "%02X".format(it) }
                AirPodsParser.messageTypes(data).forEach { typeCounts[it] = (typeCounts[it] ?: 0) + 1 }
                AirPodsParser.findProximity(data)?.let { p ->
                    c07++; last07Hex = p.joinToString("") { "%02X".format(it) }
                    PodsRepo.lastRawHex = last07Hex
                    PodsRepo.lastRawAt = System.currentTimeMillis()
                    PodsRepo.lastRssi = result.rssi
                    PodsRepo.rawCount = c07
                }
                val pods = AirPodsParser.parse(data, prefs.model) ?: return
                // 이 폰에 에어팟(오디오 기기)이 연결돼 있을 때만 배터리 처리
                if (BtState.connectedName.value == null) return
                if (pods.hasAny) {
                    if (cPods == 0) PodsRepo.status.value = "실제 에어팟 감지! ${pods.left}/${pods.right}/${pods.case}%"
                    cPods++
                    handler.removeCallbacks(demoFallback)
                    deliver(pods)
                }
            } catch (_: Throwable) { /* 한 패킷 처리 실패가 앱을 죽이지 않도록 */ }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(0, it) }
        }
        override fun onScanFailed(errorCode: Int) {
            val hint = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "이미 스캔 중"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "앱 등록 실패"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "이 기기가 BLE 스캔 미지원"
                SCAN_FAILED_INTERNAL_ERROR -> "내부 오류"
                5 -> "리소스 부족"
                6 -> "너무 잦은 스캔(잠시 후 재시도)"
                else -> "코드 $errorCode"
            }
            PodsRepo.status.value = "BLE 스캔 실패: $hint"
        }
    }

    /** 배터리 상태를 저장소 · 알림 · 팝업에 반영. 어떤 예외도 앱을 죽이지 않게 격리. */
    private fun deliver(pods: Pods) {
        try {
            PodsRepo.update(pods)
            notify(pods)   // 내용은 buildNotification이 설정에 맞게 결정
            // 팝업: 설정이 켜져 있고, 양쪽 착용 숨김 조건이 아니면 오버레이 표시
            val hide = prefs.hidePopupWhenInEar && pods.leftInEar && pods.rightInEar
            if (prefs.popup && !hide) overlay.show(pods, prefs) else overlay.hide()
            handleAutoPause(pods)            // STEP 3 착용 감지 일시정지·재생
            OnOffTileService.refresh(this)   // 타일 배터리 보조 문구 갱신
        } catch (_: Throwable) { /* 표시 단계 오류는 무시하고 스캔은 계속 */ }
    }

    /** STEP 3: 양쪽 착용 → 해제 전환 시 일시정지(포커스 획득),
     *  해제 → 양쪽 착용 전환 시 재생(포커스 반환). 통화 중에는 동작 안 함. */
    private fun handleAutoPause(pods: Pods) {
        if (!prefs.autoPause && !prefs.autoResume) { prevWorn = null; return }
        val mode = audioManager.mode
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) return

        val wornNow = (if (pods.leftInEar) 1 else 0) + (if (pods.rightInEar) 1 else 0)
        // 순간적인 오신호로 멈추지 않도록, 같은 착용 개수가 2번 연속일 때만 확정
        if (wornNow != pendingWorn) { pendingWorn = wornNow; pendingCount = 1; return }
        if (++pendingCount < 2) return
        if (wornNow == prevWorn) return
        val action = decideFocus(prevWorn, wornNow, prefs.autoPause, prefs.autoResume)
        prevWorn = wornNow
        when (action) {
            FocusAction.PAUSE -> requestPause()
            FocusAction.RESUME -> abandonResume()
            FocusAction.NONE -> {}
        }
    }

    /** 일시 독점 포커스를 요청하면 재생 중인 음악 앱이 스스로 멈춘다. */
    private fun requestPause() {
        if (focusRequest != null) return
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setOnAudioFocusChangeListener({ change ->
                    // 사용자가 음악 앱에서 직접 재생하면 포커스를 잃음 → 보유 해제해 다음 일시정지가 다시 동작
                    if (change == AudioManager.AUDIOFOCUS_LOSS) abandonResume()
                }, handler)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                ).build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
    }

    /** 포커스를 반환하면 멈췄던 음악 앱이 재생을 재개한다. */
    private fun abandonResume() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION") audioManager.abandonAudioFocus(null)
        }
        focusRequest = null
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val type = if (Build.VERSION.SDK_INT >= 29)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(PodsRepo.pods.value), type
            )
        } catch (e: Exception) {
            PodsRepo.error.value = "서비스 시작 실패: ${e.message ?: e}"
            PodsRepo.running.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        PodsRepo.error.value = null
        PodsRepo.running.value = true
        if (!prefs.popup) overlay.hide()          // 팝업 OFF 즉시 반영
        registerBt()
        startScan()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private var scanning = false
    private var btReceiver: android.content.BroadcastReceiver? = null

    /** 연결/해제 시: 연결되면 다음 패킷부터 표시, 끊기면 팝업·값 정리. */
    private fun registerBt() {
        if (btReceiver != null) return
        BtState.refresh(this)
        val r = BtState.receiver {
            handler.postDelayed({
                if (BtState.connectedName.value != null) {
                    // 연결 중: BLE 상세값이 없으면 HFP 전체 배터리로 알림 갱신
                    try { getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, buildNotification(PodsRepo.pods.value)) } catch (_: Exception) {}
                } else {
                    if (!prefs.showWhenDisconnected) PodsRepo.update(null)
                    overlay.hide(); prevWorn = null
                    try { getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, buildNotification(PodsRepo.pods.value)) } catch (_: Exception) {}
                }
                OnOffTileService.refresh(this)
            }, 800)   // 프로파일 조회(비동기) 결과 반영 대기
        }
        btReceiver = r
        ContextCompat.registerReceiver(this, r, BtState.filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun startScan() {
        if (scanning) return   // 이미 스캔 중이면 재시작하지 않음(카운터·값 유지, 잦은 재시작 방지)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= 31
        ) return

        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) {
            // 가짜 값을 보여주지 않고, 블루투스를 켜라고 안내만 함
            PodsRepo.status.value = "블루투스가 꺼져 있어요 — 켜면 에어팟을 찾습니다"
            overlay.hide()
            return
        }
        PodsRepo.update(null)   // 이전 값(혹시 남은 데모 포함) 초기화
        // 위치 서비스가 꺼져 있으면 많은 기기에서 BLE 스캔 결과가 오지 않음 — 안내
        if (!locationEnabled()) {
            PodsRepo.status.value = "위치 서비스가 꺼져 있으면 에어팟을 못 찾을 수 있어요(설정 > 위치 ON)"
        }
        scanner = bt.bluetoothLeScanner
        // 진단 빌드: 필터 없이 전체 BLE를 받아 단계별(전체/애플/에어팟)로 집계
        cTotal = 0; cApple = 0; cPods = 0; lastAppleHex = ""
        c07 = 0; last07Hex = ""; typeCounts.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L).build()
        try {
            scanner?.startScan(null, settings, callback)
            scanning = true
            PodsRepo.status.value = "에어팟 검색 중… 케이스 뚜껑을 열어주세요"
            // 실제 기기 광고가 올 때까지 대기하되, 못 찾으면 데모로 화면을 채움
            handler.removeCallbacks(demoFallback)
            handler.postDelayed(demoFallback, SCAN_TIMEOUT_MS)
        } catch (e: Exception) {
            PodsRepo.status.value = "스캔 시작 실패: ${e.message}"
        }
    }

    /** 시스템 위치 서비스 ON 여부 (BLE 스캔 결과 수신의 전제 조건이 되는 기기가 많음). */
    private fun locationEnabled(): Boolean = try {
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) { true }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        PodsRepo.running.value = false
        scanning = false
        btReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        btReceiver = null
        handler.removeCallbacks(demoFallback)
        abandonResume()   // 보유 중인 오디오 포커스 반환
        overlay.hide()
        try { scanner?.stopScan(callback) } catch (_: Exception) {}
    }

    private fun notify(pods: Pods) {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        mgr.notify(NOTIF_ID, buildNotification(pods))
    }

    private fun buildNotification(pods: Pods?): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "AosPods", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        val connected = pods != null && pods.hasAny
        val linked = BtState.connectedName.value != null
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pods)
            .setContentTitle("AosPods")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)

        // ① '알림으로 표시' OFF → 서비스 유지에 필요한 최소 알림만(배터리 내용 없음)
        if (!prefs.notification) {
            return builder.setContentText("실행 중 · 알림 표시 꺼짐").build()
        }
        // 연결 끊김: '연결 끊겨도 표시'(N-02)가 켜져 있으면 마지막 값을 유지해서 보여줌
        if (!linked) {
            val text = if (connected && prefs.showWhenDisconnected)
                "연결 끊김 · 마지막 값  ${batteryLine(pods!!)}"
            else "에어팟이 연결되지 않았어요 — 앱에서 '에어팟 연결하기'"
            if (connected && prefs.showWhenDisconnected && prefs.percentInIcon)
                basisValue(pods!!)?.let { builder.setSmallIcon(PercentIcon.icon(it)) }
            return builder.setContentText(text).build()
        }
        if (!connected) {
            val hfp = BtState.hfpBattery.value
            if (hfp != null) {
                // 귀에 낀 상태: HFP 전체 배터리. 좌·우·케이스 상세는 케이스를 열면 BLE로 표시
                if (prefs.percentInIcon) builder.setSmallIcon(PercentIcon.icon(hfp))
                return builder.setContentText("${BtState.connectedName.value} $hfp%")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(
                        "${BtState.connectedName.value} 전체 $hfp%\n왼쪽·오른쪽·케이스는 케이스 뚜껑을 열면 표시돼요"))
                    .build()
            }
            return builder.setContentText("${BtState.connectedName.value} 배터리 확인 중… 케이스를 열어보세요").build()
        }
        // ② '알림 아이콘에 퍼센트 표시' ON → 상태바 아이콘을 표시 기준(N-02) 값의 5% 단위 숫자로
        if (prefs.percentInIcon) basisValue(pods!!)?.let { builder.setSmallIcon(PercentIcon.icon(it)) }
        builder.setContentText(basisLine(pods!!))
        // 큰 알림: 세 배터리를 펼쳐 표시 / 작은 알림: 한 줄만
        if (prefs.bigNotif && !prefs.smallNotif) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(batteryLine(pods, "\n")))
        }
        return builder.build()
    }

    /** N-02 표시 기준에 따른 대표 퍼센트(상태바 아이콘용). 값이 없으면 null. */
    private fun basisValue(p: Pods): Int? = when (prefs.percentType) {
        Prefs.PERCENT_TYPES[1] -> p.left.takeIf { it > 0 }
        Prefs.PERCENT_TYPES[2] -> p.right.takeIf { it > 0 }
        Prefs.PERCENT_TYPES[3] -> p.case.takeIf { it > 0 }
        else -> listOf(p.left, p.right, p.case).filter { it > 0 }.minOrNull()
    }

    /** N-02 표시 기준에 따라 알림에 쓸 한 줄. */
    private fun basisLine(p: Pods): String = when (prefs.percentType) {
        Prefs.PERCENT_TYPES[1] -> "왼쪽 ${p.left}%"
        Prefs.PERCENT_TYPES[2] -> "오른쪽 ${p.right}%"
        Prefs.PERCENT_TYPES[3] -> "케이스 ${p.case}%"
        else -> {
            val vals = listOf(p.left, p.right, p.case).filter { it > 0 }
            if (vals.isEmpty()) "연결됨" else "가장 낮음 ${vals.min()}%"
        }
    }

    enum class FocusAction { PAUSE, RESUME, NONE }

    companion object {
        private const val CHANNEL = "aospods"
        private const val NOTIF_ID = 1
        private const val SCAN_TIMEOUT_MS = 6000L   // 이 시간 내 못 찾으면 데모 폴백

        /** 착용 상태 전환 → 오디오 포커스 동작 결정 (순수 함수, 테스트 대상).
         *  양쪽착용→해제 = 일시정지, 해제→양쪽착용 = 재생, 그 외/첫 패킷 = 없음. */
        /** 끼고 있는 개수 변화 → 동작. 하나라도 빼면 일시정지, 다시 끼면 재생. 첫 확정값은 기준만 잡음. */
        fun decideFocus(
            prevWorn: Int?, wornNow: Int,
            autoPause: Boolean, autoResume: Boolean,
        ): FocusAction = when {
            prevWorn == null -> FocusAction.NONE
            wornNow < prevWorn && autoPause -> FocusAction.PAUSE
            wornNow > prevWorn && autoResume -> FocusAction.RESUME
            else -> FocusAction.NONE
        }

        fun batteryLine(p: Pods, sep: String = "  "): String = buildList {
            if (p.left > 0) add("왼쪽 ${p.left}%")
            if (p.right > 0) add("오른쪽 ${p.right}%")
            if (p.case > 0) add("케이스 ${p.case}%")
        }.joinToString(sep)

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, ScanService::class.java))

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, ScanService::class.java))
    }
}
