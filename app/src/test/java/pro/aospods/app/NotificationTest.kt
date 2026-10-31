package pro.aospods.app

import android.app.Notification
import android.graphics.drawable.Icon
import androidx.core.app.NotificationCompat
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 배터리 표시 3항목이 알림에 실제로 반영되는지 검증. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationTest {
    private val pods = Pods("에어팟 3", 85, 70, 55, false, false, false, true, true)

    private fun build(svc: ScanService, p: Pods?): Notification {
        val m = ScanService::class.java.getDeclaredMethod("buildNotification", Pods::class.java)
        m.isAccessible = true
        return m.invoke(svc, p) as Notification
    }
    private fun text(n: Notification) = n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()
    private fun svc() = Robolectric.buildService(ScanService::class.java).create().get()

    @After fun reset() { BtState.connectedName.value = null }

    @Test fun notificationOffShowsNoBattery() {
        val s = svc(); Prefs(s).notification = false
        BtState.connectedName.value = "내 에어팟"
        assertEquals("실행 중 · 알림 표시 꺼짐", text(build(s, pods)))
    }

    @Test fun percentIconOnUsesBitmapNumberIcon() {
        val s = svc(); val pr = Prefs(s); pr.notification = true; pr.percentInIcon = true
        BtState.connectedName.value = "내 에어팟"
        val n = build(s, pods)
        assertEquals("가장 낮음 기준 → 55%", "가장 낮음 55%", text(n))
        assertEquals("상태바 아이콘이 숫자 이미지(비트맵)", Icon.TYPE_BITMAP, n.smallIcon.type)
    }

    @Test fun percentIconOffUsesStaticIcon() {
        val s = svc(); val pr = Prefs(s); pr.notification = true; pr.percentInIcon = false
        BtState.connectedName.value = "내 에어팟"
        assertEquals(Icon.TYPE_RESOURCE, build(s, pods).smallIcon.type)
    }

    @Test fun disconnectedKeepsLastValueWhenEnabled() {
        val s = svc(); val pr = Prefs(s); pr.notification = true; pr.showWhenDisconnected = true
        assertTrue(text(build(s, pods)).startsWith("연결 끊김 · 마지막 값"))
        pr.showWhenDisconnected = false
        assertTrue(text(build(s, pods)).contains("연결되지 않았어요"))
    }
}
