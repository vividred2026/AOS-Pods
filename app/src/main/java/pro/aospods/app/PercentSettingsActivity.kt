package pro.aospods.app

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import pro.aospods.app.databinding.ActivityPercentBinding

class PercentSettingsActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val b = ActivityPercentBinding.inflate(layoutInflater)
        setContentView(b.root)
        val prefs = Prefs(this)
        b.appbar.appbarTitle.text = "아이콘 퍼센트 설정"
        b.appbar.backArrow.setOnClickListener { finish() }
        b.typeValue.text = prefs.percentType
        b.swDisconnected.isChecked = prefs.showWhenDisconnected
        b.swDisconnected.setOnCheckedChangeListener { _, v -> prefs.showWhenDisconnected = v }
        b.rowType.setOnClickListener {
            val items = Prefs.PERCENT_TYPES.toTypedArray()
            AlertDialog.Builder(this).setTitle("표시할 퍼센트 기준")
                .setSingleChoiceItems(items, items.indexOf(prefs.percentType)) { d, w ->
                    prefs.percentType = items[w]; b.typeValue.text = items[w]; d.dismiss()
                }.setNegativeButton("닫기", null).show()
        }
    }

    override fun onPause() {
        super.onPause()
        ScanService.applySettings(this)   // 바뀐 설정을 알림에 즉시 반영
    }
}
