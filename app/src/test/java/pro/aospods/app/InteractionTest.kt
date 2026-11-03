package pro.aospods.app

import android.widget.LinearLayout
import com.google.android.material.switchmaterial.SwitchMaterial
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InteractionTest {

    @Test fun serviceSwitchTogglesAndPersists() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val sw = a.findViewById<SwitchMaterial>(R.id.swService)
        assertTrue("스위치는 탭 가능해야 함", sw.isClickable)
        assertFalse(sw.isChecked)
        sw.performClick()                       // 탭 시뮬레이션
        assertTrue("탭하면 켜져야 함", sw.isChecked)
        assertTrue("설정에 저장돼야 함", Prefs(a).serviceEnabled)
    }

    @Test fun pairCardSwitchesBetweenStates() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        val off = a.findViewById<android.view.View>(R.id.pairDisconnected)
        val on = a.findViewById<android.view.View>(R.id.pairConnected)
        BtState.connectedName.value = null
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("연결 전 카드", android.view.View.VISIBLE, off.visibility)
        assertEquals(android.view.View.GONE, on.visibility)

        BtState.connectedName.value = "AirPods 4"
        PodsRepo.update(Pods("x", 85, 80, 55, false, false, true, false, false))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(android.view.View.GONE, off.visibility)
        assertEquals("연결 후 카드", android.view.View.VISIBLE, on.visibility)
        val pods = a.findViewById<android.view.ViewGroup>(R.id.podsRow)
        fun pct(i: Int) = (pods.getChildAt(i).findViewById<android.widget.TextView>(R.id.podPct)).text.toString()
        assertEquals("R 칩 = 오른쪽", "80%", pct(0))
        assertEquals("L 칩 = 왼쪽", "85%", pct(1))
        assertEquals("C 칩 = 케이스(충전 중)", "55%⚡", pct(2))

        BtState.connectedName.value = null; PodsRepo.update(null)
    }

    @Test fun batteryRowTextOpensSubPage() {
        PermissionActivity.markDone(org.robolectric.RuntimeEnvironment.getApplication()) // S-02 자동 안내는 이 테스트 대상 아님
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.findViewById<LinearLayout>(R.id.textNotif).performClick()
        val next = shadowOf(a).nextStartedActivity
        assertNotNull("하위 설정으로 이동해야 함", next)
        assertEquals(NotifSettingsActivity::class.java.name, next.component?.className)
    }
}
