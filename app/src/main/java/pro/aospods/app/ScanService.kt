package pro.aospods.app

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
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
    /** 착용 신호(0x07) 수신 여부. null = 판단 전(연결 안 됨 등), true = 최근 수신, false = 연결 중인데 신호 없음. */
    val wearSignal = MutableStateFlow<Boolean?>(null)
    /** 진단용: 마지막 에어팟(0x07) 패킷 원문 16진수 · 받은 시각 · 신호 세기. */
    @Volatile var lastRawHex: String = ""
    @Volatile var lastRawAt: Long = 0L
    @Volatile var lastRssi: Int = 0
    @Volatile var rawCount: Int = 0
    @Volatile var diagLine: String = ""
    /** 진단용: 최근 서로 다른 0x07 원문(최대 6개, "RSSI 원문"). 왼쪽 누락 등 판정용. */
    val recentRaw = ArrayDeque<String>()
    fun update(p: Pods?) { _pods.value = p }

    fun addRecent(hex: String, rssi: Int) = synchronized(recentRaw) {
        if (recentRaw.any { it.endsWith(hex) }) return@synchronized
        recentRaw.addLast("$rssi $hex")
        while (recentRaw.size > 6) recentRaw.removeFirst()
    }
}

/** Apple 제조사 광고(0x004C)를 스캔하는 포그라운드 서비스. */
class ScanService : Service() {

    private var scanner: BluetoothLeScanner? = null
    private val prefs by lazy { Prefs(this) }
    private val overlay by lazy { PodsOverlay(this) }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    // STEP 3: 착용 판단 · 현재 보유 중인 오디오 포커스 요청
    private val wear = WearDetector()
    private var focusRequest: AudioFocusRequest? = null

    // 진단 카운터: 수신 BLE / 애플(0x4C) / 0x07 / 반영된 에어팟 패킷 수
    private var cTotal = 0
    private var cApple = 0
    private var cPods = 0
    private var c07 = 0
    private var last07Hex = ""
    private val typeCounts = sortedMapOf<Int, Int>()
    private var connectedSince = 0L

    private var scanning = false
    /** 지금 스캔이 필터 없는 전체 스캔인지(화면 ON) / 애플 필터 스캔인지(화면 OFF). */
    private var scanAllMode = true
    private var btReceiver: android.content.BroadcastReceiver? = null
    private var screenReceiver: android.content.BroadcastReceiver? = null

    /** 5초마다 진단 문구와 '착용 신호 수신 여부'를 갱신. */
    private val diagTicker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val types = typeCounts.entries.sortedByDescending { it.value }.take(5)
                .joinToString(" ") { "%02X×%d".format(it.key, it.value) }
            PodsRepo.diagLine = "진단(${if (scanAllMode) "전체 스캔·화면 ON" else "애플 필터·화면 OFF"}): " +
                "BLE $cTotal · 애플 $cApple · 07 $c07 · 반영 $cPods" +
                "\n유형: $types" +
                (if (last07Hex.isNotEmpty()) "\n07: ${last07Hex.take(20)}…" else "")

            if (BtState.connectedName.value == null) {
                connectedSince = 0L; PodsRepo.wearSignal.value = null
            } else {
                if (connectedSince == 0L) connectedSince = now
                PodsRepo.wearSignal.value = when {
                    PodsRepo.lastRawAt > 0 && now - PodsRepo.lastRawAt < WEAR_SIGNAL_WINDOW_MS -> true
                    now - connectedSince >= WEAR_SIGNAL_WINDOW_MS -> false
                    else -> null
                }
            }
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
                AirPodsParser.messageTypes(data).forEach { typeCounts[it] = (typeCounts[it] ?: 0) + 1 }
                AirPodsParser.findProximity(data)?.let { p ->
                    c07++; last07Hex = p.joinToString("") { "%02X".format(it) }
                    PodsRepo.lastRawHex = last07Hex
                    PodsRepo.lastRawAt = System.currentTimeMillis()
                    PodsRepo.lastRssi = result.rssi
                    PodsRepo.rawCount = c07
                    PodsRepo.addRecent(last07Hex, result.rssi)
                }
                val pods = AirPodsParser.parse(data, prefs.model) ?: return
                // 이 폰에 에어팟(오디오 기기)이 연결돼 있을 때만 배터리 처리
                if (BtState.connectedName.value == null) return
                if (pods.hasAny) {
                    if (cPods == 0) PodsRepo.status.value = "에어팟 감지 ${pods.left}/${pods.right}/${pods.case}%"
                    cPods++
                    deliver(pods)
                }
            } catch (_: Throwable) { /* 한 패킷 처리 실패가 앱을 죽이지 않도록 */ }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(0, it) }
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
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
            handleAutoPause(pods)            // STEP 3 착용 감지는 매 패킷 판단(2회 연속 확정)
            // 광고는 초당 여러 번 오므로 값이 바뀐 경우에만 알림·팝업·타일을 다시 그림
            // (알림을 너무 자주 갱신하면 시스템이 버림)
            if (pods == PodsRepo.pods.value) return
            PodsRepo.update(pods)
            notify(pods)
            showOrHidePopup(pods)
            OnOffTileService.refresh(this)
        } catch (_: Throwable) { /* 표시 단계 오류는 무시하고 스캔은 계속 */ }
    }

    /** 팝업: 설정이 켜져 있고, 양쪽 착용 숨김 조건이 아니면 오버레이 표시. */
    private fun showOrHidePopup(pods: Pods) {
        val hide = prefs.hidePopupWhenInEar && pods.leftInEar && pods.rightInEar
        if (prefs.popup && !hide) overlay.show(pods, prefs) else overlay.hide()
    }

    /** STEP 3: 끼고 있는 개수가 줄면 일시정지(포커스 획득), 늘면 재생(포커스 반환). 통화 중에는 동작 안 함. */
    private fun handleAutoPause(pods: Pods) {
        if (!prefs.autoPause && !prefs.autoResume) { wear.reset(); return }
        val mode = audioManager.mode
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) return
        when (wear.feed(pods.wornCount, prefs.autoPause, prefs.autoResume)) {
            FocusAction.PAUSE -> requestPause()
            FocusAction.RESUME -> abandonResume()
            FocusAction.NONE -> {}
        }
    }

    /** 일시 독점 포커스를 요청하면 재생 중인 음악 앱이 스스로 멈춘다. */
    private fun requestPause() {
        if (focusRequest != null) return
        // 재생 중이 아니면 포커스를 잡지 않음(잡아두면 나중에 엉뚱하게 재생이 재개될 수 있음)
        if (!audioManager.isMusicActive) return
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
        if (audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = req
        }
    }

    /** 포커스를 반환하면 멈췄던 음악 앱이 재생을 재개한다. */
    private fun abandonResume() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
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
        registerBt()
        registerScreen()
        // 설정 변경(팝업 ON/OFF·여백 등)을 이미 떠 있는 팝업에 즉시 반영
        val p = PodsRepo.pods.value
        if (p != null && p.hasAny && BtState.connectedName.value != null) showOrHidePopup(p)
        else if (!prefs.popup) overlay.hide()
        startScan()
        handler.removeCallbacks(diagTicker)
        handler.post(diagTicker)
        return START_STICKY
    }

    /** 연결/해제·블루투스 ON/OFF 시: 연결되면 다음 패킷부터 표시, 끊기면 팝업·값 정리. */
    private fun registerBt() {
        if (btReceiver != null) return
        BtState.refresh(this)
        val r = BtState.receiver { action, adapterState ->
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (adapterState) {
                    BluetoothAdapter.STATE_ON -> startScan()   // 블루투스를 다시 켜면 스캔 재시작
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        stopScan(); overlay.hide()
                    }
                }
            }
            handler.postDelayed({
                if (BtState.connectedName.value == null) {
                    if (!prefs.showWhenDisconnected) PodsRepo.update(null)
                    overlay.hide(); wear.reset(); abandonResume()
                }
                // 연결 중이면 BLE 상세값이 없을 때 HFP 전체 배터리로, 끊기면 끊김 상태로 알림 갱신
                notifyCurrent()
                OnOffTileService.refresh(this)
            }, 800)   // 프로파일 조회(비동기) 결과 반영 대기
        }
        btReceiver = r
        ContextCompat.registerReceiver(this, r, BtState.filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /** 화면 ON/OFF에 따라 스캔 방식을 바꿔 다시 시작. */
    private fun registerScreen() {
        if (screenReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (scanning && scanAllMode != isScreenOn()) { stopScan(); startScan() }
            }
        }
        screenReceiver = r
        val f = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun isScreenOn(): Boolean =
        try { getSystemService(android.os.PowerManager::class.java).isInteractive } catch (_: Exception) { true }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning) return   // 이미 스캔 중이면 재시작하지 않음(카운터·값 유지, 잦은 재시작 방지)
        if (!Perms.ok(this)) {
            PodsRepo.status.value = "권한이 없어 에어팟을 찾을 수 없어요 — 앱에서 권한을 허용해 주세요"
            return
        }
        val bt = getSystemService(BluetoothManager::class.java)?.adapter
        if (bt == null || !bt.isEnabled) {
            // 가짜 값을 보여주지 않고, 블루투스를 켜라고 안내만 함. 켜지면 리시버가 다시 시작
            PodsRepo.status.value = "블루투스가 꺼져 있어요 — 켜면 에어팟을 찾습니다"
            overlay.hide()
            return
        }
        // 30 이하는 위치 서비스가 꺼져 있으면 BLE 스캔 결과가 오지 않음 — 안내
        if (Build.VERSION.SDK_INT < 31 && !locationEnabled()) {
            PodsRepo.status.value = "위치 서비스가 꺼져 있으면 에어팟을 못 찾을 수 있어요(설정 > 위치 ON)"
        }
        scanner = bt.bluetoothLeScanner
        cTotal = 0; cApple = 0; cPods = 0
        c07 = 0; last07Hex = ""; typeCounts.clear()
        // 화면 ON: 필터 없는 전체 스캔 — 실기기에서 0x07 수신이 확인된 방식.
        //   (회사 ID만 거는 빈 데이터 필터는 일부 폰(삼성 등)의 하드웨어 필터에서 아무것도 안 걸러져 배터리를 못 받음)
        // 화면 OFF: 안드로이드 8.1+는 필터 없는 스캔을 멈추므로, 그때만 애플 회사 ID 필터로 시도(보조).
        scanAllMode = isScreenOn()
        val filters = if (scanAllMode) null else listOf(
            ScanFilter.Builder().setManufacturerData(AirPodsParser.appleId(), byteArrayOf()).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L).build()
        try {
            val s = scanner ?: run { PodsRepo.status.value = "BLE 스캐너를 사용할 수 없어요"; return }
            s.startScan(filters, settings, callback)
            scanning = true
            PodsRepo.status.value = "에어팟 검색 중… 케이스 뚜껑을 열어주세요"
        } catch (e: Exception) {
            PodsRepo.status.value = "스캔 시작 실패: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try { scanner?.stopScan(callback) } catch (_: Exception) {}
        scanning = false
    }

    /** 시스템 위치 서비스 ON 여부 (30 이하에서 BLE 스캔 결과 수신의 전제 조건). */
    private fun locationEnabled(): Boolean = try {
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) { true }

    override fun onDestroy() {
        super.onDestroy()
        PodsRepo.running.value = false
        PodsRepo.wearSignal.value = null
        btReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        btReceiver = null
        screenReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        screenReceiver = null
        handler.removeCallbacksAndMessages(null)
        abandonResume()   // 보유 중인 오디오 포커스 반환
        overlay.hide()
        stopScan()
    }

    private fun notifyCurrent() {
        try { notifyRaw(buildNotification(PodsRepo.pods.value)) } catch (_: Exception) {}
    }

    private fun notify(pods: Pods) = notifyRaw(buildNotification(pods))

    private fun notifyRaw(n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun buildNotification(pods: Pods?): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "AosPods", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
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

    /** N-02 표시 기준에 따라 알림에 쓸 한 줄. 값이 없으면 "--". */
    private fun basisLine(p: Pods): String {
        fun v(x: Int) = if (x > 0) "$x%" else "--"
        return when (prefs.percentType) {
            Prefs.PERCENT_TYPES[1] -> "왼쪽 ${v(p.left)}"
            Prefs.PERCENT_TYPES[2] -> "오른쪽 ${v(p.right)}"
            Prefs.PERCENT_TYPES[3] -> "케이스 ${v(p.case)}"
            else -> {
                val vals = listOf(p.left, p.right, p.case).filter { it > 0 }
                if (vals.isEmpty()) "연결됨" else "가장 낮음 ${vals.min()}%"
            }
        }
    }

    companion object {
        private const val CHANNEL = "aospods"
        private const val NOTIF_ID = 1
        /** 연결 후 이 시간 동안 0x07이 한 건도 없으면 '착용 신호 없음'으로 표시. */
        private const val WEAR_SIGNAL_WINDOW_MS = 30_000L

        fun batteryLine(p: Pods, sep: String = "  "): String = buildList {
            if (p.left > 0) add("왼쪽 ${p.left}%")
            if (p.right > 0) add("오른쪽 ${p.right}%")
            if (p.case > 0) add("케이스 ${p.case}%")
        }.joinToString(sep)

        /** 서비스 시작. 백그라운드 시작 제한 등으로 실패하면 false (앱이 죽지 않게). */
        fun start(ctx: Context): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ScanService::class.java)); true
        } catch (e: Exception) {
            PodsRepo.status.value = "서비스를 시작하지 못했어요: ${e.message ?: e}"; false
        }

        /** 서비스가 켜져 있으면 설정 변경을 알림·팝업에 즉시 반영. */
        fun applySettings(ctx: Context) {
            if (Prefs(ctx).serviceEnabled && Perms.ok(ctx)) start(ctx)
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, ScanService::class.java))
    }
}
