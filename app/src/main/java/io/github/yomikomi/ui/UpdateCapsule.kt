package io.github.yomikomi.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog
import io.github.yomikomi.update.ApkInstaller
import io.github.yomikomi.update.AppUpdater
import io.github.yomikomi.update.UpdatePhase

@Composable
internal fun UpdateCapsule() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        RunLog.info(LogModule.UPDATE, "installer_returned", "code" to it.resultCode)
    }
    val openInstaller = {
        AppUpdater.installation(context)?.let { apk ->
            try {
                installer.launch(ApkInstaller.intent(context, apk))
                RunLog.info(LogModule.UPDATE, "installer_opened")
            } catch (error: Exception) {
                RunLog.error(LogModule.UPDATE, "installer_failed", error)
                Toast.makeText(context, "无法打开安装器", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = ApkInstaller.allowed(context)
        RunLog.info(LogModule.PERMISSION, "install_result", "granted" to if (granted) 1 else 0)
        if (granted) openInstaller()
    }
    val install = {
        if (AppUpdater.installation(context) != null) {
            try {
                if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                    RunLog.info(LogModule.PERMISSION, "request_install")
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                } else openInstaller()
            } catch (error: Exception) {
                RunLog.error(LogModule.UPDATE, "permission_failed", error)
                Toast.makeText(context, "无法打开安装设置", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val state = AppUpdater.status
    LaunchedEffect(state.phase, foreground) {
        if (state.phase == UpdatePhase.Downloaded && foreground) install()
    }
    val label = when (state.phase) {
        UpdatePhase.Idle -> "检查更新"
        UpdatePhase.Checking -> "检查中"
        UpdatePhase.Downloading -> "下载中 ${state.percent}%"
        UpdatePhase.Verifying -> "校验中"
        UpdatePhase.Downloaded, UpdatePhase.Ready -> "安装更新"
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "update_press")
    val surface by animateColorAsState(if (pressed) yomiColors.surface else yomiColors.surfaceLow, tween(120), label = "update_surface")
    val enabled = state.phase == UpdatePhase.Idle || state.phase == UpdatePhase.Ready
    Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .animateContentSize(tween(160))
        .shadow(2.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
        .clip(CircleShape).background(surface)
        .border(if (focused) 1.5.dp else 0.5.dp, if (focused) yomiColors.accent else yomiColors.line.copy(alpha = 0.6f), CircleShape)
        .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button) {
            if (state.phase == UpdatePhase.Ready) install() else AppUpdater.check(context)
        }.heightIn(min = 48.dp).widthIn(min = 96.dp).padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (pressed || focused) yomiColors.ink else yomiColors.muted, fontSize = 14.sp, lineHeight = 20.sp)
    }
}
