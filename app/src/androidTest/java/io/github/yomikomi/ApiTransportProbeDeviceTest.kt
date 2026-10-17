package io.github.yomikomi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yomikomi.translation.*
import okhttp3.ConnectionPool
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Opt-in real requests. No OCR, translation engine, ID parser, Overlay or automatic retries. */
@RunWith(AndroidJUnit4::class)
class ApiTransportProbeDeviceTest {
    @Test fun compareIndependentPostOverHttp2AndHttp1WithAndWithoutReuse() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("transportProbe") == "true")
        val context = instrumentation.targetContext
        val config = RuntimeSettings.read(context).api
        val key = ApiSecretStore(context).read(config.endpoint)
        val messages = if (args.getString("transportFixture") == "true") {
            JSONArray(File(context.cacheDir, "api-transport-messages.json").readText())
        } else JSONArray().put(JSONObject().put("role", "user").put("content", "Reply only with OK."))
        val body = JSONObject().put("model", config.model).put("stream", false).put("max_tokens", 4096)
            .put("temperature", 0.3).put("messages", messages)
        if (config.provider == ApiProvider.DeepSeek) body.put("thinking", JSONObject().put("type", "disabled"))
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        val request = Request.Builder().url(config.completionUrl()).tag(ApiTraceId::class.java, ApiTraceId(0, context.applicationContext))
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json").header("Accept-Encoding", "identity")
            .post(object : RequestBody() {
                override fun contentType() = "application/json; charset=utf-8".toMediaType()
                override fun contentLength() = payload.size.toLong()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { sink.write(payload) }
            }).build()
        val failures = mutableListOf<String>()
        for ((label, protocols, count) in listOf(
            Triple("h2_pool", listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), 2),
            Triple("h1_pool", listOf(Protocol.HTTP_1_1), 2),
            Triple("h1_fresh", listOf(Protocol.HTTP_1_1), 1),
        )) {
            // Isolated from production's pool. Only this test owns and evicts these connections.
            val client = apiHttpClient.newBuilder().connectionPool(ConnectionPool()).protocols(protocols).build()
            try { repeat(count) { index ->
                val call = client.newCall(request)
                val started = System.nanoTime()
                fun elapsed() = (System.nanoTime() - started) / 1_000_000
                var bytes = 0
                var headersMs = -1L
                var firstMs = -1L
                try {
                    call.execute().use { response ->
                        headersMs = elapsed()
                        check(response.code == 200) { "Non-200 response: ${response.code}" }
                        check(response.header("Content-Encoding")?.equals("gzip", true) != true) { "Unexpected gzip with identity" }
                        val input = checkNotNull(response.body).byteStream()
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read > 0 && firstMs < 0) firstMs = elapsed()
                            bytes += read
                            check(bytes <= 1_048_576)
                            out.write(buffer, 0, read)
                        }
                        check(JSONObject(out.toString("UTF-8")).getJSONArray("choices").getJSONObject(0).getString("finish_reason") == "stop")
                        println("PROBE $label index=$index http2=${response.protocol == Protocol.HTTP_2} headers_ms=$headersMs first_byte_ms=$firstMs body_ms=${elapsed() - headersMs} total_ms=${elapsed()} bytes=$bytes")
                    }
                } catch (error: IOException) {
                    failures += "$label/$index kind=${apiFailureKind(error)}"
                    println("PROBE_FAILED $label index=$index kind=${apiFailureKind(error)} headers_ms=$headersMs first_byte_ms=$firstMs total_ms=${elapsed()} bytes=$bytes cancelled=${call.isCanceled()}")
                }
            } } finally { client.connectionPool.evictAll() }
        }
        assertTrue("Independent requests failed: $failures", failures.isEmpty())
    }
}
