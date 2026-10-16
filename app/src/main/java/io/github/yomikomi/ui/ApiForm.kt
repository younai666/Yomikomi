package io.github.yomikomi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yomikomi.translation.ApiProvider
import io.github.yomikomi.translation.ApiSecretStore
import io.github.yomikomi.translation.ApiSettings
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog

/** Kept only while this dialog is open, including its prompt subpanels. No saved-state plaintext key. */
@Stable
internal class ApiDraft(config: ApiSettings) {
    var enabled by mutableStateOf(config.enabled)
    var provider by mutableStateOf(config.provider)
    var endpoint by mutableStateOf(config.endpoint)
    var model by mutableStateOf(config.model)
    var secret by mutableStateOf("")
    var prompt by mutableStateOf(config.prompt)
}

@Composable
internal fun ApiForm(state: UiState, draft: ApiDraft, onDismiss: () -> Unit, open: (Panel) -> Unit) {
    val context = LocalContext.current
    val secrets = remember { ApiSecretStore(context) }
    var choosingProvider by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val savedKey = remember(draft.endpoint) { secrets.hasKey(draft.endpoint) }
    QuietSwitch("使用第三方 API", draft.enabled) { draft.enabled = it }
    Hairline()
    PreferenceRow("服务", value = draft.provider.title, onClick = { choosingProvider = !choosingProvider })
    if (choosingProvider) {
        Options(ApiProvider.entries.map { it.title }, draft.provider.title) { title ->
            draft.provider = ApiProvider.entries.single { it.title == title }
            draft.endpoint = if (draft.provider == ApiProvider.DeepSeek) "https://api.deepseek.com" else ""
            draft.model = if (draft.provider == ApiProvider.DeepSeek) "deepseek-flash" else ""
            draft.secret = ""
            error = null
            choosingProvider = false
        }
    }
    Spacer(Modifier.height(8.dp))
    FormField("API 地址", draft.endpoint, { draft.endpoint = it; error = null }, "https://api.example.com/v1", KeyboardType.Uri)
    Spacer(Modifier.height(20.dp))
    FormField("API 密钥", draft.secret, { draft.secret = it; error = null },
        if (savedKey) "已保存" else "sk-…", KeyboardType.Password, secret = true)
    Spacer(Modifier.height(20.dp))
    FormField("模型名称", draft.model, { draft.model = it; error = null })
    Spacer(Modifier.height(8.dp))
    PreferenceRow("翻译提示词", value = draft.prompt.mode.title, onClick = { open(Panel.ApiPrompts) })
    error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = yomiColors.accent, fontSize = 13.sp) }
    Spacer(Modifier.height(20.dp))
    ActionButton("完成", {
        val config = ApiSettings(draft.enabled, draft.provider, draft.endpoint.trim().trimEnd('/'), draft.model.trim(), draft.prompt)
        error = when {
            config.enabled || draft.secret.isNotBlank() -> config.validationError()
                ?: if (draft.secret.isBlank() && !secrets.hasKey(config.endpoint)) "请填写 API 密钥" else null
            else -> null
        }
        if (error == null) {
            try {
                if (draft.secret.isNotBlank()) secrets.save(config.endpoint, draft.secret.trim())
                draft.secret = ""
                state.api = config
                onDismiss()
            } catch (failure: Exception) {
                RunLog.error(LogModule.API, "settings_failed", failure)
                error = failure.message ?: "无法保存 API 设置"
            }
        }
    }, Modifier.fillMaxWidth())
}
