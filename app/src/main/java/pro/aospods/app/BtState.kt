package pro.aospods.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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

    /** 연결된 헤드셋이 블루투스 통화 프로파일(HFP)로 보고하는 '전체' 배터리(0~100). null = 모름.
     *  에어팟은 귀에 끼고 있을 땐 BLE 광고를 거의 안 보내므로, 연결 중엔 이 값이 주 정보원. */
    val hfpBattery = MutableStateFlow<Int?>(null)

    const val ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    const val EXTRA_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"

    /** 시스템이 알고 있는 기기 배터리(숨김 API라 반사로 호출, 실패하면 null). */
    @SuppressLint("MissingPermission")
    fun readBattery(d: BluetoothDevice): Int? = try {
        (d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int).takeIf { it in 0..100 }
    } catch (_: Throwable) { null }

    private fun hasConnectPerm(ctx: Context) = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

    /** A2DP(음악) 프로파일로 연결된 기기를 조회해 상태를 갱신. */
    @SuppressLint("MissingPermission")
    fun refresh(ctx: Context) {
        if (!hasConnectPerm(ctx)) return
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) { connectedName.value = null; hfpBattery.value = null; return }
        try {
            adapter.getProfileProxy(ctx.applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val dev = try { proxy.connectedDevices.firstOrNull() } catch (_: Exception) { null }
                    connectedName.value = dev?.let { safeName(ctx, it) }
                    hfpBattery.value = dev?.let { readBattery(it) }
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
                    hfpBattery.value = v.takeIf { it in 0..100 }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) {
                        connectedName.value = null; hfpBattery.value = null
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
    }
}
