package io.github.yomikomi.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import io.github.yomikomi.translation.PixelBounds
import io.github.yomikomi.translation.RecognizedBlock
import java.io.File
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog
import org.json.JSONArray
import org.json.JSONObject

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
            if (!candidate.init(root.absolutePath, languages, TessBaseAPI.OEM_LSTM_ONLY)) {
                candidate.recycle()
                error("OCR 语言包加载失败")
            }
            candidate.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
            api = candidate
            language = languages
        }
    }

    fun recognize(bitmap: Bitmap, source: String, area: Rect, traceId: Long = 0, traceSecret: String = ""): List<RecognizedBlock> =
        RunLog.measure(LogModule.OCR, "recognize", "width" to area.width(), "height" to area.height()) {
            recognizeImage(bitmap, source, area, traceId, traceSecret).also { RunLog.info(LogModule.OCR, "blocks", "request" to traceId, "count" to it.size) }
        }

    private fun recognizeImage(bitmap: Bitmap, source: String, area: Rect, traceId: Long, traceSecret: String): List<RecognizedBlock> {
        load(source)
        val engine = checkNotNull(api)
        // Crop before thresholding. Black system-bar masks can skew Tesseract's page segmentation.
        val cropped = Bitmap.createBitmap(bitmap, area.left, area.top, area.width(), area.height())
        try {
            engine.setImage(cropped)
            engine.utF8Text // Runs recognition before acquiring the iterator.
            val iterator = engine.resultIterator ?: return emptyList()
            try {
                val blocks = mutableListOf<RecognizedBlock>()
                val raw = JSONArray()
                val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                iterator.begin()
                do {
                    val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                    val box = iterator.getBoundingRect(level)
                    val confidence = iterator.confidence(level)
                    if (text.isNotEmpty() && box != null && !box.isEmpty) raw.put(JSONObject()
                        .put("index", raw.length()).put("text", text).put("confidence", confidence.toDouble())
                        .put("bounds", JSONArray(listOf(box.left + area.left, box.top + area.top, box.right + area.left, box.bottom + area.top))))
                    if (text.isNotEmpty() && confidence >= 50 && box != null && !box.isEmpty &&
                        box.height() >= maxOf(10, area.width() / 65) &&
                        text.any { it.isLetterOrDigit() || it in "!?！？…" } && '\uFFFD' !in text) {
                        blocks += RecognizedBlock(text, PixelBounds(box.left + area.left, box.top + area.top,
                            box.right + area.left, box.bottom + area.top))
                    }
                } while (iterator.next(level))
                // The line has already passed confidence checks. Russian x-height can be tiny beside
                // an avatar/badge, so lexical words must not be deleted by per-word size/confidence gates.
                // Ambiguous single-letter icon detections still need confidence, without a size gate.
                val words = mutableMapOf<PixelBounds, MutableList<Pair<String, PixelBounds>>>()
                if (source == "俄语") {
                    iterator.begin()
                    do {
                        val word = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                        val wordBox = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        val lineBox = iterator.getBoundingRect(level)
                        if (wordBox != null && lineBox != null && word.isNotBlank() &&
                            ((word.count { it in '\u0400'..'\u04ff' }.let { it >= 2 ||
                                (it == 1 && iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD) >= 30) }) ||
                                Regex("[-—>→!?！？….,:;()]+").matches(word) ||
                                (iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD) >= 30 &&
                                    wordBox.height() >= lineBox.height() * (if (word.any(Char::isDigit) && word.none(Char::isLetter)) .75f else .4f)))) {
                            val key = PixelBounds(lineBox.left + area.left, lineBox.top + area.top, lineBox.right + area.left, lineBox.bottom + area.top)
                            words.getOrPut(key) { mutableListOf() }.add(word to PixelBounds(wordBox.left + area.left,
                                wordBox.top + area.top, wordBox.right + area.left, wordBox.bottom + area.top))
                        }
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                }
                val minimumHeight = (blocks.map { it.bounds.height }.sorted().let { it.getOrNull(it.size / 2) } ?: 0) / 2
                val selected = blocks.filter { it.bounds.height >= minimumHeight }.flatMap { block ->
                    if (source != "俄语") return@flatMap listOf(block)
                    if (Regex("\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(block.text) && block.text.split(' ').size <= 4) return@flatMap listOf(block)
                    val kept = words[block.bounds].orEmpty()
                    val cleaned = kept.joinToString(" ") { it.first }
                    if (kept.isEmpty() || !(cleaned.any(Char::isLetterOrDigit) || Regex("[!?！？…]+").matches(cleaned))) emptyList()
                    else {
                        fun line(parts: List<Pair<String, PixelBounds>>) = RecognizedBlock(parts.joinToString(" ") { it.first }, PixelBounds(
                            parts.minOf { it.second.left }, parts.minOf { it.second.top }, parts.maxOf { it.second.right }, parts.maxOf { it.second.bottom }))
                        val handle = kept.indexOfFirst { it.first.startsWith('@') }
                        val prose = if (handle >= 0) (handle + 1 until kept.size).firstOrNull { index ->
                            kept[index].first.count { it in '\u0400'..'\u04ff' } >= 3
                        } ?: -1 else -1
                        if (prose > handle && handle >= 0) buildList {
                            if (handle > 0) add(line(kept.take(handle)))
                            add(line(kept.subList(handle, prose)))
                            add(line(kept.drop(prose)))
                        } else listOf(line(kept))
                    }
                }
                if (traceId > 0) {
                    RunLog.text(LogModule.OCR, "ocr_raw", traceId, raw.toString(), traceSecret)
                    RunLog.text(LogModule.OCR, "ocr_selected", traceId, JSONArray().apply { selected.forEach {
                        put(JSONObject().put("text", it.text).put("bounds", JSONArray(listOf(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom))))
                    } }.toString(), traceSecret)
                }
                return selected
            } finally { iterator.delete() }
        } finally { engine.clear(); if (cropped !== bitmap) cropped.recycle() }
    }
}
