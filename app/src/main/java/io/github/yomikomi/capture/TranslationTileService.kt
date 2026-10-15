package io.github.yomikomi.capture

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import io.github.yomikomi.AppRuntime
import io.github.yomikomi.MainActivity

@RequiresApi(24)
class TranslationTileService : TileService() {
    override fun onTileAdded() { rememberAdded(true) }
    override fun onTileRemoved() { rememberAdded(false) }
    private fun rememberAdded(value: Boolean) {
        AppRuntime.tileAdded = value
        getSharedPreferences("runtime", 0).edit().putBoolean("tile", value).apply()
    }
    override fun onStartListening() {
        rememberAdded(true)
        qsTile?.apply {
            state = if (AppRuntime.pipelinePhase == "就绪") Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            label = "Yomikomi"
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (AppRuntime.serviceConnected) AppRuntime.pipelinePhase else "请启用无障碍服务"
            updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { activate() } else activate()
    }
    @SuppressLint("StartActivityAndCollapseDeprecated") // Intent overload is only used below API 34.
    private fun activate() {
        if (Build.VERSION.SDK_INT < 30 || ScreenCaptureService.current == null) {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            else { @Suppress("DEPRECATION") startActivityAndCollapse(intent) }
        } else ScreenCaptureService.current?.toggle()
    }
    companion object {
        fun refresh(context: Context) {
            if (Build.VERSION.SDK_INT >= 24) requestListeningState(context, ComponentName(context, TranslationTileService::class.java))
        }
    }
}
