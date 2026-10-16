package io.github.yomikomi.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yomikomi.translation.PromptMode
import io.github.yomikomi.translation.PromptSettings
import io.github.yomikomi.translation.TranslationPrompts

@Composable
internal fun CustomPromptEditor(settings: PromptSettings, height: Dp, onSave: (PromptSettings) -> Unit) {
    var draft by rememberSaveable(settings.customTemplate, settings.mode, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(settings.customTemplate.ifBlank { TranslationPrompts.template(settings.mode) }))
    }
    var error by remember { mutableStateOf<String?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(if (focused) yomiColors.accent else yomiColors.line,
        tween(YomiMotion.change, easing = YomiMotion.ease), label = "editor_focus")
    BasicTextField(
        value = draft,
        onValueChange = { draft = it; error = null },
        textStyle = TextStyle(color = yomiColors.ink, fontSize = 14.sp, lineHeight = 23.sp),
        cursorBrush = SolidColor(yomiColors.accent),
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp))
            .background(yomiColors.surfaceLow).border(0.5.dp, border, RoundedCornerShape(12.dp))
            .padding(14.dp).semantics { contentDescription = "自定义翻译提示词" },
    )
    QuietContent(error, Modifier.fillMaxWidth()) { message ->
        if (message != null) Column { Spacer(Modifier.height(12.dp)); Text(message, color = yomiColors.accent, fontSize = 13.sp) }
    }
    Spacer(Modifier.height(20.dp))
    ActionButton("完成", {
        error = TranslationPrompts.validationError(draft.text)
        if (error == null) {
            onSave(PromptSettings(PromptMode.Custom, draft.text))
        }
    }, Modifier.fillMaxWidth())
}
