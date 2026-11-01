package pro.aospods.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/** 비정상 종료 시 스택트레이스를 파일로 남겨, 다음 실행 때 앱 안에서 확인할 수 있게 한다. */
class AosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 다크 모드 설정을 어느 화면으로 시작하든 적용
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(Prefs(this).nightMode)
        // AACP 소켓·HFP 배터리 조회에 필요한 블루투스 숨김 API 허용(full 갈래만 실제 동작)
        HiddenApi.allowBluetooth()
        // targetSdk 35+: 창이 상태바·내비게이션바 아래까지 깔리므로(edge-to-edge 강제) 화면 내용에 시스템 바만큼 여백을 준다.
        // 창 배경(흰/검·스플래시 보라)이 바 뒤로 이어져 이전 모습과 같게 보인다.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                val content = a.findViewById<View>(android.R.id.content) ?: return
                ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    WindowInsetsCompat.CONSUMED
                }
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                crashFile(this).writeText("${Date()}\n${t.name}\n\n$sw")
            } catch (_: Throwable) {}
            def?.uncaughtException(t, e)
        }
    }

    companion object {
        fun crashFile(ctx: android.content.Context): File =
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "crash.txt")
    }
}
