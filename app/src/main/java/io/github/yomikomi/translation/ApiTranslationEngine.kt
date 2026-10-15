package io.github.yomikomi.translation

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class ApiTranslationEngine(
    private val config: ApiSettings,
    private val secret: () -> String,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : TranslationEngine {
    private val canceled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null

    override fun translate(request: TranslationRequest): List<TranslationSegment> {
        check(!canceled.get()) { "已取消" }
        val url = URL(config.completionUrl())
        val key = secret()
        require(key.isNotBlank() && '\r' !in key && '\n' !in key) { "API 密钥格式无效" }
        val body = JSONObject().put("model", config.model).put("stream", false).put("max_tokens", 4096)
            .put("temperature", 0.3)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", TranslationPrompts.render(request))))
        if (config.provider == ApiProvider.DeepSeek) body.put("thinking", JSONObject().put("type", "disabled"))
        val conn = open(url)
        connection = conn
        try {
            check(!canceled.get()) { "已取消" }
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false // Never forward the bearer key to another destination.
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            check(conn.responseCode == 200) { when (conn.responseCode) {
                401, 403 -> "API 鉴权失败，请检查密钥"
                402 -> "API 余额不足"
                404 -> "API 地址或模型不存在"
                429 -> "API 请求频率受限"
                in 300..399 -> "API 返回重定向，请填写最终 HTTPS 地址"
                in 500..599 -> "API 服务暂时不可用"
                else -> "API 请求失败（HTTP ${conn.responseCode}）"
            } }
            val response = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    check(!canceled.get()) { "已取消" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(out.size() + count <= 1_048_576) { "API 响应过大" }
                    out.write(buffer, 0, count)
                }
                JSONObject(out.toString("UTF-8"))
            }
            check(!canceled.get()) { "已取消" }
            val choices = response.getJSONArray("choices")
            check(choices.length() == 1) { "API 返回了无效响应" }
            val choice = choices.getJSONObject(0)
            check(choice.getString("finish_reason") == "stop") { "API 译文未完整生成" }
            return TranslationProtocol.parse(choice.getJSONObject("message").getString("content").trim(), request.segments)
        } catch (_: SocketTimeoutException) { throw IllegalStateException(if (canceled.get()) "已取消" else "API 请求超时") }
        catch (_: IOException) { throw IllegalStateException(if (canceled.get()) "已取消" else "API 网络连接失败") }
        catch (_: JSONException) { throw IllegalStateException("API 返回了无效响应") }
        finally { conn.disconnect(); connection = null }
    }

    override fun cancel() { canceled.set(true); connection?.disconnect() }
}
