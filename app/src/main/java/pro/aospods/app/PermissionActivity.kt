package pro.aospods.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import pro.aospods.app.Sheets.buttons
import pro.aospods.app.Sheets.text
import pro.aospods.app.databinding.ActivityPermissionBinding
import pro.aospods.app.databinding.PermRowBinding

/** S-02 접근 권한 안내 (Figma 1216:3249). 권한별 사유를 보여 주고 [동의합니다]로 한 번에 요청한다.
 *  체크 = 이미 허용됨. 줄을 누르면 그 권한만 요청. 위치(안드로이드 11 이하)는 먼저 이유 시트(1216:3451)를 보여 준다. */
class PermissionActivity : AppCompatActivity() {

    private lateinit var b: ActivityPermissionBinding

    private class Item(val title: String, val reason: String, val runtime: List<String>, val overlay: Boolean = false)

    /** 이 기기에서 보여 줄 권한 목록 (Perms.required()와 같은 기준 + 팝업용 다른 앱 위에 표시). */
    private val items: List<Item> by lazy {
        buildList {
            add(Item("다른 앱 위에 표시", "케이스 열면 배터리 팝업 표시", emptyList(), overlay = true))
            if (Build.VERSION.SDK_INT < 31) add(Item("위치", "주변 기기 검색 (안드로이드 11 이하)",
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)))
            if (Build.VERSION.SDK_INT >= 31) add(Item("블루투스", "에어팟 연결 · 배터리·착용 상태 수신",
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)))
            if (Build.VERSION.SDK_INT >= 33) add(Item("알림", "알림창 · 상태바에 배터리 표시",
                listOf(Manifest.permission.POST_NOTIFICATIONS)))
        }
    }
    private val rows = mutableListOf<PermRowBinding>()

    /** [동의합니다] 흐름: 런타임 권한 → (다른 앱 위에 표시) → 메인. */
    private var agreeFlow = false

    private val runtimeLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
        if (agreeFlow) askOverlayThenFinish()
    }
    private val overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh()
        if (agreeFlow) goMain()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPermissionBinding.inflate(layoutInflater)
        setContentView(b.root)

        for (item in items) {
            val r = PermRowBinding.inflate(LayoutInflater.from(this), b.permList, false)
            r.permTitle.text = item.title
            r.permReason.text = item.reason
            r.root.setOnClickListener { agreeFlow = false; request(item) }
            b.permList.addView(r.root); rows += r
        }

        // "어떠한 정보도 외부에 전송하지 않으며," 만 보라 강조
        val strong = "어떠한 정보도 외부에 전송하지 않으며,"
        val txt = "AOS PODS는 내가 필요해서 만든 앱으로 $strong 에어팟이 주변에 보내는 블루투스 신호만 읽어 배터리 정보를 표시합니다."
        b.permPrivacy.text = SpannableStringBuilder(txt).apply {
            val s = txt.indexOf(strong)
            setSpan(ForegroundColorSpan(ContextCompat.getColor(this@PermissionActivity, R.color.accent)),
                s, s + strong.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val semi = androidx.core.content.res.ResourcesCompat.getFont(this@PermissionActivity, R.font.pretendard_semibold)
            val span: Any = if (Build.VERSION.SDK_INT >= 28 && semi != null) android.text.style.TypefaceSpan(semi)
                else android.text.style.StyleSpan(android.graphics.Typeface.BOLD)
            setSpan(span, s, s + strong.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        b.btnAgree.setOnClickListener {
            markDone(this); agreeFlow = true
            val missing = Perms.missing(this)
            when {
                missing.isEmpty() -> askOverlayThenFinish()
                missing.any { it == Manifest.permission.ACCESS_FINE_LOCATION } ->
                    showLocationReason(onYes = { runtimeLauncher.launch(missing.toTypedArray()) },
                        onNo = { runtimeLauncher.launch(missing.filterNot(::isLocation).toTypedArray()) })
                else -> runtimeLauncher.launch(missing.toTypedArray())
            }
        }
        b.btnLater.setOnClickListener { markDone(this); goMain() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun isLocation(p: String) =
        p == Manifest.permission.ACCESS_FINE_LOCATION || p == Manifest.permission.ACCESS_COARSE_LOCATION

    private fun granted(item: Item): Boolean =
        if (item.overlay) Settings.canDrawOverlays(this)
        else item.runtime.all { ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    private fun refresh() {
        items.forEachIndexed { i, item ->
            rows[i].permCheck.setImageResource(if (granted(item)) R.drawable.ic_check_box_on else R.drawable.ic_check_box_off)
        }
    }

    private fun request(item: Item) {
        if (granted(item)) return
        when {
            item.overlay -> overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            item.runtime.any(::isLocation) -> showLocationReason(onYes = { runtimeLauncher.launch(item.runtime.toTypedArray()) }, onNo = {})
            else -> runtimeLauncher.launch(item.runtime.toTypedArray())
        }
    }

    private fun askOverlayThenFinish() {
        if (!Settings.canDrawOverlays(this)) {
            overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else goMain()
    }

    /** 위치 권한 이유 시트 (Figma 1216:3451). */
    private fun showLocationReason(onYes: () -> Unit, onNo: () -> Unit) {
        val s = Sheets.base(this, "위치 권한이 필요한 이유")
        s.text("안드로이드 11 이하에서는 블루투스로 주변 에어팟을 찾을 때 위치 권한이 필요해요(구버전 블루투스 API의 요구 사항). " +
            "서비스를 켠 동안 에어팟 배터리 표시와 착용 감지에만 쓰며, 앱은 위치 정보를 수집 · 저장하거나 제3자에게 전송하지 않습니다.")
        s.b.sheetScroll.layoutParams = s.b.sheetScroll.layoutParams.apply { height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT }
        s.buttons("아니요", { s.dialog.dismiss(); onNo() }, "예", { s.dialog.dismiss(); onYes() })
        s.dialog.show()
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    companion object {
        /** 처음 설치 후 한 번만 권한 안내. 이후 권한이 빠지면 메인의 권한 카드가 안내.
         *  "한 번 봤음"은 설치 시각과 함께 저장 — 백업 복원으로 옛 값이 돌아와도 새 설치면 다시 보여 준다. */
        fun shouldShow(ctx: Context) = Prefs(ctx).onboardedInstall != installTime(ctx)

        fun markDone(ctx: Context) { Prefs(ctx).onboardedInstall = installTime(ctx) }

        @Suppress("DEPRECATION")
        private fun installTime(ctx: Context): Long = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime
        } catch (e: Exception) { 0L }
    }
}
