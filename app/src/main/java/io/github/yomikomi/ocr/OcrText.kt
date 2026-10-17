package io.github.yomikomi.ocr

import io.github.yomikomi.translation.PixelBounds
import io.github.yomikomi.translation.RecognizedBlock

/** Native OCR observations in screen coordinates; filtering never owns image/native resources. */
internal data class OcrText(val text: String, val bounds: PixelBounds, val confidence: Float)

private val punctuation = Regex("[-—>→!?！？….,:;()]+")
private val timestamp = Regex("\\b\\d{1,2}:\\d{2}\\b")

/** Use the same absolute floor as detection, not a page-wide median that penalizes small UI labels. */
internal fun selectOcrText(lines: List<OcrText>, words: Map<PixelBounds, List<OcrText>>,
    source: String, minimumHeight: Int): List<RecognizedBlock> = lines.filter { line ->
    line.confidence >= 50 && line.bounds.width > 0 && line.bounds.height >= minimumHeight &&
        line.text.any { it.isLetterOrDigit() || it in "!?！？…" } && '\uFFFD' !in line.text
}.flatMap { line ->
    if (source != "俄语" || (timestamp.containsMatchIn(line.text) && line.text.split(' ').size <= 4))
        return@flatMap listOf(RecognizedBlock(line.text, line.bounds))
    val kept = words[line.bounds].orEmpty().filter { word ->
        val cyrillic = word.text.count { it in '\u0400'..'\u04ff' }
        word.text.isNotBlank() && (cyrillic >= 2 || (cyrillic == 1 && word.confidence >= 30) ||
            punctuation.matches(word.text) || (word.confidence >= 30 && word.bounds.height >= line.bounds.height *
                (if (word.text.any(Char::isDigit) && word.text.none(Char::isLetter)) .75f else .4f)))
    }
    val cleaned = kept.joinToString(" ") { it.text }
    if (kept.isEmpty() || !(cleaned.any(Char::isLetterOrDigit) || Regex("[!?！？…]+").matches(cleaned))) emptyList()
    else {
        fun block(parts: List<OcrText>) = RecognizedBlock(parts.joinToString(" ") { it.text }, PixelBounds(
            parts.minOf { it.bounds.left }, parts.minOf { it.bounds.top }, parts.maxOf { it.bounds.right }, parts.maxOf { it.bounds.bottom }))
        val handle = kept.indexOfFirst { it.text.startsWith('@') }
        val prose = if (handle >= 0) (handle + 1 until kept.size).firstOrNull { index ->
            kept[index].text.count { it in '\u0400'..'\u04ff' } >= 3
        } ?: -1 else -1
        if (prose > handle && handle >= 0) buildList {
            if (handle > 0) add(block(kept.take(handle)))
            add(block(kept.subList(handle, prose)))
            add(block(kept.drop(prose)))
        } else listOf(block(kept))
    }
}
