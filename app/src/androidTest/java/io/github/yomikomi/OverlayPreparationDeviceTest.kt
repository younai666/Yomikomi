package io.github.yomikomi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yomikomi.capture.overlayBackgrounds
import io.github.yomikomi.capture.overlayBlocks
import io.github.yomikomi.translation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OverlayPreparationDeviceTest {
    private fun page(sources: List<RecognizedBlock>, translations: List<String>) = ScreenTranslator { request ->
        request.segments.mapIndexed { index, segment -> segment.copy(text = translations[index]) }
    }.translate(sources, "英语", "简体中文", "fixture", 0)

    @Test fun timeInsideASentenceAndEnglishMentionAreNotDiscardedAsMetadata() {
        for (text in listOf("Meet at 23:15 please", "Arrive 23:15", "@Alice please wait")) {
            val source = listOf(RecognizedBlock(text, PixelBounds(100, 100, 700, 140)))
            val translated = "请在这里等我，23:15见。"
            val result = overlayBlocks(page(source, listOf(translated)), source.associate { it.bounds to Color.BLACK },
                42f, PixelBounds(0, 0, 1080, 800))
            assertEquals(text, translated, result.single().layout.text.toString())
        }
    }

    @Test fun authorRowSeparatesParagraphsAndCannotBePaintedOverByAMergedLayout() {
        val source = listOf(RecognizedBlock("Previous message", PixelBounds(100, 100, 700, 138)),
            RecognizedBlock("User 23:15", PixelBounds(100, 142, 300, 160)),
            RecognizedBlock("Next message", PixelBounds(100, 168, 700, 206)))
        val result = overlayBlocks(page(source, listOf("上一条", "User 23:15", "下一条")),
            source.associate { it.bounds to Color.BLACK }, 24f, PixelBounds(0, 0, 1080, 800))
        assertEquals(2, result.size)
        assertEquals(listOf("b1"), result.first().ids)
        assertEquals(listOf("b3"), result.last().ids)
        assertTrue(result.first().bounds.bottom < source[1].bounds.top)
        assertTrue(result.last().bounds.top > source[1].bounds.bottom)
    }

    @Test fun longParagraphKeepsTheFullTailWrapsAndStaysInsideAvailableSpace() {
        val source = listOf(RecognizedBlock("A wrapped message", PixelBounds(100, 100, 660, 140)),
            RecognizedBlock("continues here", PixelBounds(100, 153, 660, 193)),
            RecognizedBlock("User 23:15", PixelBounds(100, 900, 400, 940)))
        val first = "完整保留人物、否定与限定条件，不用省略号替代内容。".repeat(4)
        val second = "包含 https://example.com/very/long/unbroken/identifier/01234567890123456789 以及表情😀和最终结尾【不可丢失】。"
        val result = overlayBlocks(page(source, listOf(first, second, "User 23:15")),
            source.associate { it.bounds to Color.BLACK }, 42f, PixelBounds(0, 0, 1080, 1200)).single()
        val text = "$first $second"
        assertEquals(text, result.layout.text.toString())
        assertEquals(text.length, result.layout.getLineEnd(result.layout.lineCount - 1))
        assertTrue(result.layout.lineCount > 2)
        assertTrue(result.layout.paint.textSize in 37.8f..42f)
        assertTrue(result.bounds.right <= 1080 && result.bounds.bottom < 900)
        assertTrue(result.layout.height <= result.bounds.height)
        for (line in 0 until result.layout.lineCount) {
            assertEquals(0, result.layout.getEllipsisCount(line))
            assertTrue(result.layout.getLineRight(line) <= result.layout.width + .5f)
        }
    }

    @Test fun physicallyUnfittableTextLeavesOriginalRatherThanClippingTheTail() {
        val source = listOf(RecognizedBlock("Small", PixelBounds(0, 0, 90, 32)))
        val result = overlayBlocks(page(source, listOf("完整长译文不允许强行裁切末尾。".repeat(12))),
            source.associate { it.bounds to Color.BLACK }, 42f, PixelBounds(0, 0, 100, 40))
        assertTrue(result.isEmpty())
        assertTrue(runtimeLogSnapshot().contains("OVERLAY layout_unfit"))
    }

    @Test fun hangingQuoteUsesFreeSpaceAboveWithoutCoveringTheInlineNameOrFollowingAuthor() {
        val source = listOf(RecognizedBlock("Previous", PixelBounds(165, 1056, 507, 1092)),
            RecognizedBlock("@Stalk124 ВМ", PixelBounds(215, 1152, 403, 1187)),
            RecognizedBlock("Тут, похоже, проще сразу переустановить всю", PixelBounds(417, 1158, 1031, 1185)),
            RecognizedBlock("Нейромиту - даже с полным списком Эмбедингов и Реранкеров...", PixelBounds(165, 1199, 1032, 1228)),
            RecognizedBlock("User 23:19", PixelBounds(165, 1273, 483, 1294)))
        val translations = listOf("上一条", "@Stalk124 ВМ", "这里看来更简单的做法是直接重装整个",
            "Нейромита——哪怕带着完整的嵌入模型和重排器列表……", "User 23:19")
        val screen = ScreenTranslator { r -> r.segments.mapIndexed { i, segment -> segment.copy(text = translations[i]) } }
            .translate(source, "俄语", "简体中文", "fixture", 0)
        val result = overlayBlocks(screen, source.associate { it.bounds to Color.BLACK }, 42f, PixelBounds(0, 0, 1080, 2400))
        val quote = result.single { "b3" in it.ids }
        assertEquals(listOf("b3", "b4"), quote.ids)
        assertEquals("${translations[2]} ${translations[3]}", quote.layout.text.toString())
        assertTrue(quote.bounds.top < source[2].bounds.top)
        assertTrue(quote.bounds.bottom < source.last().bounds.top)
        assertTrue(quote.layout.paint.textSize >= 37.8f)
        for (line in 0 until quote.layout.lineCount) {
            val left = quote.bounds.left + quote.layout.getPrimaryHorizontal(quote.layout.getLineStart(line))
            val right = quote.bounds.left + quote.layout.getLineRight(line)
            val top = quote.bounds.top + quote.layout.getLineTop(line)
            val bottom = quote.bounds.top + quote.layout.getLineBottom(line)
            val name = source[1].bounds
            assertFalse(name.left < right && name.right > left && name.top < bottom && name.bottom > top)
        }
    }

    @Test fun paragraphsShareABoundedFontAndUnknownIdsNeverReachLayout() {
        val source = listOf(RecognizedBlock("First line", PixelBounds(100, 100, 700, 138)),
            RecognizedBlock("Second line", PixelBounds(100, 153, 700, 190)))
        val translator = ScreenTranslator { request -> request.segments.map { it.copy(text = "这是一段自然的中文译文。") } }
        val page = translator.translate(source, "俄语", "简体中文", "fixture", 0)
        val colors = source.associate { it.bounds to Color.BLACK }
        val labels = overlayBlocks(page, colors, 42f, PixelBounds(0, 0, 1080, 2400))
        assertEquals(1, labels.size)
        assertEquals(page.blocks.map { it.id }, labels.single().ids)
        assertTrue(labels.all { it.layout.paint.textSize in 37.8f..42f })
        assertThrows(IllegalArgumentException::class.java) {
            overlayBlocks(page.copy(translations = mapOf("unknown" to "错误")), colors, 42f, PixelBounds(0, 0, 1080, 2400))
        }
    }

    @Test fun screenshotCanBeRecycledBeforeTranslationAndTextLayoutKeepsBoundsAndContrast() {
        AppRuntime.worker.submit {
            for (background in listOf(Color.WHITE, Color.rgb(24, 24, 23))) {
                val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(background)
                val bounds = PixelBounds(100, 200, 260, 240)
                val recognized = listOf(RecognizedBlock("Public fixture", bounds))
                val colors = try { overlayBackgrounds(bitmap, recognized) } finally { bitmap.recycle() }
                val translator = ScreenTranslator { it.segments.map { segment -> segment.copy(text = "这是一条需要缩小字号的完整译文。") } }
                val screen = translator.translate(recognized, "英语", "简体中文", "fixture", 0)
                val label = overlayBlocks(screen, colors, 48f, PixelBounds(0, 0, 1080, 2400)).single()
                assertTrue(bitmap.isRecycled)
                assertEquals(bounds, label.sourceBounds)
                assertEquals(background, label.background)
                assertEquals(screen.translations.values.single(), label.layout.text.toString())
                assertTrue(label.layout.width >= bounds.width)
                assertTrue(label.layout.height <= label.bounds.height)
                assertTrue(label.layout.paint.textSize in 43.2f..48f)
                assertEquals(if (background == Color.WHITE) Color.rgb(28, 28, 27) else Color.rgb(240, 238, 233), label.layout.paint.color)
            }
        }.get(10, TimeUnit.SECONDS)
    }
}
