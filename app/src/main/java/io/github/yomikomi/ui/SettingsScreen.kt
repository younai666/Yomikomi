package io.github.yomikomi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(state: UiState, open: (Panel) -> Unit) {
    SectionLabel("翻译设置")
    PreferenceRow("本地模型", onClick = { open(Panel.Models) })
    Hairline()
    PreferenceRow("第三方 API", value = if (state.api.enabled) state.api.provider.title else "未启用", onClick = { open(Panel.Api) })

    Spacer(Modifier.height(24.dp))
    SectionLabel("Overlay")
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("不透明度", Modifier.weight(1f), color = yomiColors.ink, fontSize = 16.sp)
        Text("${(state.opacity * 100).roundToInt()}%", color = yomiColors.muted, fontSize = 14.sp)
    }
    Slider(
        value = state.opacity,
        onValueChange = { state.opacity = it },
        valueRange = 0.3f..1f,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Overlay 不透明度" },
        thumb = {
            Box(Modifier.size(20.dp).background(yomiColors.surface, CircleShape)
                .border(1.5.dp, yomiColors.accent, CircleShape))
        },
        track = {
            val colors = yomiColors
            Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                val middle = size.height / 2
                drawLine(colors.line, Offset(0f, middle), Offset(size.width, middle), size.height, StrokeCap.Round)
                drawLine(colors.accent, Offset(0f, middle), Offset(size.width * ((state.opacity - 0.3f) / 0.7f), middle), size.height, StrokeCap.Round)
            }
        },
    )
    PreferenceRow("字号", trailing = {
        QuietIconButton(Glyph.Minus, "减小 Overlay 字号", { state.textSize -= 1 }, enabled = state.textSize > 12)
        Text("${state.textSize}", color = yomiColors.ink, fontSize = 15.sp, modifier = Modifier.width(24.dp))
        QuietIconButton(Glyph.Plus, "增大 Overlay 字号", { state.textSize += 1 }, enabled = state.textSize < 28)
    })
    Hairline()
    QuietSwitch("自动收起", state.autoCollapse) { state.autoCollapse = it }
    Spacer(Modifier.height(24.dp))
    Hairline()
    RuntimeLogRow()
}
