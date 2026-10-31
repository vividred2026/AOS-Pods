package pro.aospods.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import pro.aospods.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissionUi()
        if (prefs.serviceEnabled && runtimePermsOk()) ScanService.start(this)
        else if (prefs.serviceEnabled) {
            prefs.serviceEnabled = false; b.swService.isChecked = false
            toast("서비스를 켜려면 블루투스·알림 권한이 필요합니다.")
        }
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPermissionUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(prefs.nightMode)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.swService.isChecked = prefs.serviceEnabled
        b.swNotif.isChecked = prefs.notification
        b.swPercent.isChecked = prefs.percentInIcon
        b.swPopup.isChecked = prefs.popup
        b.swPause.isChecked = prefs.autoPause
        b.swResume.isChecked = prefs.autoResume
        b.modelValue.text = prefs.model
        b.nightValue.text = nightLabel()

        b.swService.setOnCheckedChangeListener { _, on ->
            prefs.serviceEnabled = on
            if (on) { if (runtimePermsOk()) ScanService.start(this) else requestRuntimePerms() }
            else { ScanService.stop(this); PodsRepo.update(null) }
            OnOffTileService.refresh(this)   // 빠른 설정 타일 상태 동기화
        }
        b.swNotif.setOnCheckedChangeListener { _, on -> prefs.notification = on; applyNow() }
        b.swPercent.setOnCheckedChangeListener { _, on -> prefs.percentInIcon = on; applyNow() }
        b.swPopup.setOnCheckedChangeListener { _, on -> prefs.popup = on; refreshPermissionUi(); applyNow() }
        b.swPause.setOnCheckedChangeListener { _, on -> prefs.autoPause = on }
        b.swResume.setOnCheckedChangeListener { _, on -> prefs.autoResume = on }

        // 텍스트 → 각 하위 설정 페이지
        b.textNotif.setOnClickListener { open(NotifSettingsActivity::class.java) }
        b.textPercent.setOnClickListener { open(PercentSettingsActivity::class.java) }
        b.textPopup.setOnClickListener { open(PopupSettingsActivity::class.java) }

        b.rowModel.setOnClickListener { showModelPicker() }
        b.rowNight.setOnClickListener { showNightPicker() }
        b.rowPrivacy.setOnClickListener { showPrivacy() }
        b.rowDiag.setOnClickListener { showDiag() }

        // 에어팟 연결(페어링)
        b.btnPair.setOnClickListener { pairGuide() }
        b.rowPair.setOnClickListener { pairGuide() }
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(BtState.connectedName, BtState.hfpBattery, PodsRepo.pods) { n, h, p -> Triple(n, h, p) }
                .collect { (n, h, p) ->
                    b.pairStatus.text = when {
                        n == null -> "연결 안 됨 — 눌러서 에어팟과 페어링"
                        p != null && p.hasAny -> "연결됨 · $n · 왼 ${pct(p.left)} 오 ${pct(p.right)} 케이스 ${pct(p.case)}"
                        h != null -> "연결됨 · $n · 배터리 $h%"
                        else -> "연결됨 · $n · 배터리 확인 중"
                    }
                    b.btnPair.text = if (n == null) "연결하기" else "다시 연결"
                }
        }
        b.btnPermLoc.setOnClickListener { requestRuntimePerms() }
        b.btnPermBt.setOnClickListener { requestRuntimePerms() }
        b.btnPermNotif.setOnClickListener { requestRuntimePerms() }
        b.btnPermOverlay.setOnClickListener { requestOverlay() }

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
        refreshPermissionUi()
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

    /** 설정 변경을 다음 신호를 기다리지 않고 알림·팝업에 즉시 반영. */
    private fun applyNow() { if (prefs.serviceEnabled && runtimePermsOk()) ScanService.start(this) }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))
    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    // --- 권한 ---

    private fun runtimePerms(): Array<String> {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    private fun missingRuntime() = runtimePerms().filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }
    private fun runtimePermsOk() = missingRuntime().isEmpty()
    private fun requestRuntimePerms() = permLauncher.launch(missingRuntime().toTypedArray())

    private fun canOverlay() =
        if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(this) else true

    private fun requestOverlay() {
        overlayLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun refreshPermissionUi() {
        val missing = missingRuntime()
        val locMissing = missing.contains(Manifest.permission.ACCESS_FINE_LOCATION)
        val btMissing = missing.any {
            it == Manifest.permission.BLUETOOTH_SCAN || it == Manifest.permission.BLUETOOTH_CONNECT
        }
        val notifMissing = missing.contains(Manifest.permission.POST_NOTIFICATIONS)
        // "다른 앱 위에 표시"는 팝업 기능을 켠 경우에만 요청
        val overlayMissing = prefs.popup && !canOverlay()
        b.btnPermOverlay.visibility = if (overlayMissing) View.VISIBLE else View.GONE
        b.btnPermLoc.visibility = if (locMissing) View.VISIBLE else View.GONE
        b.btnPermBt.visibility = if (btMissing) View.VISIBLE else View.GONE
        b.btnPermNotif.visibility = if (notifMissing) View.VISIBLE else View.GONE
        b.permCard.visibility =
            if (locMissing || btMissing || notifMissing || overlayMissing)
                View.VISIBLE else View.GONE
    }

    // --- 다이얼로그 ---

    private fun showModelPicker() {
        val models = Prefs.MODELS.toTypedArray()
        AlertDialog.Builder(this).setTitle("모델")
            .setSingleChoiceItems(models, models.indexOf(prefs.model)) { d, w ->
                prefs.model = models[w]; b.modelValue.text = models[w]; d.dismiss()
            }.setNegativeButton("닫기", null).show()
    }

    private fun nightLabel() =
        Prefs.NIGHT_LABELS[Prefs.NIGHT_VALUES.indexOf(prefs.nightMode).coerceAtLeast(0)]

    private fun showNightPicker() {
        val labels = Prefs.NIGHT_LABELS.toTypedArray()
        AlertDialog.Builder(this).setTitle("다크 모드")
            .setSingleChoiceItems(labels, Prefs.NIGHT_VALUES.indexOf(prefs.nightMode)) { d, w ->
                prefs.nightMode = Prefs.NIGHT_VALUES[w]; b.nightValue.text = labels[w]
                d.dismiss(); AppCompatDelegate.setDefaultNightMode(prefs.nightMode)
            }.setNegativeButton("닫기", null).show()
    }

    // ---------- 에어팟 페어링 ----------

    private var btUiReceiver: android.content.BroadcastReceiver? = null

    override fun onStart() {
        super.onStart()
        BtState.refresh(this)
        val r = BtState.receiver { }
        btUiReceiver = r
        androidx.core.content.ContextCompat.registerReceiver(
            this, r, BtState.filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        stopDiscovery()
        btUiReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        btUiReceiver = null
    }

    /** 페어링 모드 진입 방법 안내 → 기기 선택 창. */
    private fun pairGuide() {
        if (!runtimePermsOk()) { requestRuntimePerms(); toast("먼저 블루투스 권한을 허용해 주세요"); return }
        AlertDialog.Builder(this)
            .setTitle("에어팟을 페어링 모드로")
            .setMessage(
                "1. 에어팟을 케이스에 넣고 뚜껑을 연 채 폰 옆에 두세요.\n" +
                "2. 케이스의 설정 버튼을 상태 표시등이 흰색으로 깜빡일 때까지 길게 누르세요.\n" +
                "   (버튼이 없는 에어팟 4는 케이스 앞면 표시등 부근을 두 번 톡톡)\n" +
                "3. 깜빡이면 [다음]을 누르고, 목록에서 에어팟을 선택하세요.\n\n" +
                "※ 이름을 바꾼 에어팟은 바꾼 이름으로 표시돼요. " +
                "검색은 10초 정도 걸려요."
            )
            .setPositiveButton("다음") { _, _ -> startOwnDiscovery() }
            .setNeutralButton("블루투스 설정") { _, _ -> openBtSettings() }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------- 앱 자체 기기 검색 (이름 있는 오디오 기기만, MAC 주소 비노출) ----------

    /** cat: 0=에어팟/비츠로 보임, 1=오디오 기기, 2=기타 */
    private data class Found(
        val dev: android.bluetooth.BluetoothDevice, val name: String,
        val cat: Int, val bonded: Boolean,
    )

    private val found = linkedMapOf<String, Found>()          // key = 주소(화면에는 안 보임)
    private val seenAll = mutableSetOf<String>()               // 진단: 이름 유무 상관없이 발견된 기기
    private var pickDialog: AlertDialog? = null
    private var pickAdapter: android.widget.ArrayAdapter<String>? = null
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

        val listAdapter = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        pickAdapter = listAdapter
        pickDialog = AlertDialog.Builder(this)
            .setTitle("에어팟 찾는 중… (약 12초)")
            .setAdapter(listAdapter) { _, which ->
                val f = sortedFound().getOrNull(which) ?: return@setAdapter
                stopDiscovery(); bond(f.dev)
            }
            .setPositiveButton("다시 검색") { _, _ -> startOwnDiscovery() }
            .setNeutralButton("블루투스 설정") { _, _ -> stopDiscovery(); openBtSettings() }
            .setNegativeButton("취소") { _, _ -> stopDiscovery() }
            .setOnCancelListener { stopDiscovery() }
            .show()
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
                        pickDialog?.setTitle(
                            if (found.values.none { it.cat <= 1 })
                                "에어팟을 못 찾았어요 (발견 ${seenAll.size} · 이름 확인 $named)"
                            else "연결할 에어팟을 선택하세요 (발견 ${seenAll.size})"
                        )
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
            if (!adapter.startDiscovery()) pickDialog?.setTitle("검색을 시작하지 못했어요 — 위치 서비스·권한을 확인해 주세요")
        } catch (e: Exception) { pickDialog?.setTitle("검색 오류: ${e.message}") }
    }

    private fun sortedFound() = found.values.sortedWith(
        compareBy<Found> { it.cat }.thenByDescending { it.bonded }.thenBy { it.name }
    )

    private fun refreshPickList() {
        val a = pickAdapter ?: return
        a.clear()
        a.addAll(sortedFound().map {
            val icon = when (it.cat) { 0 -> "🎧"; 1 -> "🔈"; else -> "·" }
            "$icon ${it.name}" + if (it.bonded) "  (페어링됨)" else ""
        })
        a.notifyDataSetChanged()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun stopDiscovery() {
        try { getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.cancelDiscovery() } catch (_: Exception) {}
        discoveryReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        discoveryReceiver = null
    }

    /** 선택한 에어팟과 페어링(본딩). 완료되면 안드로이드가 음악 연결을 자동으로 맺음. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun bond(dev: android.bluetooth.BluetoothDevice) {
        try {
            val name = BtState.safeName(this, dev)
            if (dev.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED) {
                toast("$name — 이미 페어링됨. 블루투스 설정에서 '연결'을 눌러주세요")
                openBtSettings(); return
            }
            if (dev.createBond()) toast("$name 페어링 중… 화면의 요청을 허용해 주세요")
            else { toast("페어링을 시작하지 못했어요 — 블루투스 설정에서 연결해 주세요"); openBtSettings() }
        } catch (e: Exception) {
            toast("페어링 오류: ${e.message}"); openBtSettings()
        }
    }

    private fun openBtSettings() {
        try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {}
    }

    /** 마지막 에어팟 패킷 원문과 글자 위치별 해석값을 보여주고 복사할 수 있게 함. */
    private fun showDiag() {
        val hex = PodsRepo.lastRawHex
        val head = "연결: ${BtState.connectedName.value ?: "없음"} · HFP 배터리: ${BtState.hfpBattery.value?.let { "$it%" } ?: "없음"}\n" +
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
                append("(0% = 값 없음, 원문 글자가 F)")
            }
        }
        AlertDialog.Builder(this).setTitle("진단 정보").setMessage(text)
            .setPositiveButton("닫기", null)
            .setNeutralButton("복사") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("aospods", text))
                toast("진단 정보를 복사했습니다")
            }.show()
    }

    private fun showPrivacy() {
        AlertDialog.Builder(this).setTitle("개인정보처리방침")
            .setMessage(
                "AosPods는 AirPods가 주변에 보내는 블루투스 신호만 읽어 배터리 상태를 " +
                "표시합니다. 위치·배터리 정보를 수집하거나 외부로 전송하지 않으며, " +
                "모든 처리는 기기 안에서만 이루어집니다."
            ).setPositiveButton("확인", null).show()
    }
}
