package pro.aospods.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * S-01 스플래시. 잠깐 보여 준 뒤, 처음이면 S-02 권한 안내 · 아니면 메인으로.
 * 배경 = 떠다니는 음표 루프 영상(res/raw/splash_bg_loop.mp4, 다음 화면으로 넘어갈 때까지 반복),
 * 로고·문구 = 코드로 등장 애니메이션(페이드인 + 살짝 튕기며 커짐).
 */
class SplashActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var video: TextureView
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var videoW = 0
    private var videoH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // 영상은 상태바·내비바 아래까지 깔고, 로고·글자만 시스템 바 여백 안에 둔다(AosApp의 content 여백을 대신함)
        val content = findViewById<View>(android.R.id.content)
        val fg = findViewById<View>(R.id.splash_fg)
        val pad = intArrayOf(fg.paddingLeft, fg.paddingTop, fg.paddingRight, fg.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(0, 0, 0, 0)
            fg.setPadding(pad[0] + bars.left, pad[1] + bars.top, pad[2] + bars.right, pad[3] + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        video = findViewById(R.id.splash_video)
        video.surfaceTextureListener = this
        video.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitCenterCrop() }

        animateLogo(findViewById(R.id.splash_logo), findViewById(R.id.splash_tagline))

        handler.postDelayed({
            val next = if (PermissionActivity.shouldShow(this)) PermissionActivity::class.java
                else MainActivity::class.java
            startActivity(Intent(this, next))
            finish()
        }, SPLASH_MS)
    }

    /** 로고: 0.55배 → 1배(오버슈트) + 페이드인 · 문구: 조금 늦게 아래에서 올라오며 페이드인 */
    private fun animateLogo(logo: View, tagline: View) {
        val rise = 8 * resources.displayMetrics.density
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f).apply {
                    duration = 400; interpolator = DecelerateInterpolator()
                },
                ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.55f, 1f).apply {
                    duration = 700; interpolator = OvershootInterpolator(1.6f)
                },
                ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.55f, 1f).apply {
                    duration = 700; interpolator = OvershootInterpolator(1.6f)
                },
                ObjectAnimator.ofFloat(tagline, View.ALPHA, 0f, 1f).apply {
                    startDelay = 450; duration = 400; interpolator = DecelerateInterpolator()
                },
                ObjectAnimator.ofFloat(tagline, View.TRANSLATION_Y, rise, 0f).apply {
                    startDelay = 450; duration = 400; interpolator = DecelerateInterpolator()
                },
            )
            startDelay = 150
            start()
        }
    }

    // ── 배경 루프 영상 ──────────────────────────────────────────────

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(st)
        val mp = MediaPlayer()
        try {
            resources.openRawResourceFd(R.raw.splash_bg_loop).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.setSurface(s)
            mp.isLooping = true
            mp.setVolume(0f, 0f) // 영상에 소리 없음 · 오디오 포커스도 잡지 않음(듣던 음악 안 멈춤)
            mp.setOnVideoSizeChangedListener { _, w, h -> videoW = w; videoH = h; fitCenterCrop() }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)
                    video.animate().alpha(1f).setDuration(250).start()
                false
            }
            mp.setOnErrorListener { _, _, _ -> releasePlayer(); true } // 실패하면 단색 보라 배경 그대로
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
            player = mp
            surface = s
        } catch (e: Exception) {
            mp.release()
            s.release()
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = fitCenterCrop()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    /** 화면 비율이 영상(9:16)과 달라도 늘어나지 않게 가운데 기준으로 꽉 채워 자름 */
    private fun fitCenterCrop() {
        val w = video.width.toFloat()
        val h = video.height.toFloat()
        if (videoW == 0 || videoH == 0 || w == 0f || h == 0f) return
        val s = maxOf(w / videoW, h / videoH)
        video.setTransform(Matrix().apply { setScale(videoW * s / w, videoH * s / h, w / 2, h / 2) })
    }

    private fun releasePlayer() {
        player?.let { try { it.release() } catch (_: Exception) {} }
        player = null
        surface?.release()
        surface = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }

    companion object { private const val SPLASH_MS = 1800L }
}
