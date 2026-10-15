package io.github.yomikomi.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import io.github.yomikomi.translation.PixelBounds
import io.github.yomikomi.translation.RecognizedBlock
import java.io.File

/** A single real OCR backend, using the official fast LSTM Japanese/English/Russian data. */
class TesseractOcr(private val root: File) {
    private var api: TessBaseAPI? = null
    private var language: String? = null

    fun load() = selectLanguage("eng+jpn+rus")

    private fun selectLanguage(languages: String) {
        if (language == languages) return
        api?.recycle()
        api = null
        language = null
        val candidate = TessBaseAPI()
        if (!candidate.init(root.absolutePath, languages, TessBaseAPI.OEM_LSTM_ONLY)) {
            candidate.recycle()
            error("OCR 语言包加载失败")
        }
        candidate.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
        api = candidate
        language = languages
    }

    fun recognize(bitmap: Bitmap, source: String, area: Rect): List<RecognizedBlock> {
        selectLanguage(when (source) {
            "日语" -> "jpn"
            "英语" -> "eng"
            "俄语" -> "rus"
            "自动检测" -> "eng+jpn+rus"
            else -> error("当前 OCR 支持日语、英语和俄语")
        })
        val engine = checkNotNull(api)
        // Crop before thresholding. Black system-bar masks can skew Tesseract's page segmentation.
        val cropped = Bitmap.createBitmap(bitmap, area.left, area.top, area.width(), area.height())
        try {
            engine.setImage(cropped)
            engine.utF8Text // Runs recognition before acquiring the iterator.
            val iterator = engine.resultIterator ?: return emptyList()
            try {
                val blocks = mutableListOf<RecognizedBlock>()
                val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                iterator.begin()
                do {
                    val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                    val box = iterator.getBoundingRect(level)
                    if (text.isNotEmpty() && iterator.confidence(level) >= 30 && box != null && !box.isEmpty) {
                        blocks += RecognizedBlock(text, PixelBounds(box.left + area.left, box.top + area.top,
                            box.right + area.left, box.bottom + area.top))
                    }
                } while (iterator.next(level))
                return blocks
            } finally { iterator.delete() }
        } finally { engine.clear(); if (cropped !== bitmap) cropped.recycle() }
    }
}
