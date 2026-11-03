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
    @Test fun splashCreates() {
        Robolectric.buildActivity(SplashActivity::class.java).setup().destroy()
    }
    @Test fun permissionScreenListsPermissionsForThisSdk() {
        val a = Robolectric.buildActivity(PermissionActivity::class.java).setup().get()
        val list = a.findViewById<android.widget.LinearLayout>(R.id.permList)
        // SDK 34: 다른 앱 위에 표시 · 블루투스 · 알림 (위치는 11 이하만)
        org.junit.Assert.assertEquals(3, list.childCount)
    }
    /** 권한 안내는 처음 설치 후 한 번만. 백업으로 되살아난 이전 설치의 기록으로는 건너뛰지 않음. */
    @Test fun permissionScreenOncePerInstall() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        org.junit.Assert.assertTrue("새 설치 → 권한 안내", PermissionActivity.shouldShow(app))

        PermissionActivity.markDone(app)
        org.junit.Assert.assertFalse("이번 설치에서 봤으면 다시 안 뜸", PermissionActivity.shouldShow(app))

        Prefs(app).onboardedInstall = 12345L // 자동 백업이 되살린 이전 설치의 기록
        org.junit.Assert.assertTrue("이전 설치의 기록이면 다시 권한 안내", PermissionActivity.shouldShow(app))
    }
    @Test fun choiceSheetShows() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        var picked = -1
        val s = Sheets.choice(a, "청취 모드", listOf("노이즈 캔슬링", "주변음 허용"), 0) { picked = it }
        org.junit.Assert.assertEquals(2, s.b.sheetBody.childCount)
        s.b.sheetBody.getChildAt(1).performClick()
        org.junit.Assert.assertEquals(1, picked)
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
