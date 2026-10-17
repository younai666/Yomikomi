package io.github.yomikomi.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Debug
import android.os.Process
import com.googlecode.tesseract.android.TessBaseAPI
import io.github.yomikomi.translation.PixelBounds
import io.github.yomikomi.translation.RecognizedBlock
import java.io.File
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException

/** A single real OCR backend, using the official fast LSTM Japanese/English/Russian data. */
class TesseractOcr(private val root: File) {
    private var api: TessBaseAPI? = null
    private var language: String? = null

    fun load(source: String) = selectLanguage(when (source) {
        "日语" -> "jpn"
        "英语" -> "eng"
        "俄语" -> "rus+eng"
        "自动检测" -> "eng+jpn+rus"
        else -> error("当前 OCR 支持日语、英语和俄语")
    })

    private fun selectLanguage(languages: String) {
        if (language == languages) return
        RunLog.measure(LogModule.OCR, "initialize") {
            api?.recycle()
            api = null
            language = null
            val candidate = try { TessBaseAPI() }
            catch (error: LinkageError) { throw IllegalStateException("OCR 组件不可用，请重新安装应用", error) }
            try {
                check(candidate.init(root.absolutePath, languages, TessBaseAPI.OEM_LSTM_ONLY)) { "OCR 语言包加载失败" }
                candidate.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                api = candidate
                language = languages
            } finally {
                if (api !== candidate) candidate.recycle()
            }
        }
    }

    fun recognize(bitmap: Bitmap, source: String, area: Rect, traceId: Long = 0, traceSecret: String = "",
        isCancelled: () -> Boolean = { false }): List<RecognizedBlock> =
        RunLog.measure(LogModule.OCR, "recognize", "width" to area.width(), "height" to area.height()) {
            recognizeImage(bitmap, source, area, traceId, traceSecret, isCancelled).also { RunLog.info(LogModule.OCR, "blocks", "request" to traceId, "count" to it.size) }
        }

    private fun recognizeImage(bitmap: Bitmap, source: String, area: Rect, traceId: Long, traceSecret: String,
        isCancelled: () -> Boolean): List<RecognizedBlock> {
        fun checkCancelled(stage: Int, completed: Int = 0, total: Int = 0) {
            if (isCancelled()) {
                RunLog.info(LogModule.OCR, "cancelled", "request" to traceId, "stage" to stage,
                    "completed" to completed, "total" to total)
                throw CancellationException("OCR 已取消")
            }
        }
        checkCancelled(0)
        load(source)
        val engine = checkNotNull(api)
        // Crop before thresholding. Black system-bar masks can skew Tesseract's page segmentation.
        val cropped = Bitmap.createBitmap(bitmap, area.left, area.top, area.width(), area.height())
        try {
            val detectionAt = System.nanoTime()
            engine.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
            engine.setImage(cropped)
            val lines = engine.textlines
            val candidates = try { lines.boxRects.map { PixelBounds(it.left, it.top, it.right, it.bottom) } }
                finally { lines.recycle() }
            RunLog.info(LogModule.OCR, "detection", "request" to traceId, "candidates" to candidates.size,
                "confidence_available" to 0, "elapsed_ms" to RunLog.elapsed(detectionAt))
            val filterAt = System.nanoTime()
            val regions = recognitionRegions(candidates, cropped.width, cropped.height)
            RunLog.info(LogModule.OCR, "filter", "request" to traceId, "candidates" to candidates.size,
                "kept" to regions.size, "rejected" to (candidates.size - regions.size), "elapsed_ms" to RunLog.elapsed(filterAt))
            checkCancelled(1, total = regions.size)
            val observations = mutableListOf<OcrText>()
            val words = mutableMapOf<PixelBounds, MutableList<OcrText>>()
            // AnalyseLayout already produced this 1-bit image. Reuse it for every rectangle instead
            // of thresholding/copying the original 32-bit screenshot repeatedly inside SetRectangle.
            val binaryAt = System.nanoTime()
            val binary = engine.thresholdedImage
            try { engine.setImage(binary) } finally { binary.recycle() }
            RunLog.info(LogModule.OCR, "binary_reuse", "request" to traceId, "depth" to 1,
                "bitmap_copies" to if (cropped !== bitmap) 1 else 0, "set_image_calls" to 2,
                "elapsed_ms" to RunLog.elapsed(binaryAt))
            var recognitionMs = 0L
            var recognitionCpuNs = 0L
            var maximumRoiMs = 0L
            val extractionAt = System.nanoTime()
            engine.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            for ((index, region) in regions.withIndex()) {
                checkCancelled(2, index, regions.size)
                val setupAt = System.nanoTime()
                engine.setRectangle(region.left, region.top, region.width, region.height)
                val setupMs = RunLog.elapsed(setupAt)
                val recognitionAt = System.nanoTime()
                val cpuAt = Debug.threadCpuTimeNanos()
                try { engine.utF8Text }
                finally {
                    val wallMs = RunLog.elapsed(recognitionAt)
                    val cpuNs = Debug.threadCpuTimeNanos() - cpuAt
                    recognitionMs += wallMs
                    recognitionCpuNs += cpuNs
                    maximumRoiMs = maxOf(maximumRoiMs, wallMs)
                    RunLog.info(LogModule.OCR, "roi", "request" to traceId, "index" to index,
                        "x" to (region.left + area.left), "y" to (region.top + area.top),
                        "width" to region.width, "height" to region.height, "setup_ms" to setupMs,
                        "elapsed_ms" to wallMs, "cpu_ms" to cpuNs / 1_000_000,
                        "priority" to Process.getThreadPriority(Process.myTid()))
                }
                val iterator = engine.resultIterator ?: continue
                try {
                    val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                    iterator.begin()
                    do {
                        val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                        val box = iterator.getBoundingRect(level)
                        val confidence = iterator.confidence(level)
                        if (text.isNotEmpty() && box != null && !box.isEmpty) {
                            observations += OcrText(text, PixelBounds(box.left + area.left, box.top + area.top,
                                box.right + area.left, box.bottom + area.top), confidence)
                        }
                    } while (iterator.next(level))
                    // Capture native word evidence here; confidence/geometry policy is pure postprocessing.
                    if (source == "俄语") {
                        iterator.begin()
                        do {
                            val word = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                            val wordBox = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                            val lineBox = iterator.getBoundingRect(level)
                            if (wordBox != null && lineBox != null && word.isNotBlank()) {
                                val key = PixelBounds(lineBox.left + area.left, lineBox.top + area.top, lineBox.right + area.left, lineBox.bottom + area.top)
                                words.getOrPut(key) { mutableListOf() }.add(OcrText(word, PixelBounds(wordBox.left + area.left,
                                    wordBox.top + area.top, wordBox.right + area.left, wordBox.bottom + area.top),
                                    iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)))
                            }
                        } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                    }
                } finally { iterator.delete() }
            }
            RunLog.info(LogModule.OCR, "recognition", "request" to traceId, "regions" to regions.size,
                "raw_lines" to observations.size, "elapsed_ms" to recognitionMs,
                "cpu_ms" to recognitionCpuNs / 1_000_000, "max_roi_ms" to maximumRoiMs,
                "extraction_ms" to (RunLog.elapsed(extractionAt) - recognitionMs))
            checkCancelled(3, regions.size, regions.size)
            val postfilterAt = System.nanoTime()
            val selected = selectOcrText(observations, words, source, maxOf(10, area.width() / 65))
            val postfilterMs = RunLog.elapsed(postfilterAt)
            if (traceId > 0) {
                RunLog.text(LogModule.OCR, "ocr_raw", traceId, JSONArray().apply { observations.forEachIndexed { index, line ->
                    put(JSONObject().put("index", index).put("text", line.text).put("confidence", line.confidence.toDouble())
                        .put("bounds", JSONArray(listOf(line.bounds.left, line.bounds.top, line.bounds.right, line.bounds.bottom))))
                } }.toString(), traceSecret)
                RunLog.text(LogModule.OCR, "ocr_selected", traceId, JSONArray().apply { selected.forEach {
                    put(JSONObject().put("text", it.text).put("bounds", JSONArray(listOf(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom))))
                } }.toString(), traceSecret)
            }
            RunLog.info(LogModule.OCR, "postfilter", "request" to traceId, "raw_lines" to observations.size,
                "kept" to selected.size, "elapsed_ms" to postfilterMs)
            return selected
        } finally { try { engine.clear() } finally { if (cropped !== bitmap) cropped.recycle() } }
    }
}
