package pro.aospods.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import pro.aospods.app.databinding.SheetPodsBinding

/** 다른 앱 위에 뜨는 시스템 오버레이 배터리 팝업 (O-01). */
class PodsOverlay(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themed = android.view.ContextThemeWrapper(ctx, R.style.Theme_AosPods)
    private var view: View? = null
    private var vb: SheetPodsBinding? = null
    /** 사용자가 닫기(X)를 누르면 true — 다음 hide()까지 다시 뜨지 않음. */
    private var dismissed = false

    fun canShow(): Boolean =
        if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(ctx) else true

    fun show(p: Pods, prefs: Prefs) {
        if (!canShow() || dismissed) return
        // 서비스 컨텍스트에는 앱 테마가 없어 ?attr 참조가 풀리지 않으므로 앱 테마를 입혀 inflate
        val binding = vb ?: SheetPodsBinding.inflate(LayoutInflater.from(themed)).also { vb = it }
        binding.sheetClose.setOnClickListener { dismissed = true; remove() }
        bind(binding, p, prefs)

        // 여백(N-03): 창 너비를 화면폭-양쪽여백으로 줄이고 가운데 정렬, 아래쪽도 같은 여백
        val m = dp(prefs.popupMargin)
        val screenW = ctx.resources.displayMetrics.widthPixels
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            (screenW - 2 * m).coerceAtLeast(dp(200)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = m
            dimAmount = prefs.popupDim.coerceIn(0, 100) / 100f
        }
        if (view == null) {
            view = binding.root
            try { wm.addView(binding.root, lp) } catch (_: Exception) { view = null }
        } else {
            // 설정(여백·어둡게)을 바꾼 경우 이미 떠 있는 팝업에도 반영
            try { wm.updateViewLayout(binding.root, lp) } catch (_: Exception) {}
        }
    }

    /** 외부(서비스) 요청으로 숨김 — 닫힘 상태를 초기화해 다음 연결 때 다시 뜨게 함. */
    fun hide() {
        remove()
        dismissed = false
    }

    /** 화면에서 뷰만 제거 (닫힘 플래그는 유지). */
    private fun remove() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null; vb = null
    }

    private fun bind(binding: SheetPodsBinding, p: Pods, prefs: Prefs) {
        // 제목을 비우면 연결된 기기 이름(이름을 바꾼 에어팟은 바꾼 이름), 없으면 모델명
        val title = prefs.popupTitle.ifBlank { BtState.connectedName.value ?: p.model }
        binding.sheetTitle.text = title
        binding.sheetTitle.textSize = prefs.popupTitleSize.toFloat()
        // 이름 옆 회색 모델(Figma "Kris   AirPods 4") — 제목이 모델명과 같으면 생략
        binding.sheetModel.text = p.model.takeIf { it != title } ?: ""
        // 모서리 둥글기 · 여백 적용
        (binding.root.background?.mutate() as? GradientDrawable)?.cornerRadius = dp(prefs.popupCorner).toFloat()

        bindPod(binding.sheetLeft.podLabel, binding.sheetLeft.podPct, binding.sheetLeft.podBar,
            "왼쪽", p.left, p.leftCharging)
        bindPod(binding.sheetRight.podLabel, binding.sheetRight.podPct, binding.sheetRight.podBar,
            "오른쪽", p.right, p.rightCharging)
        bindPod(binding.sheetCase.podLabel, binding.sheetCase.podPct, binding.sheetCase.podBar,
            "케이스", p.case, p.caseCharging)

        // Figma 1216:5587: 왼쪽·오른쪽 = 이어버드 사진, 케이스 = "C" 글자
        binding.sheetLeft.podIcon.setImageResource(R.drawable.bud_left)
        binding.sheetRight.podIcon.setImageResource(R.drawable.bud_left)
        binding.sheetRight.podIcon.scaleX = -1f   // 오른쪽 = 왼쪽 사진의 거울상
        binding.sheetLeft.podIcon.visibility = View.VISIBLE
        binding.sheetLeft.podLetter.visibility = View.GONE
        binding.sheetRight.podIcon.visibility = View.VISIBLE
        binding.sheetRight.podLetter.visibility = View.GONE
        binding.sheetCase.podIcon.visibility = View.GONE
        binding.sheetCase.podLetter.visibility = View.VISIBLE
    }

    private fun bindPod(label: TextView, pct: TextView, bar: ProgressBar,
                        name: String, value: Int, charging: Boolean) {
        label.text = name
        if (value <= 0) { pct.text = "--"; bar.progress = 0 }
        else { pct.text = if (charging) "$value% ⚡" else "$value%"; bar.progress = value }
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
