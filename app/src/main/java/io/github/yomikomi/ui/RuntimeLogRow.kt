package io.github.yomikomi.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yomikomi.AppRuntime
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun RuntimeLogRow() {
    val context = LocalContext.current
    var exporting by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) {
            exporting = false
            RunLog.info(LogModule.EXPORT, "cancelled")
        } else RunLog.export({ checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) }) { success ->
            AppRuntime.main.post {
                exporting = false
                Toast.makeText(context, if (success) "日志已导出" else "日志导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    PreferenceRow("运行日志", trailing = {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = !exporting, role = Role.Button) {
            exporting = true
            RunLog.info(LogModule.ACTION, "export_logs")
            try {
                save.launch("Yomikomi-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.txt")
            } catch (error: Exception) {
                exporting = false
                RunLog.error(LogModule.EXPORT, "picker_failed", error)
                Toast.makeText(context, "无法打开文件保存器", Toast.LENGTH_SHORT).show()
            }
        }, contentAlignment = Alignment.CenterEnd) {
            Text("下载", color = yomiColors.muted.copy(alpha = if (exporting) 0.4f else 1f), fontSize = 14.sp)
        }
    })
}
