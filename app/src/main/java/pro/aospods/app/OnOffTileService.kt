package pro.aospods.app

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/** 빠른 설정 타일로 서비스 ON/OFF (와이어프레임 O-02 · OnOffTileService). */
@RequiresApi(Build.VERSION_CODES.N)
class OnOffTileService : TileService() {

    private val prefs by lazy { Prefs(this) }

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val turningOn = !prefs.serviceEnabled
        // 권한이 없으면 앱을 열어 권한을 요청하도록 안내
        if (turningOn && !Perms.ok(this)) { openApp(); return }
        prefs.serviceEnabled = turningOn
        if (turningOn) {
            // 백그라운드 시작 제한 등으로 실패하면 앱을 열어 거기서 시작
            if (!ScanService.start(this)) { openApp(); return }
        } else { ScanService.stop(this); PodsRepo.update(null) }
        render()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // <34는 Intent 오버로드만 가능
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }

    /** 타일 라벨 · 상태 · 보조 문구를 현재 상태에 맞춰 갱신. */
    private fun render() {
        val tile = qsTile ?: return
        val on = prefs.serviceEnabled
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "aosPods"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_pods)
        if (Build.VERSION.SDK_INT >= 29) {
            val pods = PodsRepo.pods.value
            tile.subtitle = when {
                !on -> "꺼짐"
                pods != null && pods.hasAny -> ScanService.batteryLine(pods)
                BtState.hfpBattery.value != null -> "에어팟 ${BtState.hfpBattery.value}%"
                else -> "AirPods 찾는 중…"
            }
        }
        tile.updateTile()
    }

    companion object {
        /** 설정 변경 시 타일을 즉시 갱신하도록 요청. */
        fun refresh(ctx: android.content.Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestListeningState(ctx, ComponentName(ctx, OnOffTileService::class.java))
                } catch (_: Exception) {}
            }
        }
    }
}
