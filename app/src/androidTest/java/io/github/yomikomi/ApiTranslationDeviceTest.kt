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
import java.net.HttpURLConnection
import java.net.URL
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
            assertFalse(conn.instanceFollowRedirects)
            assertEquals("Bearer test-key", conn.getRequestProperty("Authorization"))
            val body = JSONObject(conn.sent.toString("UTF-8"))
            assertEquals("deepseek-flash", body.getString("model"))
            assertFalse(body.getBoolean("stream"))
            assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
            val messages = body.getJSONArray("messages")
            assertEquals(1, messages.length())
            val prompt = messages.getJSONObject(0).getString("content")
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

    private open class FakeConnection(body: String, private val code: Int = 200) : HttpURLConnection(URL("https://api.deepseek.com/chat/completions")) {
        val sent = ByteArrayOutputStream()
        private val response = body.toByteArray(Charsets.UTF_8)
        var disconnected = false
        override fun getOutputStream() = sent
        override fun getInputStream(): InputStream = ByteArrayInputStream(response)
        override fun getResponseCode() = code
        override fun connect() { connected = true }
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
    }
}
