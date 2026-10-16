package io.github.yomikomi.translation

import org.junit.Assert.*
import org.junit.Test

class ApiSettingsTest {
    @Test fun deepSeekAndVersionedCompatibleBasesUseExactlyOneCompletionPath() {
        assertEquals("https://api.deepseek.com/chat/completions", ApiSettings().completionUrl())
        assertEquals("https://example.org/v1/chat/completions", ApiSettings(endpoint = "https://example.org/v1/").completionUrl())
        assertEquals("https://example.org/v1/chat/completions", ApiSettings(endpoint = "https://example.org/v1/chat/completions").completionUrl())
        assertEquals(ApiSettings.origin("https://api.deepseek.com"), ApiSettings.origin("https://API.DEEPSEEK.COM:443/v1"))
        assertNotEquals(ApiSettings.origin("https://api.deepseek.com"), ApiSettings.origin("https://api.deepseek.com:8443"))
    }

    @Test fun bearerCredentialsRequireHttpsWithoutUrlCredentialsQueryOrFragment() {
        listOf("http://example.org", "https://user:secret@example.org", "https://example.org/?key=x", "https://example.org/#x",
            "https://example.org:70000", "https://example.org:0", "invalid").forEach {
            val config = ApiSettings(endpoint = it)
            assertNotNull(it, config.validationError())
            assertThrows(IllegalArgumentException::class.java) { config.completionUrl() }
        }
        assertNotNull(ApiSettings(model = " ").validationError())
    }

    @Test fun sharedWholeScreenRenderingDoesNotIntroducePreviousIds() {
        val request = TranslationRequest("英语", "简体中文", listOf(TranslationSegment("b7", "Alice picked it up.")),
            PreviousScreen(listOf(TranslationSegment("b99", "Alice calls it Frost.")), listOf(TranslationSegment("b99", "爱丽丝称它为霜华。"))))
        val rendered = TranslationPrompts.render(request)
        assertTrue(rendered.contains("原文：Alice calls it Frost."))
        assertTrue(rendered.contains("译文：爱丽丝称它为霜华。"))
        assertTrue(rendered.contains("[b7] 原文：Alice picked it up.\n\n请将以上当前屏的英语文本翻译为简体中文"))
        assertFalse(rendered.contains("[b99]"))
    }
}
