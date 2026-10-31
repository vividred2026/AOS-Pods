package pro.aospods.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/** S-01 스플래시. 잠깐 보여 준 뒤, 처음이면 S-02 권한 안내 · 아니면 메인으로. */
class SplashActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        handler.postDelayed({
            val next = if (PermissionActivity.shouldShow(this)) PermissionActivity::class.java
                else MainActivity::class.java
            startActivity(Intent(this, next))
            finish()
        }, SPLASH_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object { private const val SPLASH_MS = 1200L }
}
