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

/** 디자인 확인용 화면 캡처 (Robolectric 실제 렌더링). app/build/screens/*.png — CI가 릴리스에 첨부. */
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

    @Test fun listenSheet() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val s = Sheets.choice(a, "청취 모드", listOf("노이즈 캔슬링", "주변음 허용", "적응형", "끔"), 0) {}
        save(s.dialog.window!!.decorView, "sheet_listen")
    }

    @Test fun notifSettings() = shot(Robolectric.buildActivity(NotifSettingsActivity::class.java).setup().get(), "n01_notif")
}
