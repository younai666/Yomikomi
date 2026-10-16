package io.github.yomikomi.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import android.annotation.SuppressLint
import io.github.yomikomi.RuntimeSettings
import io.github.yomikomi.translation.PixelBounds
import io.github.yomikomi.translation.RecognizedBlock
import io.github.yomikomi.translation.TranslatedScreen
import io.github.yomikomi.translation.ScreenBlock
import io.github.yomikomi.translation.TranslationProtocol
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog

data class OverlayBlock(val bounds: PixelBounds, val background: Int, val layout: StaticLayout,
    val sourceBounds: PixelBounds = bounds, val ids: List<String> = emptyList(), val sourceMasks: List<PixelBounds> = listOf(sourceBounds))

/** Sample only the perimeter, avoiding the original glyphs when choosing a covering color. */
fun overlayBackgrounds(bitmap: Bitmap, blocks: List<RecognizedBlock>): Map<PixelBounds, Int> = blocks.associate { block ->
    val b = block.bounds
    val samples = mutableListOf<Int>()
    for (step in 0..8) {
        val x = (b.left + b.width * step / 8).coerceIn(0, bitmap.width - 1)
        val y = (b.top + b.height * step / 8).coerceIn(0, bitmap.height - 1)
        samples += bitmap.getPixel(x, (b.top - 3).coerceIn(0, bitmap.height - 1))
        samples += bitmap.getPixel(x, (b.bottom + 3).coerceIn(0, bitmap.height - 1))
        samples += bitmap.getPixel((b.left - 3).coerceIn(0, bitmap.width - 1), y)
        samples += bitmap.getPixel((b.right + 3).coerceIn(0, bitmap.width - 1), y)
    }
    fun median(channel: (Int) -> Int) = samples.map(channel).sorted()[samples.size / 2]
    b to Color.rgb(median(Color::red), median(Color::green), median(Color::blue))
}

/** Prepare immutable text layouts on the pipeline worker; the main thread only attaches/draws the view. */
fun overlayBlocks(screen: TranslatedScreen, backgrounds: Map<PixelBounds, Int>, textSize: Float,
    viewport: PixelBounds): List<OverlayBlock> {
    require(screen.blocks.map { it.id }.distinct().size == screen.blocks.size &&
        screen.translations.keys == screen.blocks.map { it.id }.toSet()) { "Overlay ID 映射不完整" }
    screen.translations.values.forEach(TranslationProtocol::validateText)
    // Adjacent lines of the same size/column form a paragraph, while their ID mapping remains intact.
    val groups = mutableListOf<MutableList<ScreenBlock>>()
    fun metadata(text: String) = (Regex("\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(text) && text.split(' ').size <= 4) ||
        (text.startsWith('@') && text.substringAfter(' ', "").none { it in '\u0400'..'\u04ff' })
    for (block in screen.blocks.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))) {
        val b = block.bounds
        if (metadata(block.text)) { groups.add(mutableListOf(block)); continue }
        val paragraph = groups.lastOrNull { it.none { line -> metadata(line.text) } }
        val prior = paragraph?.lastOrNull()?.bounds
        if (prior != null && b.top >= prior.bottom && b.top - prior.bottom <= maxOf(prior.height, b.height) * .9f &&
            minOf(prior.right, b.right) - maxOf(prior.left, b.left) >= minOf(prior.width, b.width) * .6f &&
            b.height.toFloat() / prior.height in .65f..1.5f) {
            paragraph!!.add(block)
        } else groups.add(mutableListOf(block))
    }
    fun bounds(group: List<ScreenBlock>) = PixelBounds(group.minOf { it.bounds.left }, group.minOf { it.bounds.top },
        group.maxOf { it.bounds.right }, group.maxOf { it.bounds.bottom })
    return groups.mapNotNull { group ->
        if (group.all { metadata(it.text) }) return@mapNotNull null
        if (screen.sourceLanguage == "俄语" && group.all { '#' in it.text && it.text.count { c -> c in '\u0400'..'\u04ff' } < 3 })
            return@mapNotNull null // A channel/input identifier is already readable; do not paint OCR guesses over it.
        if (group.all { screen.translations.getValue(it.id).trim() == it.text.trim() }) return@mapNotNull null
        val source = bounds(group)
        val others = groups.filter { it !== group }.map(::bounds)
        val right = minOf(viewport.right, others.filter { it.top < source.bottom && it.bottom > source.top && it.left >= source.right }
            .minOfOrNull { it.left - 6 } ?: viewport.right)
        val bottom = minOf(viewport.bottom, others.filter { it.left < right && it.right > source.left && it.top >= source.bottom }
            .minOfOrNull { it.top - 4 } ?: viewport.bottom)
        val width = maxOf(source.width, right - source.left)
        val height = maxOf(source.height, bottom - source.top)
        val color = backgrounds.getValue(group.first().bounds)
        val text = group.joinToString(" ") { screen.translations.getValue(it.id) }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if ((Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 < 140)
                Color.rgb(240, 238, 233) else Color.rgb(28, 28, 27)
            this.textSize = textSize
        }
        fun layout() = StaticLayout.Builder.obtain(text, 0, text.length, paint,
            minOf(width, maxOf(source.width, kotlin.math.ceil(Layout.getDesiredWidth(text, paint).toDouble()).toInt())))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(0f, 1f)
            .setIndents(intArrayOf(group.first().bounds.left - source.left, 0), null).build()
        var result: StaticLayout? = null
        for (scale in listOf(1f, .95f, .9f)) {
            paint.textSize = textSize * scale
            val candidate = layout()
            if (candidate.height <= height) { result = candidate; break }
        }
        if (result == null) {
            RunLog.warn(LogModule.OVERLAY, "layout_unfit", null, "ids" to group.size)
            null // Keep the original readable text rather than shrinking to illegible pixels or clipping a translation.
        } else OverlayBlock(PixelBounds(source.left, source.top, source.left + result.width,
            source.top + maxOf(source.height, result.height)), color, result, source, group.map { it.id }, group.map { it.bounds })
    }.also { rendered ->
        RunLog.info(LogModule.OVERLAY, "layout", "source_blocks" to screen.blocks.size, "paragraphs" to rendered.size,
            "rendered_ids" to rendered.sumOf { it.ids.size }, "base_px" to textSize,
            "min_px" to (rendered.minOfOrNull { it.layout.paint.textSize } ?: textSize),
            "max_px" to (rendered.maxOfOrNull { it.layout.paint.textSize } ?: textSize))
    }
}

@RequiresApi(30)
class TranslationOverlay(private val context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    val visible get() = view != null

    @SuppressLint("RtlHardcoded") // Coordinates are physical display pixels, independent of locale.
    fun show(blocks: List<OverlayBlock>, settings: RuntimeSettings) = RunLog.measure(LogModule.OVERLAY, "show", "blocks" to blocks.size) {
        hide()
        val layer = OverlayView(context, blocks, (settings.opacity * 255).toInt())
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "Yomikomi translation"
        }
        manager.addView(layer, params)
        view = layer
    }

    fun hide() {
        val attached = view ?: return
        view = null
        try { RunLog.measure(LogModule.OVERLAY, "hide") { manager.removeViewImmediate(attached) } }
        catch (error: RuntimeException) {
            // Android may already have removed the accessibility window during unbind.
            RunLog.warn(LogModule.OVERLAY, "hide_failed", error)
        }
    }

    private class OverlayView(context: Context, private val blocks: List<OverlayBlock>, private val opacity: Int) : View(context) {
        private val background = Paint()
        private val location = IntArray(2)
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(location)
            canvas.translate(-location[0].toFloat(), -location[1].toFloat())
            blocks.forEach { block ->
                val b = block.bounds
                background.color = block.background
                background.alpha = opacity
                block.sourceMasks.forEach { mask -> canvas.drawRect(mask.left - 2f, mask.top - 2f, mask.right + 2f, mask.bottom + 2f, background) }
                for (line in 0 until block.layout.lineCount) canvas.drawRect(
                    b.left + maxOf(block.layout.getLineLeft(line), if (line == 0) (block.sourceMasks.first().left - b.left).toFloat() else 0f) - 2f,
                    b.top + block.layout.getLineTop(line) - 1f,
                    b.left + block.layout.getLineRight(line) + 2f, b.top + block.layout.getLineBottom(line) + 1f, background)
                canvas.save()
                canvas.clipRect(b.left, b.top, b.right, b.bottom)
                canvas.translate(b.left.toFloat(), b.top.toFloat())
                block.layout.draw(canvas)
                canvas.restore()
            }
        }
    }
}
