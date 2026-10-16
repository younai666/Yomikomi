package io.github.yomikomi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import io.github.yomikomi.AppRuntime
import io.github.yomikomi.models.ModelCatalog
import io.github.yomikomi.models.ModelDownloadService
import io.github.yomikomi.models.ModelSpec
import io.github.yomikomi.translation.PromptMode
import io.github.yomikomi.translation.PromptSettings
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsPanel(requested: Panel?, state: UiState, onDismiss: () -> Unit, onOpen: (Panel) -> Unit) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = requested != null
    var previous by remember { mutableStateOf(Panel.Source) }
    if (requested != null) SideEffect { previous = requested }
    if (!visibility.currentState && !visibility.targetState) return

    val apiDraft = remember { ApiDraft(state.api) }
    val transition = rememberTransition(visibility, label = "panel_visibility")
    val reveal = transition.animateFloat(transitionSpec = {
        tween(if (targetState) YomiMotion.page else YomiMotion.exit, easing = YomiMotion.ease)
    }, label = "panel_reveal") { if (it) 1f else 0f }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        DisposableEffect(window) {
            window.setDimAmount(0f)
            window.setWindowAnimations(0)
            onDispose { }
        }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize()
                .drawBehind { drawRect(Color.Black.copy(alpha = 0.32f * reveal.value)) }
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } })
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp),
                contentAlignment = Alignment.Center) {
                val availableHeight = maxHeight
                val editorHeight = (availableHeight * 0.45f).coerceIn(120.dp, 340.dp)
                Box(Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(max = availableHeight)
                    .graphicsLayer {
                        alpha = reveal.value
                        scaleX = 0.985f + reveal.value * 0.015f; scaleY = scaleX
                        translationY = (1f - reveal.value) * 6.dp.toPx()
                    }
                    .clip(RoundedCornerShape(20.dp)).background(yomiColors.surface)
                    .border(0.5.dp, yomiColors.line, RoundedCornerShape(20.dp))
                    .pointerInput(Unit) { detectTapGestures { } }) {
                    QuietContent(requested ?: previous, Modifier.fillMaxWidth(), Alignment.TopStart) { panel ->
                        Column(Modifier.heightIn(max = availableHeight).verticalScroll(rememberScrollState()).padding(20.dp)) {
                            PanelContent(panel, state, apiDraft, editorHeight, onDismiss, onOpen)
                        }
                    }
                }
            }
            if (requested == null) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
        }
    }
}

@Composable
private fun PanelContent(panel: Panel, state: UiState, apiDraft: ApiDraft, editorHeight: Dp,
    onDismiss: () -> Unit, onOpen: (Panel) -> Unit) {
    val context = LocalContext.current
    val title = when (panel) {
        Panel.Source -> "源语言"
        Panel.Target -> "目标语言"
        Panel.Ocr -> "文字识别"
        Panel.Translation -> "离线翻译"
        Panel.Tile -> "快捷磁贴"
        Panel.Service -> "无障碍服务"
        Panel.Api -> "第三方 API"
        Panel.Models -> "本地模型"
        Panel.Prompts, Panel.CustomPrompt, Panel.ApiPrompts, Panel.ApiCustomPrompt -> "翻译提示词"
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontFamily = FontFamily.Serif, fontSize = 24.sp, color = yomiColors.ink)
        QuietIconButton(Glyph.Close, "关闭$title", onDismiss)
    }
    when (panel) {
        Panel.Source, Panel.Target -> {
            val source = panel == Panel.Source
            val languages = if (source) listOf("自动检测", "日语", "英语", "俄语")
                else listOf("简体中文", "繁體中文", "日语", "英语", "俄语", "韩语", "法语", "德语", "西班牙语")
            Options(languages,
                if (source) state.source else state.target, compact = true) {
                if (source) state.source = it else state.target = it
                onDismiss()
            }
        }
        Panel.Ocr -> ModelDownloadControl(ModelCatalog.ocr, state.source)
        Panel.Translation -> ModelDownloadControl(ModelCatalog.translation, state.source)
        Panel.Tile -> {
            ActionButton(if (state.tileAdded) "已添加" else "添加磁贴", {
                addTranslationTile(context)
                onDismiss()
            }, Modifier.fillMaxWidth())
        }
        Panel.Service -> {
            QuietSwitch("启用服务", state.serviceEnabled) { openAccessibility(context); onDismiss() }
            Spacer(Modifier.height(16.dp))
        }
        Panel.Api -> ApiForm(state, apiDraft, onDismiss, onOpen)
        Panel.Models -> {
            PreferenceRow("文字识别", value = AppRuntime.status(ModelCatalog.ocr).phase, onClick = { onOpen(Panel.Ocr) })
            Hairline()
            PreferenceRow("Hy-MT2 · 1.8B", value = AppRuntime.status(ModelCatalog.translation).phase, onClick = { onOpen(Panel.Translation) })
            Hairline()
            PreferenceRow("翻译提示词", value = state.promptMode.title, onClick = { onOpen(Panel.Prompts) })
        }
        Panel.Prompts -> Options(PromptMode.entries.map { it.title }, state.promptMode.title) { title ->
            val mode = PromptMode.entries.single { it.title == title }
            if (mode == PromptMode.Custom) onOpen(Panel.CustomPrompt)
            else { state.promptMode = mode; onOpen(Panel.Models) }
        }
        Panel.CustomPrompt -> key(panel) {
            CustomPromptEditor(PromptSettings(state.promptMode, state.customPrompt), height = editorHeight) {
                state.promptMode = it.mode; state.customPrompt = it.customTemplate
                onOpen(Panel.Models)
            }
        }
        Panel.ApiPrompts -> Options(PromptMode.entries.map { it.title }, apiDraft.prompt.mode.title) { title ->
            val mode = PromptMode.entries.single { it.title == title }
            if (mode == PromptMode.Custom) onOpen(Panel.ApiCustomPrompt)
            else { apiDraft.prompt = apiDraft.prompt.copy(mode = mode); onOpen(Panel.Api) }
        }
        Panel.ApiCustomPrompt -> key(panel) {
            CustomPromptEditor(apiDraft.prompt, height = editorHeight) {
                apiDraft.prompt = it; onOpen(Panel.Api)
            }
        }
    }
}

@Composable
private fun ModelDownloadControl(spec: ModelSpec, source: String) {
    val context = LocalContext.current
    val status = AppRuntime.status(spec)
    val progress = animateFloatAsState((status.bytes.toFloat() / spec.bytes).coerceIn(0f, 1f),
        tween(YomiMotion.progress, easing = YomiMotion.ease), label = "model_progress")
    Text(spec.title, color = yomiColors.ink, fontSize = 18.sp, fontFamily = FontFamily.Serif)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (spec == ModelCatalog.translation) "Q4_K_M" else "LSTM · fast", color = yomiColors.muted, fontSize = 13.sp)
        Text("${status.bytes / 1_000_000} / ${spec.bytes / 1_000_000} MB", color = yomiColors.muted, fontSize = 13.sp)
    }
    Spacer(Modifier.height(16.dp))
    LinearProgressIndicator(progress = { progress.value }, modifier = Modifier.fillMaxWidth().height(2.dp),
        color = yomiColors.accent, trackColor = yomiColors.line)
    Spacer(Modifier.height(16.dp))
    QuietContent(status.error, Modifier.fillMaxWidth()) { message ->
        if (message != null) Column { Text(message, color = yomiColors.accent, fontSize = 14.sp); Spacer(Modifier.height(16.dp)) }
    }
    val action = when (status.phase) {
        "下载中" -> "暂停 · ${status.bytes * 100 / spec.bytes}%"
        "校验中", "加载中", "已加载" -> status.phase
        "已下载" -> "加载模型"
        else -> if (status.bytes > 0) "继续下载" else "下载模型"
    }
    ActionButton(action, {
        when (status.phase) {
            "下载中" -> ModelDownloadService.pause(context)
            "校验中", "加载中", "已加载" -> Unit
            "已下载" -> AppRuntime.load(spec, source)
            else -> if (AppRuntime.models.values.none { it.busy }) ModelDownloadService.start(context, spec)
        }
    }, Modifier.fillMaxWidth(), primary = status.phase !in listOf("已加载", "加载中", "校验中"),
        enabled = status.phase !in listOf("已加载", "加载中", "校验中") &&
            (status.phase == "下载中" || AppRuntime.models.values.none { it.busy }), stateKey = status.phase)
}

@Composable
internal fun Options(options: List<String>, selected: String, compact: Boolean = false, onSelect: (String) -> Unit) {
    if (compact) {
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                ChoiceOption(option, option == selected, { onSelect(option) })
            }
        }
    } else {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { option ->
                ChoiceOption(option, option == selected, { onSelect(option) }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ChoiceOption(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val surface by animateColorAsState(if (selected) yomiColors.accentSoft else Color.Transparent,
        tween(YomiMotion.change, easing = YomiMotion.ease), label = "choice_surface")
    val ink by animateColorAsState(if (selected) yomiColors.accent else yomiColors.ink,
        tween(YomiMotion.change, easing = YomiMotion.ease), label = "choice_ink")
    Row(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .background(surface).quietFeedback(interaction)
        .selectable(selected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 16.sp, color = ink)
        AnimatedVisibility(selected,
            enter = fadeIn(tween(YomiMotion.change)) + expandHorizontally(tween(YomiMotion.change, easing = YomiMotion.ease)),
            exit = fadeOut(tween(YomiMotion.press)) + shrinkHorizontally(tween(YomiMotion.change, easing = YomiMotion.ease))) {
            Box(Modifier.padding(start = 10.dp)) { LineIcon(Glyph.Check, Modifier.size(16.dp), yomiColors.accent) }
        }
    }
}

@Composable
internal fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val labelColor by animateColorAsState(if (focused) yomiColors.accent else yomiColors.muted,
        tween(YomiMotion.change, easing = YomiMotion.ease), label = "field_label")
    val lineColor by animateColorAsState(if (focused) yomiColors.accent else yomiColors.line,
        tween(YomiMotion.change, easing = YomiMotion.ease), label = "field_line")
    Column {
        Text(label, fontSize = 13.sp, lineHeight = 20.sp, color = labelColor)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 10.dp)
                .semantics { contentDescription = label },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = yomiColors.ink),
            singleLine = true,
            interactionSource = interaction,
            cursorBrush = SolidColor(yomiColors.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            decorationBox = { field ->
                Box {
                    if (value.isEmpty()) Text(placeholder, fontSize = 16.sp, color = yomiColors.muted)
                    field()
                }
            },
        )
        HorizontalDivider(thickness = 0.5.dp, color = lineColor)
    }
}
