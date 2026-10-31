package pro.aospods.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat

/** 상태바 아이콘용 퍼센트 숫자 이미지 (디자인 O-02: 5% 단위). */
object PercentIcon {
    /** 0~100 값을 5% 단위로 내림. */
    fun step5(pct: Int): Int = (pct.coerceIn(0, 100) / 5) * 5

    fun icon(pct: Int): IconCompat {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val text = step5(pct).toString()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE        // 상태바 아이콘은 알파값만 쓰고 시스템이 색을 입힘
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = when (text.length) { 1 -> 80f; 2 -> 72f; else -> 52f }
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        c.drawText(text, size / 2f, y, paint)
        return IconCompat.createWithBitmap(bmp)
    }
}
