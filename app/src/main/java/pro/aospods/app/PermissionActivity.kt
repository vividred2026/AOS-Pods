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
    /** 화면에 보여 줄 항목 = 화면을 열 때 아직 허용되지 않은 권한만 (Figma 1282:2409). */
    private var shown: List<Item> = emptyList()
    private val rows = mutableListOf<PermRowBinding>()

    /** [동의합니다] 흐름: 런타임 권한 → (다른 앱 위에 표시) → 메인. */
    private var agreeFlow = false

    private val runtimeLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
        if (!agreeFlow) return@registerForActivityResult
        if (Perms.ok(this)) askOverlayThenFinish() else runtimeDenied()
    }
    private val overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh()
        if (agreeFlow) finishIfAllGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPermissionBinding.inflate(layoutInflater)
        setContentView(b.root)

        shown = items.filterNot(::granted)
        for (item in shown) {
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
            agreeFlow = true
            val missing = Perms.missing(this)
            when {
                missing.isEmpty() -> askOverlayThenFinish()
                missing.any { it == Manifest.permission.ACCESS_FINE_LOCATION } ->
                    showLocationReason(onYes = { runtimeLauncher.launch(missing.toTypedArray()) },
                        onNo = { runtimeLauncher.launch(missing.filterNot(::isLocation).toTypedArray()) })
                else -> runtimeLauncher.launch(missing.toTypedArray())
            }
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun isLocation(p: String) =
        p == Manifest.permission.ACCESS_FINE_LOCATION || p == Manifest.permission.ACCESS_COARSE_LOCATION

    private fun granted(item: Item): Boolean =
        if (item.overlay) Settings.canDrawOverlays(this)
        else item.runtime.all { ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    private fun refresh() {
        shown.forEachIndexed { i, item ->
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
        } else finishIfAllGranted()
    }

    /** 처음 진입 시 권한을 모두 허용해야 메인으로 이동(사용자 결정 2026-11-03, [나중에 설정하기] 삭제). */
    private fun finishIfAllGranted() {
        if (allGranted(this)) goMain()
        else toast("모든 권한을 허용해야 시작할 수 있어요")
    }

    /** 거부됨. '다시 묻지 않음'이면 요청 창이 더 안 뜨므로 앱 설정으로 보냄. */
    private fun runtimeDenied() {
        val blocked = Perms.missing(this).any { !shouldShowRequestPermissionRationale(it) }
        if (blocked) {
            toast("앱 설정 > 권한에서 허용해 주세요")
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        } else toast("모든 권한을 허용해야 시작할 수 있어요")
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

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
        fun allGranted(ctx: Context) = Perms.ok(ctx) && Settings.canDrawOverlays(ctx)

        /** 실행할 때마다 검사 — 권한이 하나라도 없으면 권한 획득 페이지(S-02)로 이동(사용자 결정 2026-11-03). */
        fun shouldShow(ctx: Context) = !allGranted(ctx)
    }
}
