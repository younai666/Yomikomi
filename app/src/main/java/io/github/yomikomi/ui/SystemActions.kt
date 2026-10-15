package io.github.yomikomi.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import io.github.yomikomi.AppRuntime
import io.github.yomikomi.R
import io.github.yomikomi.capture.TranslationTileService

internal fun openAccessibility(context: Context) {
    if (Build.VERSION.SDK_INT < 30) {
        Toast.makeText(context, "截屏翻译需要 Android 11 或更新版本", Toast.LENGTH_LONG).show()
        return
    }
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

internal fun addTranslationTile(context: Context) {
    if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, TranslationTileService::class.java), "Yomikomi",
            Icon.createWithResource(context, R.drawable.ic_tile), context.mainExecutor,
        ) { result ->
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                AppRuntime.tileAdded = true
            }
        }
    } else Toast.makeText(context, "请在快捷设置的编辑界面添加 Yomikomi 磁贴", Toast.LENGTH_LONG).show()
}
