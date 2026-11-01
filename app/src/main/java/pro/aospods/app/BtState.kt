package pro.aospods.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAssignedNumbers
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/** 이 폰에 연결된 오디오 기기(에어팟) 상태. '연결돼 있을 때만' 배터리를 처리하는 기준. */
object BtState {
    /** 연결된 오디오 기기 이름. null = 연결 안 됨. */
    val connectedName = MutableStateFlow<String?>(null)

    /** 연결된 오디오 기기 객체(AACP 연결용, 화면에는 표시하지 않음). */
    @Volatile var connectedDevice: BluetoothDevice? = null

    /** 연결된 헤드셋이 블루투스 통화 프로파일(HFP)로 보고하는 '전체' 배터리(0~100). null = 모름.
     *  에어팟은 귀에 끼고 있을 땐 BLE 광고를 거의 안 보내므로, 연결 중엔 이 값이 주 정보원. */
    val hfpBattery = MutableStateFlow<Int?>(null)

    const val ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    const val EXTRA_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"

    /** 진단용: 배터리를 마지막으로 어떤 경로로 받았는지 / 왜 못 받았는지. */
    @Volatile var batteryNote: String = "아직 없음"

    /** 시스템이 알고 있는 기기 배터리(숨김 API라 반사로 호출, 막혀 있거나 모르면 null). */
    @SuppressLint("MissingPermission")
    fun readBattery(d: BluetoothDevice): Int? = try {
        // 플레이 갈래는 비공개 API를 건드리지 않음(공개 경로 ①②만 사용)
        if (!BuildConfig.AACP) throw UnsupportedOperationException("play")
        val v = d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int
        if (v in 0..100) { batteryNote = "시스템 조회 $v%"; v }
        else { batteryNote = "시스템 조회: 값 없음($v)"; null }
    } catch (e: Throwable) {
        batteryNote = if (!BuildConfig.AACP) "플레이 버전 · 시스템 조회 안 함" else "시스템 조회 불가(${(e.cause ?: e).javaClass.simpleName})"; null
    }

    /** 에어팟이 HFP로 직접 보내는 AT+IPHONEACCEV=n,k1,v1,... 에서 배터리(키 1, 0~9)를 %로.
     *  공개 API(BluetoothHeadset 제조사 이벤트)로 받으므로 숨김 API 제한과 무관. 없으면 null. */
    fun parseAppleAccEv(args: Array<*>?): Int? {
        if (args == null || args.isEmpty()) return null
        fun num(i: Int) = when (val a = args.getOrNull(i)) {
            is Number -> a.toInt()
            is String -> a.trim().toIntOrNull()
            else -> null
        }
        val pairs = num(0) ?: return null
        for (k in 0 until pairs) {
            val key = num(1 + 2 * k) ?: return null
            val value = num(2 + 2 * k) ?: return null
            if (key == 1 && value in 0..9) return (value + 1) * 10
        }
        return null
    }

    private fun hasConnectPerm(ctx: Context) = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

    /** A2DP(음악) 프로파일로 연결된 기기를 조회해 상태를 갱신. */
    @SuppressLint("MissingPermission")
    fun refresh(ctx: Context) {
        if (!hasConnectPerm(ctx)) return
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) {
            connectedName.value = null; connectedDevice = null; hfpBattery.value = null; return
        }
        try {
            adapter.getProfileProxy(ctx.applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val dev = try { proxy.connectedDevices.firstOrNull() } catch (_: Exception) { null }
                    connectedDevice = dev
                    // 에어팟이 AACP로 알려준 이름이 있으면 그걸 우선(이름 변경 직후 안드로이드는 옛 이름을 캐시함)
                    connectedName.value = dev?.let {
                        AapSession.info.value?.name?.takeIf { n -> n.isNotBlank() } ?: safeName(ctx, it)
                    }
                    // 조회에 실패해도 방송으로 이미 받은 값은 지우지 않음(끊겼을 때만 지움)
                    if (dev == null) hfpBattery.value = null
                    else readBattery(dev)?.let { hfpBattery.value = it }
                    try { adapter.closeProfileProxy(profile, proxy) } catch (_: Exception) {}
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)
        } catch (_: Exception) {}
    }

    /** 기기 이름. MAC 주소는 절대 화면에 노출하지 않음 — 이름이 없으면 일반 명칭. */
    @SuppressLint("MissingPermission")
    fun safeName(ctx: Context, d: BluetoothDevice): String =
        try { if (hasConnectPerm(ctx)) d.name?.takeIf { it.isNotBlank() } ?: "에어팟" else "에어팟" }
        catch (_: Exception) { "에어팟" }

    /** 연결/해제·블루투스 ON/OFF 방송을 받아 즉시 상태를 갱신하는 리시버.
     *  onChange(action, adapterState): adapterState는 ACTION_STATE_CHANGED일 때만 의미 있음. */
    fun receiver(onChange: (action: String?, adapterState: Int) -> Unit) = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_BATTERY -> {
                    val v = i.getIntExtra(EXTRA_BATTERY, -1)
                    if (v in 0..100) { hfpBattery.value = v; batteryNote = "배터리 방송 $v%" }
                }
                BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> {
                    val cmd = i.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD)
                    @Suppress("DEPRECATION")
                    val args = i.extras?.get(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS) as? Array<*>
                    if (cmd == "+IPHONEACCEV") parseAppleAccEv(args)?.let {
                        hfpBattery.value = it; batteryNote = "에어팟 보고(IPHONEACCEV) $it%"
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) {
                        connectedName.value = null; connectedDevice = null; hfpBattery.value = null
                    } else refresh(c)
                }
                else -> refresh(c)
            }
            onChange(i.action, i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1))
        }
    }

    val filter = IntentFilter().apply {
        addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        addAction(ACTION_BATTERY)
        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        // 애플 헤드셋이 보내는 배터리 보고(AT+IPHONEACCEV). 이 방송엔 회사 ID 카테고리가 붙어 있어
        // 필터에 같은 카테고리가 있어야 받을 수 있음(다른 방송은 카테고리가 없어 영향 없음).
        addAction(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT)
        addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + "." +
            BluetoothAssignedNumbers.APPLE)
    }
}
