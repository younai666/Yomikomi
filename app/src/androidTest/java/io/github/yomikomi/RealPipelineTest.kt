package io.github.yomikomi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yomikomi.models.ModelCatalog
import io.github.yomikomi.translation.HyMtTranslationEngine
import io.github.yomikomi.translation.PixelBounds
import io.github.yomikomi.translation.RecognizedBlock
import io.github.yomikomi.translation.ScreenTranslator
import io.github.yomikomi.translation.TranslationRequest
import io.github.yomikomi.translation.TranslationSegment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RealPipelineTest {
    @Test fun actualHyMtTranslatesJapaneseWholeScreenIntoChineseRatherThanCopyingSource() {
        AppRuntime.worker.submit {
            AppRuntime.ensureLoaded(ModelCatalog.translation)
            val segments = listOf("こんにちは。", "今日はいい天気です。", "本を読みましょう。")
                .mapIndexed { index, text -> TranslationSegment("b${index + 1}", text) }
            val result = HyMtTranslationEngine().translate(TranslationRequest(
                "日语", "简体中文", segments, null))
            android.util.Log.i("YomikomiPipelineTest", "Public Japanese fixture: $result")
            assertEquals(segments.map { it.id }, result.map { it.id })
            assertTrue(result.toString(), result.first().text.contains("好"))
            assertTrue(result.toString(), result[1].text.contains("天气"))
            assertTrue(result.toString(), result[2].text.contains("读"))
            result.zip(segments).forEach { (translated, source) -> assertNotEquals(source.text, translated.text) }
        }.get(90, TimeUnit.SECONDS)
    }

    @Test fun loadingSelectedOcrLanguageDoesNotInitializeOtherLanguagesOrReloadItForRecognition() {
        AppRuntime.worker.submit {
            val ocr = AppRuntime.ocr
            ocr.load("日语")
            fun initializationCount() = Regex("INFO OCR initialize.started").findAll(runtimeLogSnapshot()).count()
            val before = initializationCount()
            AppRuntime.ensureLoaded(ModelCatalog.ocr, "英语")
            assertEquals(before + 1, initializationCount())
            AppRuntime.ensureLoaded(ModelCatalog.ocr, "英语")
            val image = Bitmap.createBitmap(500, 160, Bitmap.Config.ARGB_8888)
            try {
                Canvas(image).apply {
                    drawColor(Color.WHITE)
                    drawText("Hello, library.", 30f, 90f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f })
                }
                assertTrue(ocr.recognize(image, "英语", Rect(0, 0, 500, 160)).isNotEmpty())
                assertEquals(before + 1, initializationCount())
            } finally { image.recycle() }
        }.get(30, TimeUnit.SECONDS)
    }

    @Test fun ocrIgnoresMaskedSystemBarsAndPreservesGlobalCoordinates() {
        AppRuntime.worker.submit {
            AppRuntime.ensureLoaded(ModelCatalog.ocr, "英语")
            val image = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
            try {
                Canvas(image).apply {
                    drawColor(Color.BLACK)
                    drawRect(0f, 136f, 1080f, 2337f, Paint().apply { color = Color.rgb(249, 247, 242) })
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
                    listOf("Hello, welcome.", "This is a quiet library.", "Please read the next page.").forEachIndexed { i, line ->
                        drawText(line, 80f, 250f + i * 180f, paint)
                    }
                }
                val blocks = AppRuntime.ocr.recognize(image, "英语", Rect(0, 136, 1080, 2337))
                assertEquals(3, blocks.size)
                assertTrue(blocks.any { it.text.contains("library") })
                assertTrue(blocks.first().bounds.top in 200..250)
            } finally { image.recycle() }
        }.get(30, TimeUnit.SECONDS)
    }
    @Test fun ocrReadsThreeScriptsAndReturnsPixelCoordinates() {
        AppRuntime.worker.submit {
            AppRuntime.ensureLoaded(ModelCatalog.ocr, "英语")
            listOf("英语" to "This is a quiet library.", "日语" to "今日はいい天気です。", "俄语" to "Это тихая библиотека.")
                .forEach { (language, text) ->
                    val image = Bitmap.createBitmap(1080, 800, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(image).apply {
                            drawColor(Color.WHITE)
                            drawText(text, 80f, 250f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 52f })
                        }
                        val blocks = AppRuntime.ocr.recognize(image, language, Rect(0, 0, 1080, 800))
                        assertTrue("$language: ${blocks.map { it.text }}", blocks.isNotEmpty())
                        val recognized = blocks.joinToString { it.text }
                        val expected = when (language) { "英语" -> "library"; "日语" -> "天気"; else -> "библиотека" }
                        assertTrue("$language: $recognized", recognized.contains(expected))
                        assertTrue(blocks.all { it.bounds.left in 70..100 && it.bounds.top in 180..260 && it.bounds.bottom <= 270 })
                    } finally { image.recycle() }
                }
        }.get(120, TimeUnit.SECONDS)
    }

    @Test fun actualHyMtTranslatesWholeScreenAndPreservesIds() {
        AppRuntime.worker.submit {
            AppRuntime.ensureLoaded(ModelCatalog.translation)
            val translator = ScreenTranslator(HyMtTranslationEngine())
            val result = translator.translate(listOf(
                RecognizedBlock("Hello, welcome.", PixelBounds(80, 200, 600, 250)),
                RecognizedBlock("This is a quiet library.", PixelBounds(80, 380, 800, 430)),
            ), "英语", "简体中文", "test", 0)
            assertEquals(2, result.translations.size)
            assertTrue(result.translations.values.all { it.any { char -> char in '\u4e00'..'\u9fff' } })
            assertEquals(result.blocks.map { it.id }.toSet(), result.translations.keys)
            translator.accept(result, "test", 0)
            val next = translator.translate(result.blocks.map { RecognizedBlock(it.text, it.bounds) },
                "英语", "简体中文", "test", 1)
            assertEquals(result.blocks.map { it.id }, next.blocks.map { it.id })
            listOf("日语" to "今日はいい天気です。", "俄语" to "Это тихая библиотека.").forEach { (language, text) ->
                val page = translator.translate(listOf(RecognizedBlock(text, PixelBounds(80, 200, 800, 250))),
                    language, "简体中文", "test", 2)
                assertTrue("$language: ${page.translations}", page.translations.values.single().any { it in '\u4e00'..'\u9fff' })
            }
        }.get(300, TimeUnit.SECONDS)
    }
}
