package pro.aospods.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 디자인 확인용 화면 캡처 (Robolectric 실제 렌더링). app/build/screens 아래 PNG — CI가 릴리스에 첨부. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w375dp-h812dp-xhdpi")
class ScreenshotTest {
    private val out = File("build/screens").apply { mkdirs() }

    private fun save(v: View, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun shot(a: Activity, name: String) = save(a.window.decorView, name)

    @Test fun splash() = shot(Robolectric.buildActivity(SplashActivity::class.java).setup().get(), "s01_splash")

    @Test fun permission() = shot(Robolectric.buildActivity(PermissionActivity::class.java).setup().get(), "s02_permission")

    @Test fun mainDisconnected() {
        BtState.connectedName.value = null; PodsRepo.update(null)
        shot(Robolectric.buildActivity(MainActivity::class.java).setup().get(), "m00_disconnected")
    }

    @Test fun mainConnected() {
        BtState.connectedName.value = "AirPods 4"
        PodsRepo.update(Pods("에어팟 4", 85, 85, 55, false, false, true, false, false))
        shot(Robolectric.buildActivity(MainActivity::class.java).setup().get(), "m00_connected")
        BtState.connectedName.value = null; PodsRepo.update(null)
    }

    @Test @Config(qualifiers = "+night") fun mainConnectedDark() {
        BtState.connectedName.value = "AirPods 4"
        PodsRepo.update(Pods("에어팟 4", 85, 85, 55, false, false, true, false, false))
        shot(Robolectric.buildActivity(MainActivity::class.java).setup().get(), "m00_connected_dark")
        BtState.connectedName.value = null; PodsRepo.update(null)
    }

    @Test @Config(qualifiers = "+night") fun permissionDark() =
        shot(Robolectric.buildActivity(PermissionActivity::class.java).setup().get(), "s02_permission_dark")

    @Test fun listenSheet() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val s = Sheets.choice(a, "청취 모드", listOf("노이즈 캔슬링", "주변음 허용", "적응형", "끔"), 0) {}
        save(s.dialog.window!!.decorView, "sheet_listen")
    }

    @Test fun pairGuideSheet() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(*Perms.required())
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        MainActivity::class.java.getDeclaredMethod("pairGuide").apply { isAccessible = true }.invoke(a)
        shadowOf(Looper.getMainLooper()).idle()
        save(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView, "d01_pair_guide")
    }

    @Test fun privacySheet() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        MainActivity::class.java.getDeclaredMethod("showPrivacy").apply { isAccessible = true }.invoke(a)
        shadowOf(Looper.getMainLooper()).idle()
        save(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView, "d08_privacy")
    }

    /** 행 높이 점검용: 메인 화면 '알림으로 표시' 행과 자식들의 높이(dp)를 screens/layout.txt에 기록. */
    @Test fun layoutDump() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val d = a.resources.displayMetrics.density
        val sw = a.findViewById<View>(R.id.swNotif)
        val row = sw.parent as android.view.ViewGroup
        val sb = StringBuilder()
        fun dump(v: View, depth: Int) {
            sb.append("  ".repeat(depth)).append(v.javaClass.simpleName)
                .append(" h=").append(v.height / d).append(" minH=").append(v.minimumHeight / d)
                .append(" pad=").append(v.paddingTop / d).append('/').append(v.paddingBottom / d)
            (v.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                sb.append(" m=").append(it.topMargin / d).append('/').append(it.bottomMargin / d)
            }
            sb.append('\n')
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) dump(v.getChildAt(i), depth + 1)
        }
        dump(row, 0)
        File(out, "layout.txt").writeText(sb.toString())
    }

    /** O-01 배터리 팝업(Figma 1216:5587) — 오버레이 권한 없이 레이아웃만 inflate해 묶기. */
    @Test fun popupSheet() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        BtState.connectedName.value = "Kris"
        val overlay = PodsOverlay(a)
        val b = pro.aospods.app.databinding.SheetPodsBinding.inflate(a.layoutInflater)
        PodsOverlay::class.java.getDeclaredMethod("bind",
            pro.aospods.app.databinding.SheetPodsBinding::class.java, Pods::class.java, Prefs::class.java)
            .apply { isAccessible = true }
            .invoke(overlay, b, Pods("에어팟 4", 85, 85, 55, false, false, true, false, false), Prefs(a))
        val w = (375 * a.resources.displayMetrics.density).toInt()
        b.root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        b.root.layout(0, 0, b.root.measuredWidth, b.root.measuredHeight)
        save(b.root, "o01_popup")
        BtState.connectedName.value = null
    }

    @Test fun notifSettings() = shot(Robolectric.buildActivity(NotifSettingsActivity::class.java).setup().get(), "n01_notif")
}
