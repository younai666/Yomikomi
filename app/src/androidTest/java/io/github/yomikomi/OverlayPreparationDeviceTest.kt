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
