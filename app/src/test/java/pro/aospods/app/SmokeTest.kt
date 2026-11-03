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
    /** Figma 1282:2409: 이미 허용된 권한은 목록에서 빠지고, 없는 권한만 보여 준다. */
    @Test fun permissionScreenListsOnlyMissing() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val a = Robolectric.buildActivity(PermissionActivity::class.java).setup().get()
        val list = a.findViewById<android.widget.LinearLayout>(R.id.permList)
        // SDK 34, 알림만 허용됨 → 다른 앱 위에 표시 · 블루투스 2줄
        org.junit.Assert.assertEquals(2, list.childCount)
    }
    /** 실행할 때마다 실제 권한으로 판단 — 하나라도 없으면 권한 획득 페이지, 모두 있으면 메인. */
    @Test fun permissionScreenWhenAnyPermissionMissing() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        org.junit.Assert.assertTrue("권한 없음 → 권한 페이지", PermissionActivity.shouldShow(app))

        shadowApp.grantPermissions(*Perms.required())
        org.junit.Assert.assertTrue("다른 앱 위에 표시가 없으면 권한 페이지", PermissionActivity.shouldShow(app))

        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        org.junit.Assert.assertFalse("모두 허용 → 메인", PermissionActivity.shouldShow(app))

        shadowApp.denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        org.junit.Assert.assertTrue("하나라도 빠지면 다시 권한 페이지", PermissionActivity.shouldShow(app))
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
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
