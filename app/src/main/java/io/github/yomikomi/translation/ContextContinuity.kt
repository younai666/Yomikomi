package io.github.yomikomi.translation

import java.util.Locale
import kotlin.math.abs

data class ContextConfig(val source: String, val target: String, val prompt: PromptSettings, val backend: String = "local") {
    fun matches(other: ContextConfig): Boolean = source == other.source && target == other.target && backend == other.backend &&
        prompt.mode == other.prompt.mode && (prompt.mode != PromptMode.Custom || prompt.customTemplate == other.prompt.customTemplate)
}

/** Only a tiny in-memory colour grid is retained, never a screenshot. Null samples mask OCR glyphs. */
class ScreenAppearance private constructor(val viewport: PixelBounds, internal val cells: IntArray) {
    companion object {
        internal const val COLUMNS = 12
        internal const val ROWS = 16
        fun sample(viewport: PixelBounds, pixel: (Int, Int) -> Int?): ScreenAppearance {
            val cells = IntArray(COLUMNS * ROWS) { -1 }
            // Ignore top/bottom chrome. Average nine points per cell to tolerate tiny rendering changes.
            for (row in 2 until ROWS - 2) for (column in 0 until COLUMNS) {
                var red = 0; var green = 0; var blue = 0; var count = 0
                for (dy in 1..3) for (dx in 1..3) {
                    val x = viewport.left + viewport.width * (column * 4 + dx) / (COLUMNS * 4)
                    val y = viewport.top + viewport.height * (row * 4 + dy) / (ROWS * 4)
                    val color = pixel(x, y) ?: continue
                    red += color shr 16 and 255; green += color shr 8 and 255; blue += color and 255; count++
                }
                if (count > 0) cells[row * COLUMNS + column] = (red / count shl 16) or (green / count shl 8) or (blue / count)
            }
            return ScreenAppearance(viewport, cells)
        }
    }
}

/** Deterministic evidence of continuity, not a semantic classifier. */
internal object ContextContinuity {
    private val words = Regex("[\\p{L}\\p{N}]+")
    private val japanese = Regex("[\\u3400-\\u9fff\\u3040-\\u30ff]")

    fun related(previous: TranslatedScreen, current: List<RecognizedBlock>, appearance: ScreenAppearance?): Boolean {
        val before = features(previous.blocks.map { RecognizedBlock(it.text, it.bounds) }, previous.appearance)
        val after = features(current, appearance)
        val shared = before.intersect(after).sumOf { it.length.coerceAtMost(12) }
        val total = (before.sumOf { it.length.coerceAtMost(12) } + after.sumOf { it.length.coerceAtMost(12) })
        if (shared >= 4 && total > 0 && shared * 2.0 / total >= 0.35) return true
        return previous.appearance != null && appearance != null && samePicture(previous.appearance, appearance)
    }

    private fun features(blocks: List<RecognizedBlock>, appearance: ScreenAppearance?): Set<String> {
        val viewport = appearance?.viewport
        return blocks.asSequence().filter { block ->
            viewport == null || (block.bounds.top + block.bounds.bottom) / 2 in
                (viewport.top + viewport.height / 8)..(viewport.bottom - viewport.height / 8)
        }.flatMap { block ->
            words.findAll(block.text.lowercase(Locale.ROOT)).flatMap { match ->
                val token = match.value
                // Japanese has no word spaces; adjacent character pairs preserve names and terms.
                if (japanese.containsMatchIn(token)) token.windowed(2).asSequence()
                else sequenceOf(token).filter { it.length >= 2 }
            }
        }.toSet()
    }

    private fun difference(a: Int, b: Int): Int =
        (abs((a shr 16 and 255) - (b shr 16 and 255)) + abs((a shr 8 and 255) - (b shr 8 and 255)) +
            abs((a and 255) - (b and 255))) / 3

    private fun informative(grid: IntArray): Boolean {
        var edges = 0; var detailed = 0
        for (row in 2 until ScreenAppearance.ROWS - 2) for (column in 0 until ScreenAppearance.COLUMNS - 1) {
            val a = grid[row * ScreenAppearance.COLUMNS + column]
            val b = grid[row * ScreenAppearance.COLUMNS + column + 1]
            if (a < 0 || b < 0) continue
            edges++
            if (difference(a, b) >= 24) detailed++
        }
        return edges >= 60 && detailed * 4 >= edges
    }

    private fun samePicture(a: ScreenAppearance, b: ScreenAppearance): Boolean {
        val aspectA = a.viewport.width.toDouble() / a.viewport.height
        val aspectB = b.viewport.width.toDouble() / b.viewport.height
        if (abs(aspectA / aspectB - 1) > 0.03 || !informative(a.cells) || !informative(b.cells)) return false
        var comparable = 0; var similar = 0
        a.cells.indices.forEach { index ->
            if (a.cells[index] >= 0 && b.cells[index] >= 0) {
                comparable++
                if (difference(a.cells[index], b.cells[index]) <= 18) similar++
            }
        }
        return comparable >= 108 && similar.toDouble() / comparable >= 0.90
    }
}
