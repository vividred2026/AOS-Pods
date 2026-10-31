package pro.aospods.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AT+IPHONEACCEV=n,키,값,... — 키 1 = 배터리(0~9 → 10~100%). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])   // BtState가 IntentFilter를 만들므로 안드로이드 환경 필요
class AppleAccEvTest {
    @Test fun batteryFromNumbers() = assertEquals(90, BtState.parseAppleAccEv(arrayOf(2, 1, 8, 2, 0)))
    @Test fun batteryFromStrings() = assertEquals(50, BtState.parseAppleAccEv(arrayOf("1", "1", "4")))
    @Test fun batteryKeyNotFirst() = assertEquals(100, BtState.parseAppleAccEv(arrayOf(2, 2, 0, 1, 9)))
    @Test fun noBatteryKey() = assertNull(BtState.parseAppleAccEv(arrayOf(1, 2, 0)))
    @Test fun malformed() {
        assertNull(BtState.parseAppleAccEv(null))
        assertNull(BtState.parseAppleAccEv(arrayOf(3, 1)))
        assertNull(BtState.parseAppleAccEv(arrayOf(1, 1, 12)))
    }
}
