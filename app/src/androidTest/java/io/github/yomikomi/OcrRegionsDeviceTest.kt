package io.github.yomikomi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.googlecode.tesseract.android.TessBaseAPI
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class OcrRegionsDeviceTest {
    @Test fun cancellationStopsBetweenRegionsReleasesNativePageAndPreservesTheCallersBitmap() {
        AppRuntime.worker.submit {
            val image = Bitmap.createBitmap(1080, 1200, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 42f }
            Canvas(image).apply {
                drawColor(Color.WHITE)
                drawText("First readable line.", 100f, 180f, paint)
                drawText("Second readable line.", 100f, 380f, paint)
                drawText("Third readable line.", 100f, 580f, paint)
            }
            try {
                val area = Rect(0, 0, image.width, image.height)
                val failuresBefore = Regex("ERROR OCR recognize.failed").findAll(runtimeLogSnapshot()).count()
                var checks = 0
                assertThrows(CancellationException::class.java) {
                    AppRuntime.ocr.recognize(image, "英语", area, 1300) { ++checks >= 4 }
                }
                assertFalse(image.isRecycled)
                val log = runtimeLogSnapshot()
                assertTrue(log.contains("OCR cancelled request=1300 stage=2 completed=1 total=3"))
                assertTrue(log.contains("INFO OCR recognize.cancelled"))
                assertEquals(failuresBefore, Regex("ERROR OCR recognize.failed").findAll(log).count())
                // Successful reuse proves that cancelled page/iterator ownership was released on the worker.
                val recovered = AppRuntime.ocr.recognize(image, "英语", area, 1301)
                assertTrue(recovered.joinToString(" ") { it.text }.contains("Second readable"))
                assertTrue(recovered.all { it.bounds.left >= area.left && it.bounds.top >= area.top &&
                    it.bounds.right <= area.right && it.bounds.bottom <= area.bottom })
            } finally { image.recycle() }
        }.get(30, TimeUnit.SECONDS)
    }

    @Test fun alreadyCancelledOcrDoesNotEvenInitializeTheLanguageOrTouchTheBitmap() {
        AppRuntime.worker.submit {
            val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            try {
                // An unsupported language would fail initialization if cancellation were checked too late.
                assertThrows(CancellationException::class.java) {
                    AppRuntime.ocr.recognize(image, "not-a-language", Rect(0, 0, 100, 100), 1302) { true }
                }
                assertFalse(image.isRecycled)
            } finally { image.recycle() }
        }.get(10, TimeUnit.SECONDS)
    }

    @Test fun smallUiLabelsSurviveBesideLargeHeadingsUsingRealOcr() {
        AppRuntime.worker.submit {
            val image = Bitmap.createBitmap(1080, 1200, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(image)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 90f }
                listOf("Large title", "Second title", "Third title").forEachIndexed { index, text ->
                    canvas.drawText(text, 100f, 200f + index * 200, paint)
                }
                paint.textSize = 30f
                canvas.drawText("OK", 100f, 900f, paint)
                val result = AppRuntime.ocr.recognize(image, "英语", Rect(0, 100, 1080, 1100), 1303)
                assertTrue(result.any { it.text.contains("Large title") })
                val small = result.single { it.text == "OK" }
                assertTrue(small.bounds.top in 850..900)
                assertTrue(small.bounds.height < result.filter { it.text.contains("title") }.minOf { it.bounds.height } / 2)
            } finally { image.recycle() }
        }.get(30, TimeUnit.SECONDS)
    }

    @Test fun emptyAndOnlyMicroTextPagesHaveNoRecognitionRegions() {
        AppRuntime.worker.submit {
            val image = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(image)
                canvas.drawColor(Color.WHITE)
                assertTrue(AppRuntime.ocr.recognize(image, "英语", Rect(0, 0, 1080, 2340), 1002).isEmpty())
                canvas.drawText("ERROR very small code fragments", 100f, 500f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f })
                assertTrue(AppRuntime.ocr.recognize(image, "英语", Rect(0, 0, 1080, 2340), 1003).isEmpty())
                val log = runtimeLogSnapshot()
                assertTrue(log.contains("OCR recognition request=1002 regions=0"))
                assertTrue(log.contains("OCR recognition request=1003 regions=0"))
            } finally { image.recycle() }
        }.get(30, TimeUnit.SECONDS)
    }

    @Test fun embeddedMicroCodeIsRejectedBeforeRecognitionWhileNormalTextSurvives() {
        AppRuntime.worker.submit {
            val image = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
            val engine = TessBaseAPI()
            try {
                val canvas = Canvas(image)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 42f }
                canvas.drawText("Hello, welcome to the library.", 100f, 180f, paint)
                paint.typeface = Typeface.MONOSPACE
                paint.textSize = 12f
                repeat(65) { row ->
                    canvas.drawText("ERROR RuntimeError: embeddings environment is not initialized; controller.activate_model()", 100f, 320f + row * 15, paint)
                }
                paint.typeface = Typeface.DEFAULT
                paint.textSize = 42f
                canvas.drawText("Please read the next page.", 100f, 1500f, paint)
                assertTrue(engine.init(AppRuntime.root.absolutePath, "eng", TessBaseAPI.OEM_LSTM_ONLY))
                engine.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                val baselineAt = SystemClock.elapsedRealtime()
                engine.setImage(image)
                val baseline = engine.utF8Text
                val baselineMs = SystemClock.elapsedRealtime() - baselineAt
                assertTrue(baseline.contains("RuntimeError")) // The fixture exercises the actual expensive failure mode.
                AppRuntime.ocr.load("英语")
                val optimizedAt = SystemClock.elapsedRealtime()
                val output = AppRuntime.ocr.recognize(image, "英语", Rect(0, 0, 1080, 2340), 1001)
                val optimizedMs = SystemClock.elapsedRealtime() - optimizedAt
                val text = output.joinToString(" ") { it.text }
                assertTrue(text, text.contains("library") && text.contains("next page"))
                assertFalse(text.contains("RuntimeError"))
                val log = runtimeLogSnapshot().lineSequence().filter { "request=1001" in it }.toList()
                val filter = log.last { "OCR filter " in it }
                val candidates = Regex("candidates=(\\d+)").find(filter)!!.groupValues[1].toInt()
                val kept = Regex("kept=(\\d+)").find(filter)!!.groupValues[1].toInt()
                assertTrue("Expected dense small regions: $filter", candidates > 30 && kept <= 4)
                assertTrue(log.any { "OCR recognition " in it && "regions=$kept " in it })
                assertTrue(log.any { "OCR detection " in it && "confidence_available=0" in it })
                android.util.Log.i("YomikomiOcrFixture", "dense_code baseline_ms=$baselineMs optimized_ms=$optimizedMs candidates=$candidates kept=$kept baseline_chars=${baseline.length} output_blocks=${output.size}")
            } finally { engine.recycle(); image.recycle() }
        }.get(90, TimeUnit.SECONDS)
    }

    @Test fun suppliedDiscordDetectionAndRecognition() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ocrFixture") == "true")
        val fixture = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ocr-fixture.png")
        AppRuntime.worker.submit {
            val original = checkNotNull(BitmapFactory.decodeFile(fixture.absolutePath))
            val image = Bitmap.createScaledBitmap(original, 1080, 2340, true)
            val engine = TessBaseAPI()
            try {
                assertTrue(engine.init(AppRuntime.root.absolutePath, "rus+eng", TessBaseAPI.OEM_LSTM_ONLY))
                engine.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                engine.setImage(image)
                val started = SystemClock.elapsedRealtime()
                val lines = engine.textlines
                val boxes = try { lines.boxRects } finally { lines.recycle() }
                android.util.Log.i("YomikomiOcrFixture", "detection_ms=${SystemClock.elapsedRealtime() - started} candidates=${boxes.size} tiny=${boxes.count { it.height() < 16 }}")
                val recognizeAt = SystemClock.elapsedRealtime()
                engine.utF8Text
                android.util.Log.i("YomikomiOcrFixture", "baseline_recognition_ms=${SystemClock.elapsedRealtime() - recognizeAt}")
                val baselineBody = mutableListOf<String>()
                val iterator = checkNotNull(engine.resultIterator)
                try {
                    iterator.begin()
                    do {
                        val box = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                        if (box != null && box.top >= 1610 && box.bottom <= 2070)
                            baselineBody += iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE).orEmpty()
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                } finally { iterator.delete() }
                val binary = engine.thresholdedImage
                val binaryBitmap = try { com.googlecode.leptonica.android.WriteFile.writeBitmap(binary) } finally { binary.recycle() }
                val masked = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(masked)
                    canvas.drawColor(Color.WHITE)
                    val regions = io.github.yomikomi.ocr.recognitionRegions(boxes.map {
                        io.github.yomikomi.translation.PixelBounds(it.left, it.top, it.right, it.bottom)
                    }, 1080, 2340)
                    for (region in regions) {
                        val rect = Rect(region.left, region.top, region.right, region.bottom)
                        canvas.drawBitmap(binaryBitmap, rect, rect, null)
                    }
                    val batchAt = SystemClock.elapsedRealtime()
                    engine.setImage(masked)
                    val batch = engine.utF8Text
                    android.util.Log.i("YomikomiOcrFixture", "binary_batch_ms=${SystemClock.elapsedRealtime() - batchAt} characters=${batch.length}")
                } finally { binaryBitmap.recycle(); masked.recycle() }
                AppRuntime.ocr.load("俄语")
                val optimizedAt = SystemClock.elapsedRealtime()
                val output = AppRuntime.ocr.recognize(image, "俄语", Rect(0, 110, 1080, 2313))
                val text = output.joinToString(" ") { it.text }
                android.util.Log.i("YomikomiOcrFixture", "discord optimized_ms=${SystemClock.elapsedRealtime() - optimizedAt} blocks=${output.size}")
                assertTrue(output.all { it.bounds.left >= 0 && it.bounds.top >= 110 && it.bounds.right <= 1080 && it.bounds.bottom <= 2313 })
                listOf("пакеты", "похоже", "всю", "вниз листаешь", "переустанавливаю", "некоторые элементы").forEach {
                    assertTrue("Missing body text: $it; $text", text.contains(it))
                }
                val expected = normalized(baselineBody.joinToString(" "))
                val actual = normalized(output.filter { it.bounds.top >= 1610 && it.bounds.bottom <= 2070 }.joinToString(" ") { it.text })
                assertTrue("Expected a substantial body sample, got ${expected.length} characters", expected.length > 150)
                val similarity = 1f - distance(expected, actual).toFloat() / maxOf(expected.length, actual.length)
                assertTrue("Body character similarity fell to $similarity", similarity >= .95f)
                android.util.Log.i("YomikomiOcrFixture", "discord body_chars=${expected.length} similarity=$similarity")
                val initializationCount = Regex("OCR initialize.started").findAll(runtimeLogSnapshot()).count()
                repeat(3) { run ->
                    val id = 1200L + run
                    val at = SystemClock.elapsedRealtime()
                    assertTrue(AppRuntime.ocr.recognize(image, "俄语", Rect(0, 110, 1080, 2313), id).isNotEmpty())
                    val logs = runtimeLogSnapshot().lineSequence().filter { "request=$id " in it }.toList()
                    val summary = logs.last { "OCR recognition " in it }
                    val count = Regex("regions=(\\d+)").find(summary)!!.groupValues[1].toInt()
                    val rois = logs.filter { "OCR roi " in it }.takeLast(count)
                    assertEquals(count, rois.size)
                    assertEquals((0 until count).toList(), rois.map { Regex("index=(\\d+)").find(it)!!.groupValues[1].toInt() })
                    assertTrue(rois.all { "cpu_ms=" in it && "setup_ms=" in it && "priority=" in it })
                    assertTrue(logs.last { "OCR binary_reuse " in it }.contains("set_image_calls=2"))
                    android.util.Log.i("YomikomiOcrFixture", "repeat=$run total_ms=${SystemClock.elapsedRealtime() - at} $summary")
                }
                assertEquals(initializationCount, Regex("OCR initialize.started").findAll(runtimeLogSnapshot()).count())
            } finally {
                engine.recycle()
                image.recycle()
                if (original !== image) original.recycle()
            }
        }.get(90, TimeUnit.SECONDS)
    }

    private fun normalized(text: String) = text.lowercase().filter(Char::isLetterOrDigit)

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val row = IntArray(b.length + 1)
            row[0] = i + 1
            for (j in b.indices) row[j + 1] = minOf(row[j] + 1, previous[j + 1] + 1,
                previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = row
        }
        return previous.last()
    }
}
