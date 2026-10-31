package pro.aospods.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import pro.aospods.app.databinding.ActivityNotifBinding

class NotifSettingsActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val b = ActivityNotifBinding.inflate(layoutInflater)
        setContentView(b.root)
        val prefs = Prefs(this)
        b.appbar.appbarTitle.text = "알림 설정"
        b.appbar.backArrow.setOnClickListener { finish() }
        b.swBig.isChecked = prefs.bigNotif
        b.swSmall.isChecked = prefs.smallNotif
        // 큰 알림 / 작은 알림은 둘 중 하나만 켜지도록
        b.swBig.setOnCheckedChangeListener { _, v ->
            prefs.bigNotif = v; if (v && b.swSmall.isChecked) b.swSmall.isChecked = false
        }
        b.swSmall.setOnCheckedChangeListener { _, v ->
            prefs.smallNotif = v; if (v && b.swBig.isChecked) b.swBig.isChecked = false
        }
    }

    override fun onPause() {
        super.onPause()
        ScanService.applySettings(this)   // 바뀐 설정을 알림에 즉시 반영
    }
}
