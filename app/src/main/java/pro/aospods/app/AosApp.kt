package pro.aospods.app

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/** 비정상 종료 시 스택트레이스를 파일로 남겨, 다음 실행 때 앱 안에서 확인할 수 있게 한다. */
class AosApp : Application() {
    override fun onCreate() {
        super.onCreate()
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
