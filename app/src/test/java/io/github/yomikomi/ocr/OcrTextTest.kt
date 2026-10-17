package io.github.yomikomi.ocr

import io.github.yomikomi.translation.PixelBounds
import org.junit.Assert.*
import org.junit.Test

class OcrTextTest {
    private fun text(value: String, y: Int, height: Int = 20, confidence: Float = 95f, x: Int = 10, width: Int = 80) =
        OcrText(value, PixelBounds(x, y, x + width, y + height), confidence)

    @Test fun largeBodyTextCannotRaiseTheFloorAndDeleteSmallValidLabelsOrPunctuation() {
        val lines = listOf(text("Large title", 20, 90), text("Second title", 140, 90), text("Third title", 260, 90),
            text("OK", 400, 18), text("No", 440, 18), text("うん", 480, 18), text("！", 520, 18))
        val result = selectOcrText(lines, emptyMap(), "英语", 16)
        assertEquals(lines.map { it.text }, result.map { it.text })
        assertEquals(lines.map { it.bounds }, result.map { it.bounds })
    }

    @Test fun microTextLowConfidenceAndInvalidFragmentsAreStillRejected() {
        val lines = listOf(text("small code", 10, 8), text("uncertain", 40, confidence = 49f),
            text("garbled\uFFFD", 70), text("@=", 100), text("valid", 130))
        assertEquals(listOf("valid"), selectOcrText(lines, emptyMap(), "英语", 16).map { it.text })
    }

    @Test fun russianShortRepliesAndQuestionMarksKeepTheirCoordinates() {
        val lines = listOf(text("Я", 10), text("Да", 50), text("Нет", 90), text("?", 130))
        val words = lines.associate { it.bounds to listOf(it.copy(confidence = 35f)) }
        val result = selectOcrText(lines, words, "俄语", 16)
        assertEquals(lines.map { it.text }, result.map { it.text })
        assertEquals(lines.map { it.bounds }, result.map { it.bounds })
        // An ambiguous, low-confidence single-character icon is not rescued as a reply.
        assertTrue(selectOcrText(listOf(lines[0]), mapOf(lines[0].bounds to listOf(lines[0].copy(confidence = 10f))), "俄语", 16).isEmpty())
    }

    @Test fun russianHandlesAndProseAreSplitOnlyAlongTheirActualWordBounds() {
        val line = text("@Alice привет мир", 100, width = 300)
        val words = listOf(text("@Alice", 100, x = 10, width = 80), text("привет", 100, x = 110, width = 90),
            text("мир", 100, x = 220, width = 60))
        val result = selectOcrText(listOf(line), mapOf(line.bounds to words), "俄语", 16)
        assertEquals(listOf("@Alice", "привет мир"), result.map { it.text })
        assertEquals(listOf(PixelBounds(10, 100, 90, 120), PixelBounds(110, 100, 280, 120)), result.map { it.bounds })
        val time = text("Stalk124 23:20", 200)
        assertEquals(time.text, selectOcrText(listOf(time), emptyMap(), "俄语", 16).single().text)
    }
}
