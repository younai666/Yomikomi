package io.github.yomikomi.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HomeScreen(state: UiState, open: (Panel) -> Unit) {
    SectionLabel("语言方向")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        LanguageEntry(state.source, "源语言", Modifier.weight(1f, fill = false)) { open(Panel.Source) }
        QuietIconButton(Glyph.Swap, "交换翻译方向", {
            val previous = state.source
            state.source = state.target
            state.target = previous
        }, enabled = state.source != "自动检测" && state.target in listOf("日语", "英语", "俄语"))
        LanguageEntry(state.target, "目标语言", Modifier.weight(1f, fill = false)) { open(Panel.Target) }
    }

    Spacer(Modifier.height(32.dp))
    SectionLabel(if (state.api.enabled) "翻译引擎" else "本地模型")
    HomeGroup {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            ModelChoice("文字识别", state.ocrModel, Modifier.weight(1f)) { open(Panel.Ocr) }
            Box(Modifier.width(12.dp).height(36.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(0.5.dp).fillMaxHeight().background(yomiColors.line))
            }
            ModelChoice(if (state.api.enabled) "第三方 API" else "离线翻译", state.translationModel, Modifier.weight(1f)) {
                open(if (state.api.enabled) Panel.Api else Panel.Translation)
            }
        }
        Hairline(Modifier.padding(horizontal = 12.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.api.enabled) "翻译方式" else "推理设备", Modifier.weight(1f), fontSize = 13.sp, color = yomiColors.muted)
            Text(if (state.api.enabled) "云端" else "CPU", fontSize = 13.sp, color = yomiColors.muted)
        }
    }

    Spacer(Modifier.height(28.dp))
    HomeGroup {
        ServiceControl("快捷磁贴", if (state.tileAdded) "已添加" else "未添加", state.tileAdded,
            if (state.tileAdded) Glyph.Settings else Glyph.Plus) { open(Panel.Tile) }
        Hairline(Modifier.padding(horizontal = 12.dp))
        ServiceControl("无障碍服务", if (state.serviceEnabled) "已启用" else "未启用", state.serviceEnabled,
            Glyph.Next) { open(Panel.Service) }
    }
}

@Composable
private fun HomeGroup(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(yomiColors.surfaceLow)
        .border(0.5.dp, yomiColors.line.copy(alpha = 0.6f), shape).padding(8.dp), content = content)
}

@Composable
private fun LanguageEntry(text: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val color by animateColorAsState(if (pressed || focused) yomiColors.accent else yomiColors.ink, tween(120), label = "language")
    Column(modifier.width(IntrinsicSize.Max).clip(RoundedCornerShape(8.dp))
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "$label：$text" }) {
        Row(Modifier.heightIn(min = 72.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, Modifier.weight(1f, fill = false), fontFamily = FontFamily.Serif,
                fontSize = 28.sp, lineHeight = 40.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LineIcon(Glyph.Down, Modifier.size(12.dp), yomiColors.muted)
        }
        Hairline()
    }
}

@Composable
private fun ModelChoice(title: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.heightIn(min = 96.dp).clip(RoundedCornerShape(12.dp))
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "$title：$value" }.padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Text(title, fontSize = 13.sp, lineHeight = 20.sp, color = yomiColors.muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(value, Modifier.weight(1f), fontSize = 18.sp, lineHeight = 26.sp, fontFamily = FontFamily.Serif,
                color = yomiColors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LineIcon(Glyph.Next, Modifier.size(14.dp), yomiColors.muted)
        }
    }
}

@Composable
private fun ServiceControl(title: String, status: String, active: Boolean, glyph: Glyph,
    onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), fontSize = 15.sp, color = yomiColors.ink)
        Status(status, active)
        LineIcon(glyph, Modifier.size(16.dp), yomiColors.muted)
    }
}
