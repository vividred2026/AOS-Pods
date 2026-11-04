package pro.aospods.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pro.aospods.app.Sheets.button
import pro.aospods.app.Sheets.buttons
import pro.aospods.app.Sheets.link
import pro.aospods.app.Sheets.text
import pro.aospods.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (prefs.serviceEnabled && runtimePermsOk()) ScanService.start(this)
        else if (prefs.serviceEnabled) {
            prefs.serviceEnabled = false; b.swService.isChecked = false
            toast("서비스를 켜려면 블루투스·알림 권한이 필요합니다.")
        }
        // '다시 묻지 않음'으로 거부되면 요청 창이 더 뜨지 않으므로 앱 설정으로 안내
        val blocked = missingRuntime().filter { !shouldShowRequestPermissionRationale(it) }
        if (blocked.isNotEmpty() && blocked.size == missingRuntime().size) openAppSettingsDialog()
    }

    private fun openAppSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("권한이 거부되어 있어요")
            .setMessage("앱 설정 > 권한에서 블루투스(주변 기기)·알림 권한을 허용해 주세요.")
            .setPositiveButton("앱 설정 열기") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            }
            .setNegativeButton("닫기", null).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.swService.isChecked = prefs.serviceEnabled
        b.swNotif.isChecked = prefs.notification
        b.swPercent.isChecked = prefs.percentInIcon
        b.swPopup.isChecked = prefs.popup
        b.swPause.isChecked = prefs.autoPause
        b.swResume.isChecked = prefs.autoResume
        renderModel()
        lifecycleScope.launch { PodsRepo.modelVersion.collect { renderModel() } }
        b.nightValue.text = nightLabel()
        b.footerVersion.text = "aosPods ${BuildConfig.VERSION_NAME} · 빌드 ${BuildConfig.BUILD_ID}"

        b.swService.setOnCheckedChangeListener { _, on ->
            prefs.serviceEnabled = on
            if (on) {
                if (!runtimePermsOk()) requestRuntimePerms()
                else if (!ScanService.start(this)) { prefs.serviceEnabled = false; b.swService.isChecked = false }
            }
            else { ScanService.stop(this); PodsRepo.update(null) }
            OnOffTileService.refresh(this)   // 빠른 설정 타일 상태 동기화
        }
        b.swNotif.setOnCheckedChangeListener { _, on -> prefs.notification = on; applyNow() }
        b.swPercent.setOnCheckedChangeListener { _, on -> prefs.percentInIcon = on; applyNow() }
        b.swPopup.setOnCheckedChangeListener { _, on -> prefs.popup = on; applyNow() }
        b.swPause.setOnCheckedChangeListener { _, on -> prefs.autoPause = on }
        b.swResume.setOnCheckedChangeListener { _, on -> prefs.autoResume = on }

        // 텍스트 → 각 하위 설정 페이지
        b.textNotif.setOnClickListener { open(NotifSettingsActivity::class.java) }
        b.textPercent.setOnClickListener { open(PercentSettingsActivity::class.java) }
        b.textPopup.setOnClickListener { open(PopupSettingsActivity::class.java) }

        b.rowModel.setOnClickListener { showModelPicker() }
        b.rowNight.setOnClickListener { showNightPicker() }
        b.rowPrivacy.setOnClickListener { showPrivacy() }

        // 에어팟 연결 카드 (Figma M-00): 연결 전 = 안내 + [연결] / 연결 후 = 이름 - 모델 번호 · R/L/C 배터리
        b.btnPair.setOnClickListener { pairGuide() }
        b.pairDisconnected.setOnClickListener { pairGuide() }
        // 연결 후: 탭 = 에어팟 정보(전용 채널 연결 시), 아니면 다시 연결 / 길게 = 다시 연결
        b.pairConnected.setOnClickListener {
            if (AapSession.connected.value) open(InfoActivity::class.java) else pairGuide()
        }
        b.pairConnected.setOnLongClickListener { pairGuide(); true }
        b.podR.podLetter.text = "R"; b.podL.podLetter.text = "L"; b.podC.podLetter.text = "C"
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                BtState.connectedName, BtState.hfpBattery, PodsRepo.pods, AapSession.info, PodsRepo.running
            ) { n, h, p, i, r -> PairUi(n, h, p, i?.modelNumber, r) }
                .collect { renderPairCard(it) }
        }
        // 청취 모드: AACP 연결에 성공했을 때만 메뉴 표시
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(AapSession.connected, AapSession.listenMode) { c, m -> c to m }
                .collect { (c, m) ->
                    b.listenSection.visibility = if (c) View.VISIBLE else View.GONE
                    b.listenValue.text = Aap.modeName(m)
                }
        }
        b.rowListen.setOnClickListener { showListenModePicker() }

        // 착용 감지: 연결 중인데 에어팟이 착용 신호(0x07)를 보내지 않으면 솔직하게 안내
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                PodsRepo.wearSignal, PodsRepo.running, AapSession.connected
            ) { w, r, a -> Triple(w, r, a) }
                .collect { (w, r, a) ->
                    b.wearStatus.text = when {
                        !r -> "서비스를 켜면 착용 신호를 확인해요"
                        a -> "실시간 착용 감지 사용 중 (에어팟 전용 채널)"
                        w == true -> "착용 신호 수신 중"
                        w == false -> "에어팟이 착용 신호를 보내지 않고 있어요 — " +
                            (if (BuildConfig.AACP) "이 폰·모델에서는 착용 감지가 동작하지 않을 수 있어요"
                             else "플레이 버전에서는 이 폰·모델의 착용 감지가 안 될 수 있어요(전체 버전은 가능)")
                        else -> "에어팟 연결 후 착용 신호를 확인해요"
                    }
                }
        }
        showCrashIfAny()

        lifecycleScope.launch {
            PodsRepo.error.collect { msg ->
                if (msg != null) {
                    toast(msg); prefs.serviceEnabled = false; b.swService.isChecked = false
                    PodsRepo.error.value = null
                }
            }
        }
        lifecycleScope.launch {
            PodsRepo.status.collect { msg ->          // 비치명적 안내(스캔 상태 등)
                if (msg != null) { toast(msg); PodsRepo.status.value = null }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.serviceEnabled && runtimePermsOk()) ScanService.start(this)
    }

    /** 직전 비정상 종료 로그가 있으면 대화상자로 보여주고, 복사할 수 있게 한다. */
    private fun showCrashIfAny() {
        val f = AosApp.crashFile(this)
        if (!f.exists()) return
        val text = try { f.readText() } catch (_: Exception) { return }
        AlertDialog.Builder(this)
            .setTitle("이전 비정상 종료 로그")
            .setMessage(text.take(4000))
            .setPositiveButton("확인") { _, _ -> f.delete() }
            .setNeutralButton("복사") { _, _ ->
                try {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                    toast("크래시 로그를 복사했습니다")
                } catch (_: Exception) {}
                f.delete()
            }
            .setCancelable(false)
            .show()
    }

    private fun pct(v: Int) = if (v > 0) "$v%" else "--"

    private data class PairUi(val name: String?, val hfp: Int?, val pods: Pods?, val modelNumber: String?, val running: Boolean)

    private fun renderPairCard(u: PairUi) {
        val connected = u.name != null
        b.pairDisconnected.visibility = if (connected) View.GONE else View.VISIBLE
        b.pairConnected.visibility = if (connected) View.VISIBLE else View.GONE
        if (!connected) {
            b.pairStatus.text = "연결 안 됨 | 버튼을 눌러 에어팟과 페어링"
            return
        }
        // "이름(주황) - 모델 번호(회색)" — 모델 번호는 에어팟이 알려줬을 때만
        val title = android.text.SpannableStringBuilder(u.name).apply {
            setSpan(android.text.style.ForegroundColorSpan(ContextCompat.getColor(this@MainActivity, R.color.accent)),
                0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (!u.modelNumber.isNullOrBlank()) append(" - ${u.modelNumber}")
        }
        b.pairName.text = title
        val p = u.pods
        if (p != null && p.hasAny) {
            b.podsRow.visibility = View.VISIBLE
            b.podR.podPct.text = pct(p.right) + if (p.rightCharging) "⚡" else ""
            b.podL.podPct.text = pct(p.left) + if (p.leftCharging) "⚡" else ""
            b.podC.podPct.text = pct(p.case) + if (p.caseCharging) "⚡" else ""
            b.pairDetail.visibility = View.GONE
        } else {
            // 좌·우·케이스 값이 아직 없으면 칩 대신 안내(가짜 값 표시 안 함)
            b.podsRow.visibility = View.GONE
            b.pairDetail.visibility = View.VISIBLE
            b.pairDetail.text = if (!u.running) "배터리를 보려면 아래 '서비스 활성화'를 켜 주세요"   // 배터리·착용은 서비스가 받아 화면에 넘김
                else if (u.hfp != null) "전체 배터리 ${u.hfp}% · 케이스 뚜껑을 열면 R·L·C 표시"
                else if (BuildConfig.AACP) "배터리 확인 중 · 케이스 뚜껑을 열면 R·L·C 표시"
                else "배터리 확인 중 · 에어팟을 다시 연결하거나 케이스 뚜껑을 열어 보세요 (플레이 버전은 일부 폰·모델에서 배터리를 못 받을 수 있어요)"
        }
    }

    /** 설정 변경을 다음 신호를 기다리지 않고 알림·팝업에 즉시 반영. */
    private fun applyNow() = ScanService.applySettings(this)

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    private fun copy(label: String, text: String, done: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        toast(done)
    }
    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    // --- 권한 ---

    private fun missingRuntime() = Perms.missing(this)
    private fun runtimePermsOk() = Perms.ok(this)
    private fun requestRuntimePerms() = permLauncher.launch(missingRuntime().toTypedArray())



    // --- 다이얼로그 ---

    private fun showModelPicker() {
        val models = Prefs.MODELS
        Sheets.choice(this, "제품 선택", models, models.indexOf(prefs.model)) { w ->
            prefs.model = models[w]; prefs.modelAuto = false; renderModel()
        }
    }

    /** 모델 행 요약: 자동으로 맞춘 값이면 출처를 함께 표시. */
    private fun renderModel() {
        b.modelValue.text = if (prefs.modelAuto) "${prefs.model} · 연결된 에어팟에서 확인" else prefs.model
    }

    private fun nightLabel() =
        Prefs.NIGHT_LABELS[Prefs.NIGHT_VALUES.indexOf(prefs.nightMode).coerceAtLeast(0)]

    private fun showNightPicker() {
        val labels = Prefs.NIGHT_LABELS
        Sheets.choice(this, "다크 모드", labels, Prefs.NIGHT_VALUES.indexOf(prefs.nightMode)) { w ->
            prefs.nightMode = Prefs.NIGHT_VALUES[w]; b.nightValue.text = labels[w]
            AppCompatDelegate.setDefaultNightMode(prefs.nightMode)
        }
    }

    // ---------- 에어팟 페어링 ----------

    private var btUiReceiver: android.content.BroadcastReceiver? = null

    override fun onStart() {
        super.onStart()
        BtState.refresh(this)
        val r = BtState.receiver { _, _ -> }
        btUiReceiver = r
        androidx.core.content.ContextCompat.registerReceiver(
            this, r, BtState.filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        stopDiscovery(); stopBondWatch()
        btUiReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        btUiReceiver = null
    }

    /** 페어링 모드 진입 방법 안내 시트(Figma `1230:2253`) → 기기 선택 시트. */
    private fun pairGuide() {
        if (!runtimePermsOk()) { requestRuntimePerms(); toast("먼저 블루투스 권한을 허용해 주세요"); return }
        val s = Sheets.base(this, "에어팟 페어링모드").link("블루투스 설정") { openBtSettings() }
        val ctx = s.dialog.context
        listOf(
            "에어팟을 케이스에 넣고 뚜껑을 연 채 폰 옆에 두세요.",
            "케이스의 설정 버튼을 상태 표시등이 흰색으로 깜빡일 때까지 길게 누르세요. " +
                "(버튼이 없는 에어팟 4는 케이스 앞면 표시등 부근을 두 번 톡톡)",
            "깜빡이면 [다음]을 누르고, 목록에서 에어팟을 선택하세요.",
        ).forEachIndexed { i, line ->
            s.b.sheetBody.addView(sheetText(ctx, bullet(line), 14f, R.color.ink).apply {
                if (i > 0) layoutParams = marginTop(10)
            })
        }
        s.b.sheetBody.addView(sheetText(ctx,
            "이름을 변경한 에어팟은 변경된 이름으로 표기되며, 검색은 10초 정도 소요됩니다.", 14f, R.color.ink).apply {
            setPadding(dp(20), dp(10), 0, dp(10))
            layoutParams = marginTop(10)
        })
        s.buttons("취소", { s.dialog.dismiss() }, "다음", { s.dialog.dismiss(); startOwnDiscovery() })
        s.dialog.show()
    }

    private fun bullet(t: String) = android.text.SpannableString(t).apply {
        setSpan(android.text.style.BulletSpan(dp(10)), 0, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun sheetText(ctx: android.content.Context, t: CharSequence, size: Float, color: Int) =
        android.widget.TextView(ctx).apply {
            text = t; textSize = size
            setTextColor(ctx.getColor(color))
            setLineSpacing(0f, 1.2f)
        }

    private fun marginTop(v: Int) = android.widget.LinearLayout.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(v) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- 앱 자체 기기 검색 (이름 있는 오디오 기기만, MAC 주소 비노출) ----------

    /** cat: 0=에어팟/비츠로 보임, 1=오디오 기기, 2=기타 */
    private data class Found(
        val dev: android.bluetooth.BluetoothDevice, val name: String,
        val cat: Int, val bonded: Boolean,
    )

    private val found = linkedMapOf<String, Found>()          // key = 주소(화면에는 안 보임)
    private val seenAll = mutableSetOf<String>()               // 진단: 이름 유무 상관없이 발견된 기기
    private var pickSheet: Sheets.Sheet? = null
    private var pickHead: android.widget.TextView? = null
    private var pickList: android.widget.LinearLayout? = null
    /** 목록에 지금 보이는 순서 그대로(누른 항목과 실제 기기가 어긋나지 않게). */
    private var shown: List<Found> = emptyList()
    private var discoveryReceiver: android.content.BroadcastReceiver? = null

    @android.annotation.SuppressLint("MissingPermission")
    private fun isAudio(d: android.bluetooth.BluetoothDevice, cls: android.bluetooth.BluetoothClass?): Boolean {
        val c = cls ?: try { d.bluetoothClass } catch (_: Exception) { null }
        return c?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO
    }

    private fun looksLikePods(name: String) =
        Regex("(?i)airpods|beats|powerbeats|에어팟").containsMatchIn(name)

    private fun category(d: android.bluetooth.BluetoothDevice, name: String, cls: android.bluetooth.BluetoothClass?) =
        when { looksLikePods(name) -> 0; isAudio(d, cls) -> 1; else -> 2 }

    /** 주변 블루투스 기기를 직접 검색. 이름이 확인된 기기만 이름으로 표시(MAC 비노출). */
    @android.annotation.SuppressLint("MissingPermission")
    private fun startOwnDiscovery() {
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (adapter == null) { toast("이 기기는 블루투스를 지원하지 않아요"); return }
        if (!adapter.isEnabled) {
            toast("블루투스를 켠 뒤 다시 [연결하기]를 눌러주세요")
            try { startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            catch (_: Exception) { openBtSettings() }
            return
        }
        stopDiscovery()
        found.clear(); seenAll.clear()
        // 이미 페어링된 기기도 함께 표시(에어팟을 예전에 페어링했다면 여기서 바로 고를 수 있게)
        try {
            adapter.bondedDevices?.forEach { d ->
                val n = try { d.name } catch (_: Exception) { null }
                if (!n.isNullOrBlank()) found[d.address] = Found(d, n, category(d, n, null), true)
            }
        } catch (_: Exception) {}

        // 기기 선택 시트(Figma `1230:2480`): 안내 줄 + 이름 목록(MAC 비노출) + [취소][다시 검색]
        val sheet = Sheets.base(this, "에어팟 페어링모드").link("블루투스 설정") { stopDiscovery(); openBtSettings() }
        val ctx = sheet.dialog.context
        val head = sheetText(ctx, "에어팟 찾는 중… (약 12초)", 16f, R.color.ink).apply {
            typeface = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.pretendard_bold)
        }
        val list = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        sheet.b.sheetBody.addView(head)
        sheet.b.sheetBody.addView(list, marginTop(10))
        sheet.b.sheetScroll.layoutParams = sheet.b.sheetScroll.layoutParams.apply {
            height = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        }
        sheet.buttons("취소", { sheet.dialog.dismiss() }, "다시 검색", { startOwnDiscovery() })
        // 이 시트가 닫힐 때만 검색 중지(다시 검색으로 새 시트가 열린 뒤 옛 시트의 닫힘이 새 검색을 끊지 않게)
        sheet.dialog.setOnDismissListener { if (pickSheet === sheet) { stopDiscovery(); pickSheet = null } }
        pickSheet?.dialog?.dismiss()
        pickSheet = sheet; pickHead = head; pickList = list
        sheet.dialog.show()
        refreshPickList()

        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) {
                when (i.action) {
                    android.bluetooth.BluetoothDevice.ACTION_FOUND,
                    android.bluetooth.BluetoothDevice.ACTION_NAME_CHANGED -> {
                        @Suppress("DEPRECATION")
                        val d: android.bluetooth.BluetoothDevice = i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE) ?: return
                        seenAll += d.address
                        @Suppress("DEPRECATION")
                        val cls: android.bluetooth.BluetoothClass? = i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_CLASS)
                        val name = i.getStringExtra(android.bluetooth.BluetoothDevice.EXTRA_NAME)
                            ?: try { d.name } catch (_: Exception) { null }
                        if (name.isNullOrBlank()) return              // 이름 없는 기기는 숨김(MAC 비노출)
                        val bonded = try { d.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED } catch (_: Exception) { false }
                        found[d.address] = Found(d, name, category(d, name, cls), bonded)
                        refreshPickList()
                    }
                    android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        val named = found.values.count { !it.bonded }
                        pickHead?.text =
                            if (found.values.none { it.cat <= 1 })
                                "에어팟을 못 찾았어요 (발견 ${seenAll.size} · 이름 확인 $named)"
                            else countLine("연결할 에어팟을 선택하세요.", seenAll.size)
                    }
                }
            }
        }
        discoveryReceiver = r
        val f = android.content.IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_FOUND)
            addAction(android.bluetooth.BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        androidx.core.content.ContextCompat.registerReceiver(this, r, f, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            if (!adapter.startDiscovery()) pickHead?.text = "검색을 시작하지 못했어요 — 위치 서비스·권한을 확인해 주세요"
        } catch (e: Exception) { pickHead?.text = "검색 오류: ${e.message}" }
    }

    private fun sortedFound() = found.values.sortedWith(
        compareBy<Found> { it.cat }.thenByDescending { it.bonded }.thenBy { it.name }
    )

    /** "문구   +N" — 발견 수만 보라. */
    private fun countLine(t: String, n: Int): CharSequence {
        val tail = "+$n"
        return android.text.SpannableString("$t   $tail").apply {
            setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.accent)),
                length - tail.length, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun refreshPickList() {
        val list = pickList ?: return
        val sheet = pickSheet ?: return
        shown = sortedFound()
        list.removeAllViews()
        val ctx = list.context
        val bg = android.util.TypedValue().also {
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        shown.forEach { f ->
            val icon = when (f.cat) { 0 -> "🎧"; 1 -> "🔈"; else -> "·" }
            val row = sheetText(ctx, "$icon ${f.name}" + if (f.bonded) "  (페어링됨)" else "", 16f, R.color.ink).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                setPadding(dp(24), dp(12), dp(4), dp(12))
                setBackgroundResource(bg)
                setOnClickListener { sheet.dialog.dismiss(); stopDiscovery(); bond(f.dev) }
            }
            list.addView(row, marginTop(if (list.childCount == 0) 0 else 10))
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun stopDiscovery() {
        try { getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.cancelDiscovery() } catch (_: Exception) {}
        discoveryReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        discoveryReceiver = null
    }

    // ---------- 페어링 진행 (본딩 → 프로파일 연결) ----------

    private var bondReceiver: android.content.BroadcastReceiver? = null
    private var bondSheet: Sheets.Sheet? = null
    private var bondLog: android.widget.TextView? = null
    private val bondLines = StringBuilder()
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    private fun bondAppend(line: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())
        bondLines.append(t).append("  ").append(line).append('\n')
        bondLog?.text = bondLines.toString()
    }

    /** 선택한 에어팟과 페어링(본딩)하고, 끝나면 음악·통화 프로파일 연결까지 시도한다.
     *  설정 앱에서 페어링하면 시스템이 연결까지 해 주지만, 앱이 createBond()로 페어링하면
     *  본딩만 되고 연결은 안 되는 폰이 있어서(실기기 보고) 그 다음 단계를 직접 밟는다. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun bond(dev: android.bluetooth.BluetoothDevice) {
        val name = BtState.safeName(this, dev)
        stopBondWatch()
        bondLines.clear()
        val s = Sheets.base(this, "에어팟 연결 중").link("블루투스 설정") { openBtSettings() }
        bondLog = s.text("")
        s.buttons("복사", { copy("aospods-pair", bondLines.toString(), "진행 기록을 복사했습니다") },
            "닫기", { s.dialog.dismiss() })
        s.dialog.setOnDismissListener { if (bondSheet === s) { stopBondWatch(); bondSheet = null; bondLog = null } }
        bondSheet = s
        s.dialog.show()
        bondAppend("$name 선택 · 안드로이드 ${Build.VERSION.SDK_INT} · 빌드 ${BuildConfig.BUILD_ID}")

        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) {
                @Suppress("DEPRECATION")
                val d: android.bluetooth.BluetoothDevice = i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE) ?: return
                if (d.address != dev.address) return
                when (i.action) {
                    android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val st = i.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, -1)
                        bondAppend("페어링 상태: " + when (st) {
                            android.bluetooth.BluetoothDevice.BOND_BONDING -> "진행 중"
                            android.bluetooth.BluetoothDevice.BOND_BONDED -> "완료"
                            android.bluetooth.BluetoothDevice.BOND_NONE -> "안 됨(거부·취소·실패)"
                            else -> "$st"
                        })
                        if (st == android.bluetooth.BluetoothDevice.BOND_BONDED) afterBonded(dev)
                        if (st == android.bluetooth.BluetoothDevice.BOND_NONE)
                            bondAppend("→ 다시 시도하거나 [블루투스 설정]에서 페어링해 주세요")
                    }
                    android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                    android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                        val st = i.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, -1)
                        val which = if (i.action == android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) "음악" else "통화"
                        bondAppend("$which 프로파일: " + when (st) {
                            android.bluetooth.BluetoothProfile.STATE_CONNECTING -> "연결 중"
                            android.bluetooth.BluetoothProfile.STATE_CONNECTED -> "연결됨 ✅"
                            android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> "끊김"
                            else -> "$st"
                        })
                        if (st == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                            ui.removeCallbacksAndMessages(null)
                            bondAppend("연결 완료 — 잠시 후 배터리가 표시돼요")
                            BtState.refresh(this@MainActivity)
                        }
                    }
                }
            }
        }
        bondReceiver = r
        androidx.core.content.ContextCompat.registerReceiver(this, r, android.content.IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)

        try {
            if (dev.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED) {
                bondAppend("이미 페어링된 기기 → 연결만 시도")
                afterBonded(dev)
            } else if (dev.createBond()) {
                bondAppend("페어링 요청 보냄 — 화면에 요청이 뜨면 허용해 주세요")
            } else {
                bondAppend("페어링을 시작하지 못했어요(createBond=false) → [블루투스 설정]에서 페어링해 주세요")
            }
        } catch (e: Exception) {
            bondAppend("페어링 오류: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** 본딩 뒤: 시스템이 알아서 연결하는지 잠시 기다리고, 안 되면 숨김 API connect()로 직접 연결 시도. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun afterBonded(dev: android.bluetooth.BluetoothDevice) {
        ui.removeCallbacksAndMessages(null)
        bondAppend("시스템 자동 연결 기다리는 중(3초)…")
        ui.postDelayed({ if (!isA2dpConnected(dev)) connectProfiles(dev) else bondAppend("음악 프로파일 이미 연결됨 ✅") }, 3000)
        ui.postDelayed({
            if (!isA2dpConnected(dev)) bondAppend("아직 연결되지 않았어요 → [블루투스 설정]에서 에어팟을 눌러 연결해 주세요. " +
                "이 기록을 [복사]해 보내 주시면 원인을 확인할 수 있어요")
        }, 12000)
    }

    private fun isA2dpConnected(dev: android.bluetooth.BluetoothDevice): Boolean =
        try { BtState.connectedDevice?.address == dev.address } catch (_: Exception) { false }

    /** BluetoothA2dp/BluetoothHeadset.connect(device) — 숨김 API. 12+에선 권한(BLUETOOTH_PRIVILEGED) 때문에 거부될 수 있어 결과를 기록만 함. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun connectProfiles(dev: android.bluetooth.BluetoothDevice) {
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return
        HiddenApi.allowBluetooth()
        for ((profile, label) in listOf(android.bluetooth.BluetoothProfile.A2DP to "음악", android.bluetooth.BluetoothProfile.HEADSET to "통화")) {
            try {
                adapter.getProfileProxy(applicationContext, object : android.bluetooth.BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: android.bluetooth.BluetoothProfile) {
                        val res = try {
                            val m = proxy.javaClass.getMethod("connect", android.bluetooth.BluetoothDevice::class.java)
                            "connect() → ${m.invoke(proxy, dev)}"
                        } catch (e: Throwable) {
                            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                            "connect() 불가: ${cause.javaClass.simpleName} ${cause.message ?: ""}".trim()
                        }
                        ui.post { bondAppend("$label 프로파일 직접 연결: $res") }
                        try { adapter.closeProfileProxy(p, proxy) } catch (_: Exception) {}
                    }
                    override fun onServiceDisconnected(p: Int) {}
                }, profile)
            } catch (e: Exception) { bondAppend("$label 프로파일 접근 실패: ${e.message}") }
        }
    }

    private fun stopBondWatch() {
        ui.removeCallbacksAndMessages(null)
        bondReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        bondReceiver = null
    }

    private fun openBtSettings() {
        try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {}
    }

    /** 마지막 에어팟 패킷 원문과 글자 위치별 해석값을 보여주고 복사할 수 있게 함. */
    private fun showDiag() {
        // 진단을 보는 동안은 빠른 스캔(저전력 모드에선 광고를 놓쳐 "07 0"으로 보일 수 있음) — 5초 주기 재평가에서 반영
        PodsRepo.boostUntil = System.currentTimeMillis() + 90_000
        val hex = PodsRepo.lastRawHex
        val recent = synchronized(PodsRepo.recentRaw) { PodsRepo.recentRaw.toList() }
        val head = "연결: ${BtState.connectedName.value ?: "없음"} · HFP 배터리: ${BtState.hfpBattery.value?.let { "$it%" } ?: "없음"}\n" +
            "배터리 경로: ${BtState.batteryNote} · 숨김 API: ${HiddenApi.result}\n" +
            "안드로이드 ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL} · 앱 빌드 ${BuildConfig.BUILD_ID} (${BuildConfig.FLAVOR} 갈래 · ${BuildConfig.VERSION_NAME})\n" +
            PodsRepo.diagLine + "\n\n"
        val text = head + if (hex.isEmpty()) {
            "아직 받은 에어팟 배터리 신호(07)가 없습니다.\n케이스를 폰 옆에서 열고 잠시 후 다시 확인하세요."
        } else buildString {
            val ago = (System.currentTimeMillis() - PodsRepo.lastRawAt) / 1000
            appendLine("원문(0x07): $hex")
            appendLine("수신 ${PodsRepo.rawCount}건 · ${ago}초 전 · RSSI ${PodsRepo.lastRssi}")
            appendLine()
            fun c(i: Int) = hex.getOrNull(i)?.toString() ?: "?"
            appendLine("s[7]=${c(7)}  s[10]=${c(10)}  s[11]=${c(11)}")
            appendLine("s[12]=${c(12)}  s[13]=${c(13)}  s[14]=${c(14)}  s[15]=${c(15)}")
            val p = AirPodsParser.parse(
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), prefs.model
            )
            if (p != null) {
                appendLine()
                appendLine("해석: 왼쪽 ${p.left}% · 오른쪽 ${p.right}% · 케이스 ${p.case}%")
                appendLine("충전: 왼${p.leftCharging} 오${p.rightCharging} 케${p.caseCharging}")
                appendLine("착용: 왼${p.leftInEar} 오${p.rightInEar}")
                appendLine("(0% = 값 없음, 원문 글자가 F)")
            }
            if (recent.size > 1) {
                appendLine()
                appendLine("최근 서로 다른 원문(RSSI 원문):")
                recent.forEach { appendLine(it) }
            }
            val dec = synchronized(PodsRepo.recentDecrypted) { PodsRepo.recentDecrypted.toList() }
            appendLine()
            appendLine("암호화 광고 복호화(실험): ${PodsRepo.bleKeyNote}")
            dec.forEach { appendLine(it) }
        }
        // 진단정보 시트 (Figma 1216:5169): 오른쪽 위 [AACP 테스트] · 하단 [복사] [닫기]
        val s = Sheets.base(this, "진단 정보").link("AACP 테스트") { showAapExperiment() }
        s.text(text)
        s.buttons("복사", { copy("aospods", text, "진단 정보를 복사했습니다") }, "닫기", { s.dialog.dismiss() })
        s.dialog.show()
    }

    /** 실험: 에어팟 전용 채널을 60초 끊고 빠른 스캔으로 암호화 광고를 모아 저장된 키로 복호화(결과는 진단 정보 맨 아래).
     *  AACP가 열려 있는 동안엔 에어팟이 근접 광고를 보내지 않아(실기기 확인) 이 방법으로만 광고를 받을 수 있다. */
    private fun startAdTest() {
        val now = System.currentTimeMillis()
        PodsRepo.aapPauseUntil = now + 60_000
        PodsRepo.boostUntil = now + 75_000
        if (!PodsRepo.running.value) { toast("서비스를 먼저 켜 주세요"); return }
        toast("60초 동안 전용 채널을 끊고 광고를 모아요 — 케이스 뚜껑을 열어 두세요. 끝나면 자동으로 다시 연결돼요")
    }

    /** 청취 모드 선택 → 에어팟에 전송. 2초 안에 에어팟이 같은 모드로 확인해 주지 않으면 미지원 안내. */
    private fun showListenModePicker() {
        val modes = Aap.MODES
        val labels = modes.map { Aap.modeName(it) }
        Sheets.choice(this, "청취 모드", labels, modes.indexOf(AapSession.listenMode.value)) { w ->
            val want = modes[w]
            if (!AapSession.setListenMode(want)) { toast("에어팟과 연결이 끊겨 바꾸지 못했어요"); return@choice }
            b.root.postDelayed({
                if (AapSession.connected.value && AapSession.listenMode.value != want)
                    toast("에어팟이 '${labels[w]}'을(를) 확인해 주지 않았어요 — 이 모델이나 설정에서는 지원되지 않을 수 있어요")
            }, 2000)
        }
    }

    /** AACP 연결 상태·로그 확인. 연결돼 있지 않으면 지금 다시 시도한다(실패 원인이 로그에 남음).
     *  열리면 실시간 착용·배터리·청취 모드 메시지가 보인다. 결과는 복사해서 공유할 수 있다. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun showAapExperiment() {
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        val dev = BtState.connectedDevice
        if (adapter == null || !adapter.isEnabled) { toast("블루투스를 켜 주세요"); return }
        if (dev == null) { toast("에어팟을 먼저 연결해 주세요"); return }
        if (!runtimePermsOk()) { requestRuntimePerms(); return }

        val head = "안드로이드 ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.ID} · 앱 빌드 ${BuildConfig.BUILD_ID} (${BuildConfig.FLAVOR} 갈래)\n"
        // AACP 연결 테스트 시트 (Figma 1216:5303): 실시간 로그 · [복사] [닫기]
        val s = Sheets.base(this, "AACP 연결 테스트")
        val logView = s.text("")
        logView.textSize = 12f
        val job = lifecycleScope.launch {
            AapSession.log.collect {
                logView.text = head + it
                s.b.sheetScroll.post { s.b.sheetScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        s.buttons("복사", { copy("aospods-aacp", head + AapSession.log.value, "AACP 로그를 복사했습니다") },
            "닫기", { s.dialog.dismiss() })
        s.dialog.setOnDismissListener { job.cancel() }
        s.dialog.show()
        if (!PodsRepo.running.value) toast("서비스가 꺼져 있어요 — 배터리·착용을 화면에 보이려면 '서비스 활성화'를 켜 주세요")
        if (!AapSession.connected.value) AapSession.start(this, adapter, dev, force = true)
    }

    private fun showPrivacy() {
        // 하단 시트(Figma `1230:2896`): 제목 + ✕ · 본문 · [확인]
        val s = Sheets.base(this, "개인정보 처리방침")
        val ctx = s.dialog.context
        s.b.sheetBody.addView(sheetText(ctx,
            "aosPods는 AirPods가 주변에 보내는 블루투스 신호만 읽어 배터리 상태를 " +
            "표시합니다. 위치·배터리 정보를 수집하거나 외부로 전송하지 않으며, " +
            "모든 처리는 기기 안에서만 이루어집니다.", 14f, R.color.ink))
        // 진단 정보 행(2026-11-04 메인 목록에서 이 시트로 이동): 구분선 · 톱니 32 · "HW 진단 정보"(16 Medium 보라 밑줄) + 요약 13
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        s.b.sheetBody.addView(View(ctx).apply { setBackgroundColor(ctx.getColor(R.color.line)) },
            android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(20) })
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            isClickable = true; isFocusable = true
            val tv = android.util.TypedValue(); ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { s.dialog.dismiss(); showDiag() }
        }
        row.addView(android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_settings); contentDescription = "진단"
        }, android.widget.LinearLayout.LayoutParams(dp(32), dp(32)))
        val col = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        col.addView(android.widget.TextView(ctx).apply {
            text = "HW 진단 정보"; textSize = 16f; typeface = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.pretendard_medium)
            setTextColor(ctx.getColor(R.color.accent)); paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            letterSpacing = -0.019f; minHeight = dp(22); gravity = android.view.Gravity.CENTER_VERTICAL
        })
        col.addView(android.widget.TextView(ctx).apply {
            text = "마지막 에어팟 신호 원문 · 해석값 확인/복사"; textSize = 13f
            setTextColor(ctx.getColor(R.color.muted)); letterSpacing = -0.015f; minHeight = dp(18); gravity = android.view.Gravity.CENTER_VERTICAL
        }, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        row.addView(col, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        s.b.sheetBody.addView(row, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(26) })
        s.button("확인") { s.dialog.dismiss() }
        s.dialog.show()
    }
}
