package io.github.yomikomi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yomikomi.translation.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.Timeout
import okio.buffer
import okio.source
import java.net.SocketException
import java.util.zip.GZIPOutputStream
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ApiTranslationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val segments = listOf(TranslationSegment("b7", "Alice picked up Frost."), TranslationSegment("b12", "She said, 'Let's go.'"))
    private val previous = PreviousScreen(listOf(TranslationSegment("b99", "Captain Alice calls her sword Frost.")),
        listOf(TranslationSegment("b99", "爱丽丝队长称她的剑为霜华。")))
    private fun request(prompt: PromptSettings = PromptSettings()) = TranslationRequest("英语", "简体中文", segments, previous, prompt)
    private fun response(text: String, finish: String = "stop") = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("finish_reason", finish).put("message", JSONObject().put("content", text)))).toString()

    @Test fun longResponsesPreserveEveryCharacterAndIdAcrossReadBoundaries() {
        val long = "人名Нейромиту、否定条件、引号\"与括号{原样}😀，不要省略。".repeat(240) + "【最后一个字】"
        val conn = FakeConnection(response("[b7] → $long\n[b12] → 完整的第二个ID。"))
        val result = ApiTranslationEngine(ApiSettings(), { "test-key" }, { conn }).translate(request())
        assertEquals(long, result.first().text)
        assertEquals("完整的第二个ID。", result.last().text)
        assertEquals(segments.map { it.id }, result.map { it.id })
        assertEquals(4096, JSONObject(conn.sent.toString("UTF-8")).getInt("max_tokens"))
    }

    @Test fun tokenLimitAndProtocolFailuresHaveDistinctDiagnosticsAndNeverReturnPartialTranslations() {
        val body = JSONObject(response("[b7] → 看似完整\n[b12] → 实际被截断", "length"))
            .put("usage", JSONObject().put("prompt_tokens", 1800).put("completion_tokens", 4096)).toString()
        val failure = assertThrows(IllegalStateException::class.java) {
            ApiTranslationEngine(ApiSettings(), { "test-key" }, { FakeConnection(body) }).translate(request())
        }
        assertTrue(failure.message.orEmpty().contains("token 上限"))
        assertTrue(runtimeLogSnapshot().contains("finish=1 prompt_tokens=1800 output_tokens=4096 max_output_tokens=4096"))
        assertThrows(IllegalArgumentException::class.java) {
            ApiTranslationEngine(ApiSettings(), { "test-key" }, { FakeConnection(response("[b7] → 缺少第二个ID")) }).translate(request())
        }
        assertTrue(runtimeLogSnapshot().contains("API protocol_failed request=0 expected_ids=2 lines=1"))
    }

    @Test fun onePostContainsBothScreensAndEveryPresetAndPreservesIds() {
        PromptMode.entries.forEach { mode ->
            val custom = "背景 {{previous_screen}}\n任务：自然中文\n整屏 {{current_screen}}"
            val config = ApiSettings(enabled = true, prompt = PromptSettings(mode, custom))
            val conn = FakeConnection(response("[b7] → 爱丽丝拿起了霜华。\n[b12] → 她说：我们走吧。"))
            var opens = 0
            val result = ApiTranslationEngine(config, { "test-key" }, { opens++; conn }).translate(request(config.prompt))
            assertEquals(1, opens)
            assertEquals(segments.map { it.id }, result.map { it.id })
            assertEquals("POST", conn.requestMethod)
            assertFalse(apiHttpClient.followRedirects)
            assertFalse(apiHttpClient.retryOnConnectionFailure)
            assertTrue(conn.lastRequest.body!!.isOneShot())
            assertEquals("Bearer test-key", conn.getRequestProperty("Authorization"))
            val body = JSONObject(conn.sent.toString("UTF-8"))
            assertEquals("deepseek-flash", body.getString("model"))
            assertFalse(body.getBoolean("stream"))
            assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
            val messages = body.getJSONArray("messages")
            assertEquals(2, messages.length())
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            assertTrue(messages.getJSONObject(0).getString("content").contains("不猜成普通词"))
            assertEquals("user", messages.getJSONObject(1).getString("role"))
            val prompt = messages.getJSONObject(1).getString("content")
            assertEquals(TranslationPrompts.render(request(config.prompt)), prompt)
            assertTrue(prompt.contains("霜华"))
            assertFalse(prompt.contains("[b99]"))
            assertTrue(conn.disconnected)
        }
        val conn = FakeConnection(response("[b7] → 爱丽丝拿起了霜华。\n[b12] → 她说：我们走吧。"))
        ApiTranslationEngine(ApiSettings(provider = ApiProvider.OpenAiCompatible), { "test-key" }, { conn }).translate(request())
        assertFalse(JSONObject(conn.sent.toString("UTF-8")).has("thinking"))
    }

    @Test fun rejectsRedirectsHttpFailuresTruncatedResponsesAndWrongIdsWithoutLeakingBodies() {
        listOf(302, 401, 402, 404, 429, 503).forEach { status ->
            val conn = FakeConnection("secret-or-screen-text-must-not-leak", status)
            val failure = assertThrows(IllegalStateException::class.java) {
                ApiTranslationEngine(ApiSettings(), { "test-key" }, { conn }).translate(request())
            }
            assertFalse(failure.message.orEmpty().contains("must-not-leak"))
            assertTrue(conn.disconnected)
        }
        listOf(response("[b7] → 截断", "length"), "not JSON", response("[b12] → 二\n[b7] → 一"),
            response("[b7] → 一\n[b12] → 二\n[b99] → 上屏"), response("[b7] → 一\n[b12] → 二 [b99] → 上屏")).forEach { body ->
            assertThrows(Exception::class.java) { ApiTranslationEngine(ApiSettings(), { "test-key" }, { FakeConnection(body) }).translate(request()) }
        }
    }

    @Test fun cancellationDisconnectsAndCannotPublishAResponse() {
        lateinit var engine: ApiTranslationEngine
        val conn = object : FakeConnection("") {
            override fun getInputStream(): InputStream {
                engine.cancel()
                throw IOException("closed")
            }
        }
        engine = ApiTranslationEngine(ApiSettings(), { "test-key" }, { conn })
        assertEquals("已取消", assertThrows(IllegalStateException::class.java) { engine.translate(request()) }.message)
        assertTrue(conn.disconnected)
        var opened = false
        val canceled = ApiTranslationEngine(ApiSettings(), { "test-key" }, { opened = true; conn })
        canceled.cancel()
        assertThrows(IllegalStateException::class.java) { canceled.translate(request()) }
        assertFalse(opened)
    }

    @Test fun completeJsonDoesNotWaitForEofOrAResetAfterTheBody() {
        val text = "[b7] → 爱丽丝拿起{霜华}，说：\"走吧\"。\n[b12] → 她笑了。"
        val bytes = ("\n\n" + response(text)).toByteArray(Charsets.UTF_8)
        var reads = 0
        val conn = object : FakeConnection("") {
            override fun getInputStream() = object : InputStream() {
                var position = 0
                override fun read(): Int = error("Use bounded bulk reads")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reads++
                    if (position == bytes.size) throw SocketException("reset after complete body")
                    // Split escapes, Chinese UTF-8 bytes and braces over read boundaries.
                    val count = minOf(3, length, bytes.size - position)
                    bytes.copyInto(buffer, offset, position, position + count)
                    position += count
                    return count
                }
                override fun close() { throw SocketException("close after complete body") }
            }
        }
        val translated = ApiTranslationEngine(ApiSettings(), { "test-key" }, { conn }).translate(request())
        assertEquals(text.substringBefore('\n').substringAfter(" → "), translated.first().text)
        assertEquals((bytes.size + 2) / 3, reads)
        assertNull(conn.getRequestProperty("Connection"))
        assertTrue(conn.disconnected)
    }

    @Test fun resetAfterHttp200BeforeCompleteBodyFailsWithoutReplayingThePost() {
        val bytes = response("[b7] → 爱丽丝\n[b12] → 她笑了。").toByteArray(Charsets.UTF_8)
        var opens = 0
        val conn = object : FakeConnection("") {
            override fun getInputStream() = object : InputStream() {
                var sent = false
                override fun read(): Int = error("Use bounded bulk reads")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (sent) throw SocketException("private response must not leak")
                    sent = true
                    bytes.copyInto(buffer, offset, 0, bytes.size / 2)
                    return bytes.size / 2
                }
            }
        }
        val error = assertThrows(IllegalStateException::class.java) {
            ApiTranslationEngine(ApiSettings(), { "test-key" }, { opens++; conn }).translate(request())
        }
        assertEquals("API 响应读取中断，请重试", error.message)
        assertEquals(1, opens)
        assertTrue(conn.disconnected)
        val log = runtimeLogSnapshot()
        assertTrue(log.contains("API transport_failed stage=4 http_status=200"))
        assertFalse(log.contains("private response must not leak"))
    }

    @Test fun explicitIdentityAndGzipCompatibilityKeepUtf8AndRecordBodyPhases() {
        val encoded = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(response("[b7] → 爱丽丝拿起霜华。\n[b12] → 她说：走吧。").toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val conn = object : FakeConnection("") {
            override fun getHeaderField(name: String) = when (name) {
                "Content-Encoding" -> "gzip"
                "Transfer-Encoding" -> "chunked"
                else -> null
            }
            override fun getInputStream(): InputStream = object : ByteArrayInputStream(encoded) {
                override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(length, 3))
            }
        }
        val result = ApiTranslationEngine(ApiSettings(), { "test-key" }, { conn }).translate(request())
        assertTrue(result.first().text.contains("霜华"))
        assertEquals("identity", conn.getRequestProperty("Accept-Encoding"))
        val log = runtimeLogSnapshot()
        assertTrue(log.contains("gzip=1 chunked=1"))
        assertTrue(log.contains("API first_byte"))
        assertTrue(log.contains("API body_read"))
        assertFalse(log.contains("Bearer test-key"))
    }

    @Test fun gzipHeaderResetCannotBeMisreportedAsEmptySuccessfulTranslation() {
        val conn = object : FakeConnection("") {
            override fun getHeaderField(name: String) = if (name == "Content-Encoding") "gzip" else null
            override fun getInputStream() = object : InputStream() {
                override fun read(): Int = throw SocketException("reset in gzip header")
            }
        }
        assertThrows(IllegalStateException::class.java) {
            ApiTranslationEngine(ApiSettings(), { "test-key" }, { conn }).translate(request())
        }
        assertTrue(runtimeLogSnapshot().contains("API transport_failed stage=3 http_status=200"))
    }

    @Test fun keyIsEncryptedOriginBoundAndApiSettingsPersistIndependently() {
        val testContext = object : android.content.ContextWrapper(context) {
            override fun getNoBackupFilesDir() = File(context.cacheDir, "api-secret-test").apply { mkdirs() }
        }
        val secrets = ApiSecretStore(testContext)
        val stored = File(testContext.noBackupFilesDir, "api-key")
        val original = RuntimeSettings.read(context)
        try {
            secrets.save("https://api.deepseek.com", "test-secret-not-plaintext")
            assertFalse(stored.readText().contains("test-secret-not-plaintext"))
            assertEquals("test-secret-not-plaintext", secrets.read("https://api.deepseek.com/v1"))
            assertFalse(secrets.hasKey("https://other.example"))
            assertThrows(IllegalStateException::class.java) { secrets.read("https://other.example") }
            val config = ApiSettings(enabled = true, prompt = PromptSettings(PromptMode.Custom, "{{previous_screen}}\n{{current_screen}}"))
            val changed = original.copy(api = config)
            changed.save(context)
            assertEquals(changed, RuntimeSettings.read(context))
            assertEquals(original.prompt, RuntimeSettings.read(context).prompt)
        } finally { original.save(context); stored.delete() }
    }

    @Test fun concurrentStoresNeverReturnAnotherOriginsKeyAndUnreadableStorageDoesNotCrashPresenceCheck() {
        val testContext = object : android.content.ContextWrapper(context) {
            override fun getNoBackupFilesDir() = File(context.cacheDir, "api-secret-concurrency-test").apply { mkdirs() }
        }
        val writer = ApiSecretStore(testContext)
        val reader = ApiSecretStore(testContext)
        val stored = File(testContext.noBackupFilesDir, "api-key")
        val origins = listOf("https://left.example" to "left-fixture-key", "https://right.example" to "right-fixture-key")
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val writing = workers.submit { repeat(15) { origins.forEach { (origin, key) -> writer.save(origin, key) } } }
            val reading = workers.submit { repeat(30) { origins.forEach { (origin, key) ->
                val value = try { reader.read(origin) } catch (_: IllegalStateException) { null }
                if (value != null) assertEquals(key, value)
            } } }
            writing.get(15, TimeUnit.SECONDS); reading.get(15, TimeUnit.SECONDS)
            assertEquals("right-fixture-key", reader.read("https://right.example"))
            assertFalse(reader.hasKey("invalid URL"))
            assertTrue(stored.delete())
            assertTrue(stored.mkdir())
            assertFalse(reader.hasKey("https://right.example"))
        } finally { workers.shutdownNow(); stored.delete() }
    }

    /** Explicitly opt in; only the public fixture is sent. Never embed credentials in this test APK. */
    @Test fun liveDeepSeekTranslatesAllFiveModesWithPreviousContext() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveApi") == "true")
        val secrets = ApiSecretStore(context)
        val imported = File(context.noBackupFilesDir, "api-key-import")
        if (imported.exists()) {
            try { secrets.save("https://api.deepseek.com", imported.readText().trim()) }
            finally { imported.delete() }
        }
        AppRuntime.worker.submit {
            val custom = """
                上一屏背景：{{previous_screen}}
                将当前整屏翻译成自然中文。Alice 必须译为“阿璃”，Frost 必须译为“霜华”。
                必须完整保留本屏人物和动作。仅按 [ID] → 译文 输出本屏，保持 ID 和顺序。
                当前整屏：{{current_screen}}
            """.trimIndent()
            PromptMode.entries.forEach { mode ->
                val config = ApiSettings(enabled = true, prompt = PromptSettings(mode, custom))
                val start = android.os.SystemClock.elapsedRealtime()
                val result = ApiTranslationEngine(config, { secrets.read(config.endpoint) }).translate(request(config.prompt))
                assertEquals(mode.title, segments.map { it.id }, result.map { it.id })
                assertTrue("${mode.title}: $result", result.first().text.contains("霜华"))
                assertTrue("${mode.title}: $result", result.first().text.contains(if (mode == PromptMode.Custom) "阿璃" else "爱丽丝"))
                assertTrue("${mode.title}: $result", result.first().text.any { it in "拾捡拿" })
                android.util.Log.i("YomikomiApiTest", "${mode.title}: $result; ${android.os.SystemClock.elapsedRealtime() - start}ms")
            }
        }.get(360, TimeUnit.SECONDS)
    }

    private open class FakeConnection(body: String, private val code: Int = 200) : Call.Factory {
        val sent = ByteArrayOutputStream()
        private val response = body.toByteArray(Charsets.UTF_8)
        var disconnected = false
        lateinit var lastRequest: Request
        val requestMethod get() = lastRequest.method
        fun getRequestProperty(name: String) = lastRequest.header(name)
        open fun getInputStream(): InputStream = ByteArrayInputStream(response)
        open fun getHeaderField(name: String): String? = null
        override fun newCall(request: Request): Call {
            lastRequest = request
            return object : Call {
                private var canceled = false
                private var executed = false
                override fun request() = request
                override fun execute(): Response {
                    executed = true
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    sent.write(buffer.readByteArray())
                    return Response.Builder().request(request).protocol(Protocol.HTTP_2).code(code).message("fixture")
                        .apply { for (name in listOf("Content-Encoding", "Transfer-Encoding"))
                            getHeaderField(name)?.let { header(name, it) } }
                        .body(object : ResponseBody() {
                            override fun contentType() = "application/json".toMediaType()
                            override fun contentLength() = -1L
                            private val input by lazy { getInputStream().source().buffer() }
                            override fun source() = input
                            override fun close() { disconnected = true; input.close() }
                        }).build()
                }
                override fun enqueue(responseCallback: Callback) = error("Synchronous fixture")
                override fun cancel() { canceled = true; disconnected = true }
                override fun isCanceled() = canceled
                override fun isExecuted() = executed
                override fun timeout() = Timeout()
                override fun clone(): Call = newCall(request)
            }
        }
    }
}
