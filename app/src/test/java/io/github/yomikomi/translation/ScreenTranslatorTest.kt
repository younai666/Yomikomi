package io.github.yomikomi.translation

import org.junit.Assert.*
import org.junit.Test

class ScreenTranslatorTest {
    private fun block(text: String, x: Int, y: Int, width: Int = 80) =
        RecognizedBlock(text, PixelBounds(x, y, x + width, y + 20))

    @Test fun headingAndColumnsFollowReadingOrder() {
        val heading = block("Heading", 10, 10, 400)
        val left1 = block("Left 1", 10, 90)
        val left2 = block("Left 2", 10, 120)
        val right1 = block("Right 1", 230, 90)
        val right2 = block("Right 2", 230, 120)
        assertEquals(listOf(heading, left1, left2, right1, right2),
            readingOrder(listOf(right2, left2, right1, heading, left1)))
    }

    @Test fun oneWholePageCallAndIdBasedMapping() {
        var calls = 0
        val translator = ScreenTranslator { request ->
            calls++
            assertEquals(listOf("First", "Second"), request.segments.map { it.text })
            request.segments.reversed().map { TranslationSegment(it.id, "译:${it.text}") }
        }
        val page = translator.translate(listOf(block("Second", 10, 80), block("First", 10, 10)), "英语", "简体中文", "app", 100)
        assertEquals(1, calls)
        assertEquals(listOf("译:First", "译:Second"), page.blocks.map { page.translations.getValue(it.id) })
    }

    @Test fun duplicateTextGetsDistinctStableIdsDespiteJitterAndOcrShuffle() {
        val translator = ScreenTranslator { r -> r.segments }
        val page = translator.translate(listOf(block("Same", 10, 10), block("Same", 10, 80)), "英语", "中文", "app", 100)
        translator.accept(page, "app", 100)
        val next = translator.translate(listOf(block("Same", 12, 82), block("Same", 11, 11)), "英语", "中文", "app", 110)
        assertEquals(2, next.blocks.map { it.id }.toSet().size)
        assertEquals(page.blocks.map { it.id }, next.blocks.map { it.id })
    }

    @Test fun contextIsBoundedExpiresAndDoesNotCrossAppsOrLanguages() {
        val requests = mutableListOf<TranslationRequest>()
        val translator = ScreenTranslator { r -> requests += r; r.segments }
        val first = translator.translate((0..5).map { block("$it".repeat(500), 10, it * 50) }, "英语", "中文", "app", 0)
        translator.accept(first, "app", 0)
        val next = listOf(block("0".repeat(500), 10, 10), block("1".repeat(500), 10, 60))
        translator.translate(next, "英语", "中文", "app", 20)
        val previous = requests.last().previousScreen!!
        assertTrue((previous.segments + previous.translations).sumOf { it.text.length } <= ScreenTranslator.CONTEXT_CHARS)
        translator.translate(next, "英语", "中文", "other", 21)
        assertNull(requests.last().previousScreen)
        translator.translate(next, "俄语", "中文", "app", 22)
        assertNull(requests.last().previousScreen)
        translator.translate(next, "英语", "中文", "app", ScreenTranslator.CONTEXT_TTL_MS + 1)
        assertNull(requests.last().previousScreen)
    }

    @Test fun unacceptedScreensNeverBecomeContext() {
        var request: TranslationRequest? = null
        val translator = ScreenTranslator { r -> request = r; r.segments }
        translator.translate(listOf(block("discarded", 0, 0)), "英语", "中文", "app", 0)
        translator.translate(listOf(block("next", 0, 0)), "英语", "中文", "app", 1)
        assertNull(request!!.previousScreen)
    }

    @Test fun rejectsMissingDuplicateUnknownAndEmptyTranslations() {
        val input = listOf(block("a", 0, 0), block("b", 0, 50))
        val invalid: List<(TranslationRequest) -> List<TranslationSegment>> = listOf(
            { emptyList() },
            { listOf(it.segments.first(), it.segments.first()) },
            { listOf(it.segments.first(), TranslationSegment("unknown", "x")) },
            { it.segments.map { s -> s.copy(text = " ") } },
        )
        invalid.forEach { response ->
            assertThrows(IllegalArgumentException::class.java) {
                ScreenTranslator(response).translate(input, "英语", "中文", "app", 0)
            }
        }
    }
}
