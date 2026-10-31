package pro.aospods.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

/** 앱 설정. 와이어프레임(메인 설정 M-00)의 항목을 담은 미니멀 버전. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("aospods", Context.MODE_PRIVATE)

    /** S-02 권한 안내를 마쳤는지(동의 또는 나중에). */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit { putBoolean("onboarded", v) }

    var serviceEnabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit { putBoolean("enabled", v) }

    var model: String
        get() = sp.getString("model", "에어팟 4") ?: "에어팟 4"
        set(v) = sp.edit { putString("model", v) }

    var notification: Boolean
        get() = sp.getBoolean("notification", true)
        set(v) = sp.edit { putBoolean("notification", v) }

    var percentInIcon: Boolean
        get() = sp.getBoolean("percent", true)
        set(v) = sp.edit { putBoolean("percent", v) }

    var popup: Boolean
        get() = sp.getBoolean("popup", true)
        set(v) = sp.edit { putBoolean("popup", v) }

    // STEP 3 착용 감지 자동 일시정지·재생 (오디오 포커스 방식)
    var autoPause: Boolean      // AirPods를 빼면 재생 일시정지
        get() = sp.getBoolean("auto_pause", false)
        set(v) = sp.edit { putBoolean("auto_pause", v) }
    var autoResume: Boolean     // 다시 끼면 재생 재개
        get() = sp.getBoolean("auto_resume", false)
        set(v) = sp.edit { putBoolean("auto_resume", v) }

    /** AppCompatDelegate.MODE_NIGHT_* */
    var nightMode: Int
        get() = sp.getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(v) = sp.edit { putInt("night", v) }

    // N-01 알림 설정
    var bigNotif: Boolean
        get() = sp.getBoolean("big_notif", true)
        set(v) = sp.edit { putBoolean("big_notif", v) }
    var smallNotif: Boolean
        get() = sp.getBoolean("small_notif", false)
        set(v) = sp.edit { putBoolean("small_notif", v) }

    // N-02 아이콘 퍼센트 설정
    var percentType: String
        get() = sp.getString("percent_type", PERCENT_TYPES[0]) ?: PERCENT_TYPES[0]
        set(v) = sp.edit { putString("percent_type", v) }
    var showWhenDisconnected: Boolean
        get() = sp.getBoolean("show_disconnected", true)
        set(v) = sp.edit { putBoolean("show_disconnected", v) }

    // N-03 팝업 설정
    var popupTitle: String
        get() = sp.getString("popup_title", "") ?: ""
        set(v) = sp.edit { putString("popup_title", v) }
    var popupTitleSize: Int
        get() = sp.getInt("popup_title_size", 22)
        set(v) = sp.edit { putInt("popup_title_size", v) }
    var popupCorner: Int
        get() = sp.getInt("popup_corner", 24)
        set(v) = sp.edit { putInt("popup_corner", v) }
    var popupMargin: Int
        get() = sp.getInt("popup_margin", 20)
        set(v) = sp.edit { putInt("popup_margin", v) }
    var popupDim: Int
        get() = sp.getInt("popup_dim", 40)
        set(v) = sp.edit { putInt("popup_dim", v) }
    var hidePopupWhenInEar: Boolean
        get() = sp.getBoolean("hide_popup_inear", false)
        set(v) = sp.edit { putBoolean("hide_popup_inear", v) }

    /** 에어팟이 마지막으로 보낸 기기 정보(AACP 0x1D). 에어팟은 연결 직후에만 보내고 요청할 수 없어서 저장해 둔다.
     *  키는 기기 주소(화면에는 표시하지 않음). 값은 필드를 \u001F로 이은 문자열. */
    fun aapInfo(deviceKey: String): Aap.Msg.Info? {
        val f = (sp.getString("aap_info_$deviceKey", null) ?: return null).split('\u001F')
        if (f.size < 7) return null
        return Aap.Msg.Info(f[0], f[1], f[2], f[3], f[4], f[5], f[6])
    }
    fun saveAapInfo(deviceKey: String, i: Aap.Msg.Info) = sp.edit {
        putString("aap_info_$deviceKey", listOf(i.name, i.modelNumber, i.manufacturer, i.serial,
            i.firmware, i.leftSerial, i.rightSerial).joinToString("\u001F"))
    }

    companion object {
        val MODELS = listOf("에어팟 1·2", "에어팟 프로", "파워비츠 프로", "에어팟 3", "에어팟 4")
        val PERCENT_TYPES = listOf("가장 닳은 기기 (케이스 포함)", "왼쪽", "오른쪽", "케이스")

        val NIGHT_LABELS = listOf("라이트", "다크", "시스템 기본값")
        val NIGHT_VALUES = listOf(
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES,
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        )
    }
}
