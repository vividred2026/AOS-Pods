package pro.aospods.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** 서비스 동작에 필요한 런타임 권한 (MainActivity · 빠른 설정 타일 공용). */
object Perms {
    /** 필수 권한. 31+는 BLUETOOTH_SCAN(neverForLocation)이라 위치 권한이 필요 없고,
     *  30 이하는 BLE 스캔·기기 검색에 위치 권한이 필요하다(FINE은 COARSE와 함께 요청해야 함). */
    fun required(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
            list += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    fun missing(ctx: Context) = required().filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }

    fun ok(ctx: Context) = missing(ctx).isEmpty()
}
