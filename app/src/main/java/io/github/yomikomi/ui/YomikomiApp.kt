package io.github.yomikomi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.yomikomi.AppRuntime
import io.github.yomikomi.RuntimeSettings
import io.github.yomikomi.models.ModelCatalog
import io.github.yomikomi.translation.PromptSettings
import io.github.yomikomi.translation.ApiSettings
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Stable
internal class UiState(config: RuntimeSettings) {
    var source by mutableStateOf(config.source)
    var target by mutableStateOf(config.target)
    val ocrModel get() = if (AppRuntime.status(ModelCatalog.ocr).phase == "未下载") "未下载" else "Tesseract"
    val translationModel get() = if (api.enabled) {
        if (api.model == "deepseek-flash") "DeepSeek" else api.model
    } else if (AppRuntime.status(ModelCatalog.translation).phase == "未下载") "未下载" else "Hy-MT2 · 1.8B"
    val tileAdded get() = AppRuntime.tileAdded
    val serviceEnabled get() = AppRuntime.serviceConnected
    var api by mutableStateOf(config.api)
    var opacity by mutableFloatStateOf(config.opacity)
    var textSize by mutableIntStateOf(config.textSize)
    var autoCollapse by mutableStateOf(config.autoCollapse)
    var promptMode by mutableStateOf(config.prompt.mode)
    var customPrompt by mutableStateOf(config.prompt.customTemplate)
}

internal enum class Panel { Source, Target, Ocr, Translation, Tile, Service, Api, Models, Prompts, CustomPrompt, ApiPrompts, ApiCustomPrompt }

@Composable
fun YomikomiApp() {
    val context = LocalContext.current
    val state = remember { UiState(RuntimeSettings.read(context)) }
    LaunchedEffect(state.source, state.target, state.opacity, state.textSize, state.autoCollapse, state.promptMode, state.customPrompt, state.api) {
        RuntimeSettings(state.source, state.target, state.opacity, state.textSize, state.autoCollapse,
            PromptSettings(state.promptMode, state.customPrompt), state.api).save(context)
    }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var panel by rememberSaveable { mutableStateOf<Panel?>(null) }
    BackHandler(enabled = page != 0 && panel == null) { page = 0 }

    Box(Modifier.fillMaxSize().background(yomiColors.background).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Crossfade(page, animationSpec = tween(180), label = "page", modifier = Modifier.widthIn(max = 600.dp)) { selected ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 32.dp),
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selected != 0) {
                        QuietIconButton(Glyph.Back, "返回主页", { page = 0 }, Modifier.offset(x = (-12).dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        if (selected == 0) "Yomikomi" else "设置",
                        modifier = Modifier.weight(1f),
                        color = yomiColors.ink,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Normal,
                        fontSize = if (selected == 0) 36.sp else 30.sp,
                        letterSpacing = if (selected == 0) (-1.4).sp else 0.sp,
                        lineHeight = 46.sp,
                    )
                    if (selected == 0) QuietIconButton(Glyph.Settings, "设置", { page = 1 })
                }
                Spacer(Modifier.height(if (selected == 0) 36.dp else 24.dp))
                if (selected == 0) HomeScreen(state) { panel = it }
                else SettingsScreen(state) { panel = it }
            }
        }
    }

    panel?.let { selected ->
        SettingsPanel(selected, state, onDismiss = { panel = null }, onOpen = { panel = it })
    }
}
