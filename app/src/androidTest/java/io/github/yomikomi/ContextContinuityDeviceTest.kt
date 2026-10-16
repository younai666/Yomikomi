package io.github.yomikomi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yomikomi.capture.screenAppearance
import io.github.yomikomi.models.ModelCatalog
import io.github.yomikomi.translation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ContextContinuityDeviceTest {
    private val area = Rect(0, 136, 1080, 2337)
    private fun image(lines: List<String>): Bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).also { bitmap ->
        Canvas(bitmap).apply {
            drawColor(Color.rgb(249, 247, 242))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f }
            lines.forEachIndexed { index, text -> drawText(text, 80f, 500f + index * 180f, paint) }
        }
    }

    @Test fun realOcrKeepsScrollingContextAndRejectsUnrelatedPageOnSameFlatSurface() {
        AppRuntime.worker.submit {
            AppRuntime.ensureLoaded(ModelCatalog.ocr, "英语")
            val requests = mutableListOf<TranslationRequest>()
            val translator = ScreenTranslator { request -> requests += request; request.segments }
            val pages = listOf(
                listOf("Alice carried Frost through the forest.", "She stopped beside the river."),
                listOf("She stopped beside the river.", "Alice lifted Frost and looked across."),
                listOf("Your order has shipped.", "Payment confirmed. Delivery tomorrow."),
            )
            pages.forEachIndexed { index, lines ->
                val bitmap = image(lines)
                try {
                    val blocks = AppRuntime.ocr.recognize(bitmap, "英语", area)
                    assertEquals("OCR: ${blocks.map { it.text }}", 2, blocks.size)
                    val screen = translator.translate(blocks, "英语", "中文", "reader", index * 100L,
                        appearance = screenAppearance(bitmap, area, blocks))
                    assertEquals(index == 1, requests.last().previousScreen != null)
                    assertTrue(translator.accept(screen, "reader", index * 100L))
                } finally { bitmap.recycle() }
            }
        }.get(60, TimeUnit.SECONDS)
    }

    @Test fun bitmapSamplerIgnoresOcrGlyphsAndCanRetainAnUnchangedIllustratedScene() {
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                val paint = Paint()
                for (row in 0..15) for (column in 0..11) {
                    paint.color = if ((row + column) % 2 == 0) Color.rgb(40, 40, 40) else Color.rgb(200, 200, 200)
                    drawRect(column * 90f, area.top + row * area.height() / 16f,
                        (column + 1) * 90f, area.top + (row + 1) * area.height() / 16f, paint)
                }
            }
            val bounds = PixelBounds(100, 900, 500, 960)
            val first = listOf(RecognizedBlock("Alice!", bounds))
            val next = listOf(RecognizedBlock("Who are you?", bounds))
            val translator = ScreenTranslator { it.segments }
            val screen = translator.translate(first, "英语", "中文", "reader", 0,
                appearance = screenAppearance(bitmap, area, first))
            translator.accept(screen, "reader", 0)
            Canvas(bitmap).drawRect(100f, 900f, 500f, 960f, Paint().apply { color = Color.RED })
            val continued = translator.translate(next, "英语", "中文", "reader", 100,
                appearance = screenAppearance(bitmap, area, next))
            assertTrue(continued.contextUsed)
        } finally { bitmap.recycle() }
    }
}
