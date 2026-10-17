package io.github.yomikomi.ocr

import io.github.yomikomi.translation.PixelBounds

/** Match the existing readable-line floor before LSTM recognition, independent of candidate density. */
internal fun recognitionRegions(candidates: List<PixelBounds>, width: Int, height: Int): List<PixelBounds> {
    val minimumHeight = maxOf(10, width / 65)
    val minimumArea = minimumHeight * minimumHeight / 4
    return candidates.mapNotNull { box ->
        val left = box.left.coerceAtLeast(0)
        val top = box.top.coerceAtLeast(0)
        val right = box.right.coerceAtMost(width)
        val bottom = box.bottom.coerceAtMost(height)
        val w = right - left
        val h = bottom - top
        if (h < minimumHeight || w < 2 || w.toLong() * h < minimumArea) null
        else {
            // Layout boxes precede recognition and can omit edge punctuation. Keep horizontal context,
            // but only a small vertical margin so neighboring tiny code lines stay outside the ROI.
            val yPad = maxOf(2, h / 8)
            PixelBounds((left - h).coerceAtLeast(0), (top - yPad).coerceAtLeast(0),
                (right + h).coerceAtMost(width), (bottom + yPad).coerceAtMost(height))
        }
    }
}
