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

    @Test fun batteryRowTextOpensSubPage() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.findViewById<LinearLayout>(R.id.textNotif).performClick()
        val next = shadowOf(a).nextStartedActivity
        assertNotNull("하위 설정으로 이동해야 함", next)
        assertEquals(NotifSettingsActivity::class.java.name, next.component?.className)
    }
}
