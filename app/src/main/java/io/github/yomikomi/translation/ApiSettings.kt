package io.github.yomikomi.translation

import java.net.URI

enum class ApiProvider(val key: String, val title: String) {
    DeepSeek("deepseek", "DeepSeek"), OpenAiCompatible("openai", "OpenAI 兼容"),
}

data class ApiSettings(
    val enabled: Boolean = false,
    val provider: ApiProvider = ApiProvider.DeepSeek,
    val endpoint: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val prompt: PromptSettings = PromptSettings(),
) {
    fun validationError(): String? {
        val uri = try { URI(endpoint) } catch (_: Exception) { return "请填写有效的 HTTPS API 地址" }
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0) return "请填写有效的 HTTPS API 地址"
        if (model.isBlank()) return "请填写模型名称"
        return null
    }

    fun completionUrl(): String {
        require(validationError() == null) { validationError()!! }
        val base = endpoint.trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    companion object {
        fun origin(endpoint: String): String {
            val uri = URI(endpoint)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) { "请填写有效的 HTTPS API 地址" }
            return URI("https", null, uri.host.lowercase(), if (uri.port == 443) -1 else uri.port, null, null, null).toString()
        }
    }
}
