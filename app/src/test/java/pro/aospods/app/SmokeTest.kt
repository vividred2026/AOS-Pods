package pro.aospods.app

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmokeTest {
    @Test fun mainActivityCreates() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(c.get())
        c.pause().stop().destroy()
    }
    @Test fun notifSettingsCreates() {
        Robolectric.buildActivity(NotifSettingsActivity::class.java).setup().destroy()
    }
    @Test fun percentSettingsCreates() {
        Robolectric.buildActivity(PercentSettingsActivity::class.java).setup().destroy()
    }
    @Test fun popupSettingsCreates() {
        Robolectric.buildActivity(PopupSettingsActivity::class.java).setup().destroy()
    }
}
