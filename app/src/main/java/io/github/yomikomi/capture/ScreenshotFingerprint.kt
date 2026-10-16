package io.github.yomikomi.capture

import io.github.yomikomi.translation.PixelBounds
import kotlin.math.abs

/** Tiny in-memory RGB grid, not an image or OCR result; excludes system bars through the viewport. */
internal class ScreenshotFingerprint private constructor(
    val viewport: PixelBounds, val regions: List<PixelBounds>, private val rgb: IntArray, private val sampled: BooleanArray,
) {
    data class Change(val changedCells: Int, val changedRows: Int, val totalCells: Int, val geometryChanged: Boolean) {
        // Reject substantial movement/replacement in captured text, not a caret or small refresh.
        val changed get() = geometryChanged || (totalCells > 0 && changedCells >= 8 &&
            ((changedRows >= 2 && changedCells * 100 >= totalCells * 18) || changedCells * 100 >= totalCells * 55))
    }

    fun compare(other: ScreenshotFingerprint, ignored: List<PixelBounds> = emptyList()): Change {
        if (viewport != other.viewport || regions != other.regions) return Change(0, 0, sampled.count { it }, true)
        var cells = 0
        var rows = 0
        var compared = 0
        for (row in 0 until ROWS) {
            var rowCells = 0
            for (column in 0 until COLUMNS) {
                if (!sampled[row * COLUMNS + column]) continue
                val x = viewport.left + viewport.width * (column * 4 + 2) / (COLUMNS * 4)
                val y = viewport.top + viewport.height * (row * 4 + 2) / (ROWS * 4)
                if (ignored.any { x >= it.left - 12 && x < it.right + 12 && y >= it.top - 12 && y < it.bottom + 12 }) continue
                compared++
                val a = rgb[row * COLUMNS + column]
                val b = other.rgb[row * COLUMNS + column]
                val difference = (abs((a shr 16 and 255) - (b shr 16 and 255)) +
                    abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))) / 3
                if (difference > 24) rowCells++
            }
            cells += rowCells
            if (rowCells >= 2) rows++
        }
        return Change(cells, rows, compared, false)
    }

    companion object {
        private const val COLUMNS = 64
        private const val ROWS = 128
        fun sample(viewport: PixelBounds, regions: List<PixelBounds> = emptyList(), pixel: (Int, Int) -> Int): ScreenshotFingerprint {
            val rgb = IntArray(COLUMNS * ROWS)
            val sampled = BooleanArray(rgb.size)
            for (row in 0 until ROWS) for (column in 0 until COLUMNS) {
                val centerX = viewport.left + viewport.width * (column * 4 + 2) / (COLUMNS * 4)
                val centerY = viewport.top + viewport.height * (row * 4 + 2) / (ROWS * 4)
                if (regions.isNotEmpty() && regions.none {
                    centerX >= it.left - 8 && centerX < it.right + 8 && centerY >= it.top - 8 && centerY < it.bottom + 8
                }) continue
                sampled[row * COLUMNS + column] = true
                var red = 0; var green = 0; var blue = 0
                for (dy in 1..3) for (dx in 1..3) {
                    val x = viewport.left + viewport.width * (column * 4 + dx) / (COLUMNS * 4)
                    val y = viewport.top + viewport.height * (row * 4 + dy) / (ROWS * 4)
                    val color = pixel(x, y)
                    red += color shr 16 and 255; green += color shr 8 and 255; blue += color and 255
                }
                rgb[row * COLUMNS + column] = (red / 9 shl 16) or (green / 9 shl 8) or (blue / 9)
            }
            return ScreenshotFingerprint(viewport, regions, rgb, sampled)
        }
    }
}
