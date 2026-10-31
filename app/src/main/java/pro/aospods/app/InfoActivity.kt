package pro.aospods.app

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import pro.aospods.app.databinding.ActivityInfoBinding

/** 연결된 에어팟 정보(AACP 0x1D) 표시 · 이름 변경(AACP 0x1A). AACP가 연결돼 있어야 동작. */
class InfoActivity : AppCompatActivity() {
    private lateinit var b: ActivityInfoBinding

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityInfoBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.appbar.appbarTitle.text = "에어팟 정보"
        b.appbar.backArrow.setOnClickListener { finish() }
        b.rowName.setOnClickListener { showRename() }

        lifecycleScope.launch {
            combine(AapSession.connected, AapSession.info, AapSession.pendingName) { c, i, p -> Triple(c, i, p) }
                .collect { (connected, info, pending) ->
                    b.infoStatus.text = when {
                        !connected -> "에어팟 전용 채널이 연결돼 있지 않아요. 에어팟을 연결하면 표시돼요."
                        info == null -> "에어팟 정보를 받는 중…"
                        else -> "에어팟이 직접 알려준 정보예요."
                    }
                    b.rowName.isEnabled = connected
                    val name = info?.name?.ifBlank { null }
                    b.nameValue.text = when {
                        pending != null -> "$pending (변경 요청함 · 에어팟 확인 대기)"
                        name != null -> name
                        else -> "--"
                    }
                    b.modelValue.text = info?.modelNumber.orDash()
                    b.firmwareValue.text = info?.firmware.orDash()
                    b.makerValue.text = info?.manufacturer.orDash()
                    b.serialValue.text = info?.serial.orDash()
                    b.budSerialValue.text = if (info == null) "--"
                        else "${info.leftSerial.orDash()} · ${info.rightSerial.orDash()}"
                }
        }
        lifecycleScope.launch {
            PodsRepo.pods.collect { p ->
                b.batteryValue.text = if (p == null || !p.hasAny) "--" else ScanService.batteryLine(p)
            }
        }
        lifecycleScope.launch {
            combine(AapSession.connected, AapSession.listenMode) { c, m -> c to m }
                .collect { (c, m) -> b.listenInfoValue.text = if (c) Aap.modeName(m) else "--" }
        }
    }

    private fun String?.orDash() = if (this.isNullOrBlank()) "--" else this

    private fun showRename() {
        if (!AapSession.connected.value) { toast("에어팟을 연결한 뒤 바꿀 수 있어요"); return }
        val current = AapSession.pendingName.value ?: AapSession.info.value?.name.orEmpty()
        val input = EditText(this).apply {
            setText(current); setSelection(text.length)
            hint = "에어팟 이름"
            filters = arrayOf(InputFilter.LengthFilter(Aap.NAME_MAX_BYTES))
        }
        AlertDialog.Builder(this).setTitle("이름 변경")
            .setMessage("최대 ${Aap.NAME_MAX_BYTES}바이트(한글 약 10자)")
            .setView(input)
            .setPositiveButton("변경") { _, _ ->
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() -> toast("이름을 입력해 주세요")
                    Aap.renamePacket(name) == null -> toast("이름이 너무 길어요 (최대 ${Aap.NAME_MAX_BYTES}바이트)")
                    !AapSession.rename(name) -> toast("에어팟과 연결이 끊겨 바꾸지 못했어요")
                    else -> {
                        trySetAlias(name)
                        toast("이름 변경을 요청했어요. 폰의 블루투스 목록 이름은 에어팟을 다시 연결(또는 재페어링)해야 바뀔 수 있어요")
                    }
                }
            }
            .setNegativeButton("취소", null).show()
    }

    /** 폰의 블루투스 목록에 보이는 이름(별칭)도 바꿔 보기. 안드로이드 12+는 동반 기기 연결이 없으면
     *  거부될 수 있어 실패는 조용히 무시(에어팟 쪽 이름 변경과는 무관). */
    @SuppressLint("MissingPermission")
    private fun trySetAlias(name: String) {
        val dev = BtState.connectedDevice ?: return
        try { if (Build.VERSION.SDK_INT >= 31) dev.setAlias(name) } catch (_: Throwable) {}
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
