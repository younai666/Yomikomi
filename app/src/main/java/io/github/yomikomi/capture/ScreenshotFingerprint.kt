package io.github.yomikomi.capture

import io.github.yomikomi.translation.PixelBounds
import kotlin.math.abs

/** Tiny in-memory RGB grid, not an image or OCR result; excludes system bars through the viewport. */
internal class ScreenshotFingerprint private constructor(val viewport: PixelBounds, private val rgb: IntArray) {
    data class Change(val changedCells: Int, val changedRows: Int, val totalCells: Int, val geometryChanged: Boolean) {
        // Tolerate tiny cursors/antialiasing; several changed text rows or broad movement invalidates.
        val changed get() = geometryChanged || changedCells * 1000 >= totalCells * 15 ||
            (changedRows >= 3 && changedCells * 1000 >= totalCells * 3)
    }

    fun compare(other: ScreenshotFingerprint): Change {
        if (viewport != other.viewport) return Change(0, 0, rgb.size, true)
        var cells = 0
        var rows = 0
        for (row in 0 until ROWS) {
            var rowCells = 0
            for (column in 0 until COLUMNS) {
                val a = rgb[row * COLUMNS + column]
                val b = other.rgb[row * COLUMNS + column]
                val difference = (abs((a shr 16 and 255) - (b shr 16 and 255)) +
                    abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))) / 3
                if (difference > 24) rowCells++
            }
            cells += rowCells
            if (rowCells >= 2) rows++
        }
        return Change(cells, rows, rgb.size, false)
    }

    companion object {
        private const val COLUMNS = 64
        private const val ROWS = 128
        fun sample(viewport: PixelBounds, pixel: (Int, Int) -> Int): ScreenshotFingerprint {
            val rgb = IntArray(COLUMNS * ROWS)
            for (row in 0 until ROWS) for (column in 0 until COLUMNS) {
                var red = 0; var green = 0; var blue = 0
                for (dy in 1..3) for (dx in 1..3) {
                    val x = viewport.left + viewport.width * (column * 4 + dx) / (COLUMNS * 4)
                    val y = viewport.top + viewport.height * (row * 4 + dy) / (ROWS * 4)
                    val color = pixel(x, y)
                    red += color shr 16 and 255; green += color shr 8 and 255; blue += color and 255
                }
                rgb[row * COLUMNS + column] = (red / 9 shl 16) or (green / 9 shl 8) or (blue / 9)
            }
            return ScreenshotFingerprint(viewport, rgb)
        }
    }
}
