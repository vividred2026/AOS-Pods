package pro.aospods.app

import android.os.Bundle
import android.widget.EditText
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import pro.aospods.app.databinding.ActivityPopupBinding
import pro.aospods.app.databinding.SeekRowBinding

class PopupSettingsActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val b = ActivityPopupBinding.inflate(layoutInflater)
        setContentView(b.root)
        val prefs = Prefs(this)
        b.appbar.appbarTitle.text = "팝업 설정"
        b.appbar.backArrow.setOnClickListener { finish() }

        fun titleCaption() =
            if (prefs.popupTitle.isBlank()) "비우면 기기 이름을 표시" else prefs.popupTitle
        b.titleValue.text = titleCaption()
        b.rowTitle.setOnClickListener {
            val input = EditText(this).apply { setText(prefs.popupTitle); hint = "기기 이름" }
            AlertDialog.Builder(this).setTitle("제목 문구").setView(input)
                .setPositiveButton("저장") { _, _ ->
                    prefs.popupTitle = input.text.toString().trim(); b.titleValue.text = titleCaption()
                }.setNegativeButton("취소", null).show()
        }

        seek(b.seekSize, "제목 글자 크기", 14, 30, prefs.popupTitleSize) { prefs.popupTitleSize = it }
        seek(b.seekCorner, "모서리 둥글기", 0, 32, prefs.popupCorner) { prefs.popupCorner = it }
        seek(b.seekMargin, "여백", 0, 40, prefs.popupMargin) { prefs.popupMargin = it }
        seek(b.seekDim, "배경 투명도", 0, 100, prefs.popupDim) { prefs.popupDim = it }

        b.swHide.isChecked = prefs.hidePopupWhenInEar
        b.swHide.setOnCheckedChangeListener { _, v -> prefs.hidePopupWhenInEar = v }
    }

    private fun seek(row: SeekRowBinding, title: String, min: Int, max: Int,
                     value: Int, save: (Int) -> Unit) {
        row.seekTitle.text = title
        row.seekBar.max = max - min
        row.seekBar.progress = (value - min).coerceIn(0, max - min)
        row.seekValue.text = value.toString()
        row.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                val v = min + p; row.seekValue.text = v.toString(); save(v)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
}
