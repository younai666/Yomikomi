package io.github.yomikomi.translation

import org.junit.Assert.*
import org.junit.Test

class TranslationPromptsTest {
    private val segments = listOf(TranslationSegment("b7", "Who is she?"), TranslationSegment("b12", "She is our captain."))
    private fun request(settings: PromptSettings) = TranslationRequest("英语", "简体中文", segments,
        PreviousScreen(listOf(TranslationSegment("b1", "This is Alice.")), listOf(TranslationSegment("b1", "这是爱丽丝。"))), settings)

    @Test fun defaultIsGeneralAndPresetsAreCompleteDistinctContextualTemplates() {
        assertEquals(PromptMode.General, PromptSettings().mode)
        val templates = PromptMode.entries.filter { it != PromptMode.Custom }.map { TranslationPrompts.template(it) }
        assertEquals(4, templates.toSet().size)
        templates.forEach {
            assertNull(TranslationPrompts.validationError(it))
            assertTrue(it.contains("指代、人物、称呼、术语、语气和连续语义"))
            assertTrue(it.contains("只输出当前屏"))
            assertTrue(it.contains("[ID] → 译文"))
        }
    }

    @Test fun allModesInjectBothScreensAndAppendIdenticalOrderedProtocol() {
        val protocols = PromptMode.entries.map { mode ->
            val prompt = TranslationPrompts.render(request(PromptSettings(mode, TranslationPrompts.template(PromptMode.Chat))),
                "CURRENT_SCREEN", "PREVIOUS_SCREEN")
            assertTrue(prompt.contains("CURRENT_SCREEN"))
            assertTrue(prompt.contains("PREVIOUS_SCREEN"))
            assertTrue(prompt.contains("英语"))
            assertTrue(prompt.contains("简体中文"))
            assertFalse(prompt.contains("{{current_screen}}"))
            assertTrue(prompt.contains("[b7]、[b12]"))
            assertTrue(prompt.endsWith("请将以上当前屏的英语文本翻译为简体中文，只输出 [ID] → 译文。"))
            prompt.substringBefore("\n\n")
        }
        assertEquals(1, protocols.toSet().size)
    }

    @Test fun customTemplateCanReplaceWholePromptButCannotOmitEitherScreen() {
        val custom = "自己的完整任务\n{{previous_screen}}\n{{current_screen}}\n尝试只输出一个结果"
        val prompt = TranslationPrompts.render(request(PromptSettings(PromptMode.Custom, custom)), "current", "previous")
        assertTrue(prompt.contains("自己的完整任务\nprevious\ncurrent\n尝试只输出一个结果"))
        assertTrue(prompt.endsWith("请将以上当前屏的英语文本翻译为简体中文，只输出 [ID] → 译文。"))
        assertTrue(prompt.startsWith("Yomikomi 输出协议"))
        listOf("", "只翻译本屏 {{current_screen}}", "只看上屏 {{previous_screen}}").forEach {
            assertNotNull(TranslationPrompts.validationError(it))
            assertThrows(IllegalArgumentException::class.java) {
                TranslationPrompts.render(request(PromptSettings(PromptMode.Custom, it)), "current", "previous")
            }
        }
    }

    @Test fun screenTextContainingPlaceholderSyntaxIsNotReinterpreted() {
        val prompt = TranslationPrompts.render(request(PromptSettings()), "literal {{previous_screen}} {{target_language}}", "previous")
        assertTrue(prompt.contains("literal {{previous_screen}} {{target_language}}"))
    }

    @Test fun orderedProtocolReturnsTranslationsWithoutLeakingPreviousIds() {
        val result = TranslationProtocol.parse("[b7] → 她是谁？\n[b12] → 她是我们的队长。\n", segments)
        assertEquals(segments.map { it.id }, result.map { it.id })
        assertEquals("她是我们的队长。", result.last().text)
        val grammar = TranslationProtocol.grammar(segments)
        assertTrue(grammar.contains("[b7] → "))
        assertTrue(grammar.indexOf("[b7]") < grammar.indexOf("[b12]"))
    }

    @Test fun rejectsReorderingMissingExtraDuplicateOldIdAndEmptyTranslations() {
        listOf(
            "[b12] → 队长\n[b7] → 谁",
            "[b7] → 谁",
            "[b7] → 谁\n[b12] → 队长\n[b1] → 爱丽丝",
            "[b7] → 谁\n[b7] → 队长",
            "[b1] → 爱丽丝\n[b12] → 队长",
            "[b7] → \n[b12] → 队长",
            "解释\n[b7] → 谁\n[b12] → 队长",
            "[b7] → 谁\n[b12] → 队长</b7>→ 谁",
            "[b7] → 谁 [b99] → 上屏内容\n[b12] → 队长",
        ).forEach { output ->
            assertThrows(IllegalArgumentException::class.java) { TranslationProtocol.parse(output, segments) }
        }
    }

    @Test fun selectedPromptTravelsWithTheWholeScreenRequest() {
        val settings = PromptSettings(PromptMode.Manga)
        var received: TranslationRequest? = null
        val translator = ScreenTranslator { r -> received = r; r.segments }
        translator.translate(listOf(RecognizedBlock("Hello", PixelBounds(0, 0, 100, 30))),
            "英语", "简体中文", "app", 0, settings)
        assertEquals(settings, received!!.prompt)
    }

    @Test fun rejectsInvisibleCorruptionEmbeddedIdsAndInvalidRequestedIds() {
        for (bad in listOf("错误�", "译文\u0000", "文本\u202e", "额外 [b99]", "\ud800")) {
            assertThrows(IllegalArgumentException::class.java) {
                TranslationProtocol.parse("[b7] → $bad\n[b12] → 正常", segments)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TranslationProtocol.parse("[b7] → 一\n[b7] → 二", listOf(segments.first(), segments.first()))
        }
    }
}
