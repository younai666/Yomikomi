package io.github.yomikomi.ocr

import io.github.yomikomi.translation.PixelBounds
import org.junit.Assert.*
import org.junit.Test

class OcrRegionsTest {
    @Test fun denseMicroCodeDoesNotLowerTheReadableTextFloorOrReachRecognition() {
        val code = (0 until 100).map { PixelBounds(160, 300 + it * 12, 1000, 310 + it * 12) }
        val body = listOf(PixelBounds(160, 200, 1000, 235), PixelBounds(160, 1600, 1000, 1638))
        val kept = recognitionRegions(code + body, 1080, 2203)
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.height > 30 })
        assertTrue(kept.none { it.top in 280..1500 })
    }

    @Test fun smallUiSingleJapaneseGlyphAndPunctuationSurviveAndCoordinatesStayClipped() {
        val input = listOf(PixelBounds(0, 0, 300, 18), PixelBounds(1010, 100, 1030, 120), PixelBounds(30, 200, 33, 230))
        val kept = recognitionRegions(input, 1080, 2340)
        assertEquals(3, kept.size)
        input.zip(kept).forEach { (box, region) ->
            assertTrue(region.left <= box.left && region.top <= box.top && region.right >= box.right && region.bottom >= box.bottom)
            assertTrue(region.left >= 0 && region.top >= 0 && region.right <= 1080 && region.bottom <= 2340)
        }
    }

    @Test fun ignoresEmptyOutsideAndNearZeroAreaFragments() {
        assertTrue(recognitionRegions(listOf(PixelBounds(0, 0, 0, 0), PixelBounds(20, 20, 21, 60),
            PixelBounds(2000, 20, 2050, 50), PixelBounds(30, 2000, 50, 2010)), 1080, 1800).isEmpty())
    }
}
