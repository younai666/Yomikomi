package io.github.yomikomi.translation

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog

class ApiTranslationEngine(
    private val config: ApiSettings,
    private val secret: () -> String,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : TranslationEngine {
    private val canceled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null

    override fun translate(request: TranslationRequest): List<TranslationSegment> =
        RunLog.measure(LogModule.API, "request", "blocks" to request.segments.size) { requestScreen(request) }

    private fun requestScreen(request: TranslationRequest): List<TranslationSegment> {
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
        val requestStarted = System.nanoTime()
        var status = 0
        var receivedBytes = 0
        var stage = 0 // 0 write, 1 headers, 2 open body, 3 gzip header, 4 read body, 5 JSON, 6 IDs.
        var bodyStarted = 0L
        var firstByteAt = 0L
        var wireBytes = 0L
        var succeeded = false
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
            // Avoid Android's transparent gzip reader blocking on a missing chunk/gzip header.
            conn.setRequestProperty("Accept-Encoding", "identity")
            // A translation POST must not reuse a stale keep-alive socket or be replayed implicitly.
            conn.setRequestProperty("Connection", "close")
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            RunLog.text(LogModule.API, "api_input", request.traceId, body.getJSONArray("messages").getJSONObject(0).getString("content"), key)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            stage = 1
            status = conn.responseCode
            val gzip = conn.getHeaderField("Content-Encoding")?.equals("gzip", ignoreCase = true) == true
            RunLog.info(LogModule.API, "response", "request" to request.traceId, "http_status" to status,
                "headers_ms" to RunLog.elapsed(requestStarted), "gzip" to if (gzip) 1 else 0,
                "chunked" to if (conn.getHeaderField("Transfer-Encoding")?.contains("chunked", true) == true) 1 else 0)
            check(status == 200) { when (status) {
                401, 403 -> "API 鉴权失败，请检查密钥"
                402 -> "API 余额不足"
                404 -> "API 地址或模型不存在"
                429 -> "API 请求频率受限"
                in 300..399 -> "API 返回重定向，请填写最终 HTTPS 地址"
                in 500..599 -> "API 服务暂时不可用"
                else -> "API 请求失败（HTTP ${conn.responseCode}）"
            } }
            stage = 2
            bodyStarted = System.nanoTime()
            val wire = conn.inputStream
            val counted = object : InputStream() {
                private fun count(size: Int) {
                    if (size <= 0) return
                    wireBytes += size
                    if (firstByteAt == 0L) {
                        firstByteAt = System.nanoTime()
                        RunLog.info(LogModule.API, "first_byte", "request" to request.traceId,
                            "elapsed_ms" to RunLog.elapsed(requestStarted))
                    }
                }
                override fun read(): Int = wire.read().also { if (it >= 0) count(1) }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = wire.read(buffer, offset, length).also(::count)
                override fun close() = wire.close()
            }
            val responseText = try {
                stage = 3
                val input = if (gzip) GZIPInputStream(counted) else counted
                stage = 4
                readResponse(input, request.traceId, key) { receivedBytes = it }
            }
            finally {
                try { counted.close() } catch (error: IOException) {
                    RunLog.warn(LogModule.API, "body_close_failed", error, "bytes" to receivedBytes)
                }
            }
            RunLog.info(LogModule.API, "body_read", "request" to request.traceId, "bytes" to receivedBytes,
                "wire_bytes" to wireBytes, "elapsed_ms" to RunLog.elapsed(bodyStarted), "total_ms" to RunLog.elapsed(requestStarted))
            RunLog.text(LogModule.API, "api_response", request.traceId, responseText, key)
            stage = 5
            val response = JSONObject(responseText)
            check(!canceled.get()) { "已取消" }
            val choices = response.getJSONArray("choices")
            check(choices.length() == 1) { "API 返回了无效响应" }
            val choice = choices.getJSONObject(0)
            check(choice.getString("finish_reason") == "stop") { "API 译文未完整生成" }
            stage = 6
            val translated = TranslationProtocol.parse(choice.getJSONObject("message").getString("content").trim(), request.segments)
            RunLog.text(LogModule.API, "id_map", request.traceId, JSONArray().apply {
                translated.forEach { put(JSONObject().put("id", it.id).put("text", it.text)) }
            }.toString(), key)
            succeeded = true
            return translated
        } catch (error: IOException) {
            RunLog.warn(LogModule.API, "transport_failed", error, "stage" to stage, "http_status" to status,
                "request" to request.traceId, "bytes" to receivedBytes, "wire_bytes" to wireBytes,
                "first_byte_ms" to if (firstByteAt == 0L) -1L else (firstByteAt - requestStarted) / 1_000_000,
                "cancelled" to if (canceled.get()) 1 else 0,
                "elapsed_ms" to if (bodyStarted == 0L) 0L else RunLog.elapsed(bodyStarted))
            throw IllegalStateException(when {
                canceled.get() -> "已取消"
                error is SocketTimeoutException -> "API 请求超时"
                stage in 2..4 -> "API 响应读取中断，请重试"
                else -> "API 网络连接失败"
            }, error)
        }
        catch (error: JSONException) { throw IllegalStateException("API 返回了无效响应", error) }
        finally {
            RunLog.info(LogModule.API, "finished", "request" to request.traceId, "success" to if (succeeded) 1 else 0,
                "stage" to stage, "http_status" to status, "wire_bytes" to wireBytes, "body_bytes" to receivedBytes,
                "first_byte_ms" to if (firstByteAt == 0L) -1L else (firstByteAt - requestStarted) / 1_000_000,
                "elapsed_ms" to RunLog.elapsed(requestStarted))
            conn.disconnect(); connection = null
        }
    }

    /** Stop at the complete JSON envelope, not socket EOF or the next keep-alive chunk.
     * A reset before completion still fails, without replaying a potentially billed POST. */
    private fun readResponse(input: InputStream, traceId: Long, key: String, progress: (Int) -> Unit): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var depth = 0
        var quoted = false
        var escaped = false
        var started = false
        while (true) {
            check(!canceled.get()) { "已取消" }
            val count = try { input.read(buffer) } catch (error: IOException) {
                RunLog.text(LogModule.API, "api_partial", traceId, out.toString("UTF-8"), key)
                throw error
            }
            if (count < 0) {
                RunLog.text(LogModule.API, "api_partial", traceId, out.toString("UTF-8"), key)
                throw IOException("Incomplete JSON envelope")
            }
            check(out.size() + count <= 1_048_576) { "API 响应过大" }
            for (index in 0 until count) {
                val char = buffer[index].toInt().toChar()
                if (!started && !char.isWhitespace()) {
                    if (char != '{') RunLog.text(LogModule.API, "api_partial", traceId,
                        out.toString("UTF-8") + buffer.copyOf(count).toString(Charsets.UTF_8), key)
                    check(char == '{') { "API 返回了无效响应" }
                    started = true
                }
                if (quoted) {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (started && depth == 0) {
                            out.write(buffer, 0, index + 1)
                            progress(out.size())
                            check(!canceled.get()) { "已取消" }
                            return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString() }
                            catch (error: java.nio.charset.CharacterCodingException) {
                                RunLog.text(LogModule.API, "api_invalid_utf8", traceId, out.toString("UTF-8"), key)
                                throw error
                            }
                        }
                    }
                }
            }
            out.write(buffer, 0, count)
            progress(out.size())
        }
    }

    override fun cancel() { RunLog.info(LogModule.API, "cancel_requested"); canceled.set(true); connection?.disconnect() }
}
