package pro.aospods.app

import android.Manifest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TileTest {

    /** 타일 탭 → 서비스 ON/OFF 상태(Prefs)가 토글되는지 검증. */
    @Test fun tileClickTogglesServiceState() {
        // 권한이 있는 상태를 가정(없으면 타일은 앱을 열어 권한을 요청)
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.BLUETOOTH_SCAN)

        val svc = Robolectric.buildService(OnOffTileService::class.java).create().get()
        val prefs = Prefs(svc)
        assertFalse(prefs.serviceEnabled)
        svc.onClick()                       // 빠른 설정 타일 탭 → 켜기
        assertTrue("타일을 누르면 서비스가 켜져야 함", prefs.serviceEnabled)
        svc.onClick()                       // 다시 탭 → 끄기
        assertFalse("다시 누르면 꺼져야 함", prefs.serviceEnabled)
    }
}
