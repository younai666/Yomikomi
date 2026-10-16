package io.github.yomikomi.capture

import android.graphics.Bitmap
import android.graphics.Rect
import io.github.yomikomi.translation.PixelBounds
import io.github.yomikomi.translation.RecognizedBlock
import io.github.yomikomi.translation.ScreenAppearance

internal fun screenAppearance(bitmap: Bitmap, area: Rect, blocks: List<RecognizedBlock>): ScreenAppearance =
    ScreenAppearance.sample(PixelBounds(area.left, area.top, area.right, area.bottom)) { x, y ->
        if (blocks.any { x in (it.bounds.left - 2)..(it.bounds.right + 2) && y in (it.bounds.top - 2)..(it.bounds.bottom + 2) }) null
        else bitmap.getPixel(x, y)
    }
