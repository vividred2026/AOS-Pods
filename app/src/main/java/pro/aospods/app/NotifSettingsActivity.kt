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
        b.swBig.setOnCheckedChangeListener { _, v -> prefs.bigNotif = v }
        b.swSmall.setOnCheckedChangeListener { _, v -> prefs.smallNotif = v }
    }
}
