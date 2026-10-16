package io.github.yomikomi

import android.content.Context
import io.github.yomikomi.translation.PromptMode
import io.github.yomikomi.translation.PromptSettings
import io.github.yomikomi.translation.ApiSettings
import io.github.yomikomi.translation.ApiProvider
import io.github.yomikomi.translation.ContextConfig
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog

data class RuntimeSettings(
    val source: String, val target: String, val opacity: Float, val textSize: Int, val autoCollapse: Boolean,
    val prompt: PromptSettings = PromptSettings(),
    val api: ApiSettings = ApiSettings(),
) {
    val contextConfig get() = ContextConfig(source, target, if (api.enabled) api.prompt else prompt,
        if (api.enabled) "api:${api.provider.key}:${api.endpoint}:${api.model}" else "local")
    companion object {
        fun read(context: Context): RuntimeSettings {
            val prefs = context.getSharedPreferences("settings", 0)
            return RuntimeSettings(prefs.getString("source", "日语")!!, prefs.getString("target", "简体中文")!!,
                prefs.getFloat("opacity", 1f), prefs.getInt("textSize", 16), prefs.getBoolean("autoCollapse", true),
                PromptSettings(PromptMode.entries.firstOrNull { it.key == prefs.getString("promptMode", null) } ?: PromptMode.General,
                    prefs.getString("customPrompt", "").orEmpty()),
                ApiSettings(prefs.getBoolean("apiEnabled", false),
                    ApiProvider.entries.firstOrNull { it.key == prefs.getString("apiProvider", null) } ?: ApiProvider.DeepSeek,
                    prefs.getString("apiEndpoint", "https://api.deepseek.com")!!,
                    prefs.getString("apiModel", "deepseek-flash")!!,
                    PromptSettings(PromptMode.entries.firstOrNull { it.key == prefs.getString("apiPromptMode", null) } ?: PromptMode.General,
                        prefs.getString("apiCustomPrompt", "").orEmpty())))
        }
    }
    fun save(context: Context) {
        RunLog.info(LogModule.ACTION, "settings_saved", "api_enabled" to if (api.enabled) 1 else 0)
        context.getSharedPreferences("settings", 0).edit().putString("source", source).putString("target", target)
            .putFloat("opacity", opacity).putInt("textSize", textSize).putBoolean("autoCollapse", autoCollapse)
            .putString("promptMode", prompt.mode.key).putString("customPrompt", prompt.customTemplate)
            .putBoolean("apiEnabled", api.enabled).putString("apiProvider", api.provider.key)
            .putString("apiEndpoint", api.endpoint).putString("apiModel", api.model)
            .putString("apiPromptMode", api.prompt.mode.key).putString("apiCustomPrompt", api.prompt.customTemplate).apply()
    }
}
