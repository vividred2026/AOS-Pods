package pro.aospods.app

import android.content.Context
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import pro.aospods.app.databinding.SheetBaseBinding
import pro.aospods.app.databinding.SheetCheckRowBinding

/** 공용 하단 시트 (Figma bottomsheetHeader): 손잡이 · 제목(오른쪽 ✕ 또는 링크) · 본문 · 버튼 2개. */
object Sheets {

    class Sheet(val dialog: BottomSheetDialog, val b: SheetBaseBinding)

    /** 빈 시트를 만든다. 본문은 [Sheet.b].sheetBody에 채우고 show()는 호출한 쪽에서. */
    fun base(ctx: Context, title: String): Sheet {
        val d = BottomSheetDialog(ctx, R.style.ThemeOverlay_AosPods_Sheet)
        val b = SheetBaseBinding.inflate(LayoutInflater.from(d.context))
        b.sheetTitle.text = title
        b.sheetClose.setOnClickListener { d.dismiss() }
        d.setContentView(b.root)
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.behavior.skipCollapsed = true
        return Sheet(d, b)
    }

    /** 제목 오른쪽 링크(밑줄, 보라) — 있으면 ✕ 대신 표시. */
    fun Sheet.link(label: String, onClick: () -> Unit): Sheet {
        b.sheetLink.text = label
        b.sheetLink.paintFlags = b.sheetLink.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        b.sheetLink.visibility = View.VISIBLE
        b.sheetClose.visibility = View.GONE
        b.sheetLink.setOnClickListener { dialog.dismiss(); onClick() }
        return this
    }

    /** 하단 버튼(왼쪽 테두리 · 오른쪽 보라). 버튼이 있으면 본문 아래 여백을 줄인다. */
    fun Sheet.buttons(secondary: String, onSecondary: () -> Unit, primary: String, onPrimary: () -> Unit): Sheet {
        b.sheetButtons.visibility = View.VISIBLE
        b.sheetBody.setPadding(b.sheetBody.paddingLeft, b.sheetBody.paddingTop, b.sheetBody.paddingRight, dp(20))
        b.sheetSecondary.text = secondary
        b.sheetPrimary.text = primary
        b.sheetSecondary.setOnClickListener { onSecondary() }
        b.sheetPrimary.setOnClickListener { onPrimary() }
        return this
    }

    /** 본문 텍스트(14sp, 줄간격 1.2). 반환된 TextView로 내용을 갱신할 수 있다. */
    fun Sheet.text(body: CharSequence): TextView {
        val t = TextView(dialog.context).apply {
            text = body; textSize = 14f
            setTextColor(context.getColor(R.color.ink))
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
        }
        b.sheetBody.addView(t)
        // 긴 진단 로그도 시트가 화면을 넘지 않게
        b.sheetScroll.layoutParams = b.sheetScroll.layoutParams.apply {
            height = (dialog.context.resources.displayMetrics.heightPixels * 0.5f).toInt()
        }
        return t
    }

    /** 단일 선택 시트 (청취 모드 · 제품 선택 · 다크 모드 · 퍼센트 기준). 고르면 바로 닫힘. */
    fun choice(ctx: Context, title: String, items: List<String>, selected: Int, onPick: (Int) -> Unit): Sheet {
        val s = base(ctx, title)
        items.forEachIndexed { i, label ->
            val row = SheetCheckRowBinding.inflate(LayoutInflater.from(s.dialog.context), s.b.sheetBody, false)
            row.checkLabel.text = label
            row.checkIcon.setImageResource(if (i == selected) R.drawable.ic_check_circle_on else R.drawable.ic_check_circle_off)
            row.root.setOnClickListener { s.dialog.dismiss(); onPick(i) }
            if (i > 0) (row.root.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin = s.dp(10)
            s.b.sheetBody.addView(row.root)
        }
        s.dialog.show()
        return s
    }

    private fun Sheet.dp(v: Int) = (v * dialog.context.resources.displayMetrics.density).toInt()
}
