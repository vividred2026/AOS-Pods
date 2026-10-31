package pro.aospods.app

import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** 안드로이드 숨김 API 제한에서 블루투스 클래스만 예외로 둔다.
 *  - AACP 연결: BluetoothSocket 숨김 생성자(L2CAP PSM 0x1001)
 *  - HFP 배터리: BluetoothDevice.getBatteryLevel()
 *  안드로이드 17(SDK 37)에서는 이 예외 없이는 생성자가 반사로도 아예 보이지 않음(실기기 확인).
 *  사이드로드 전용 — 구글 플레이 정책상 스토어 배포 앱에서는 쓸 수 없음. */
object HiddenApi {
    @Volatile var result: String = "시도 안 함"
        private set

    @Synchronized
    fun allowBluetooth(): Boolean {
        if (Build.VERSION.SDK_INT < 28) { result = "필요 없음(SDK ${Build.VERSION.SDK_INT})"; return true }
        return try {
            val ok = HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/bluetooth/BluetoothSocket;",
                "Landroid/bluetooth/BluetoothDevice;",
            )
            result = if (ok) "예외 등록 성공" else "예외 등록 실패"
            ok
        } catch (e: Throwable) {
            result = "예외 등록 오류: ${e.javaClass.simpleName}"; false
        }
    }
}
