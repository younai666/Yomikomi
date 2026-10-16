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
import io.github.yomikomi.translation.TranslatedScreen
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog

data class OverlayBlock(val bounds: PixelBounds, val text: String, val background: Int)

/** Sample only the perimeter, avoiding the original glyphs when choosing a covering color. */
fun overlayBlocks(screen: TranslatedScreen, bitmap: Bitmap): List<OverlayBlock> = screen.blocks.map { block ->
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
    OverlayBlock(b, screen.translations.getValue(block.id), Color.rgb(median(Color::red), median(Color::green), median(Color::blue)))
}

@RequiresApi(30)
class TranslationOverlay(private val context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    val visible get() = view != null

    @SuppressLint("RtlHardcoded") // Coordinates are physical display pixels, independent of locale.
    fun show(blocks: List<OverlayBlock>, settings: RuntimeSettings) = RunLog.measure(LogModule.OVERLAY, "show", "blocks" to blocks.size) {
        hide()
        val layer = OverlayView(context, blocks, settings)
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
        view?.let {
            RunLog.measure(LogModule.OVERLAY, "hide") { manager.removeViewImmediate(it) }
        }
        view = null
    }

    private class OverlayView(context: Context, blocks: List<OverlayBlock>, settings: RuntimeSettings) : View(context) {
        private val background = Paint()
        private val location = IntArray(2)
        private data class Label(val block: OverlayBlock, val layout: StaticLayout)
        private val labels = blocks.map { block ->
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                val color = block.background
                this.color = if ((Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 < 140)
                    Color.rgb(240, 238, 233) else Color.rgb(28, 28, 27)
                textSize = minOf(settings.textSize * resources.displayMetrics.scaledDensity, block.bounds.height * 0.9f)
            }
            fun layout() = StaticLayout.Builder.obtain(block.text, 0, block.text.length, paint, block.bounds.width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(0f, 1f).build()
            var result = layout()
            while (result.height > block.bounds.height && paint.textSize > 2f) {
                paint.textSize = maxOf(2f, paint.textSize - 1f)
                result = layout()
            }
            Label(block, result)
        }
        private val opacity = (settings.opacity * 255).toInt()
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(location)
            canvas.translate(-location[0].toFloat(), -location[1].toFloat())
            labels.forEach { (block, layout) ->
                val b = block.bounds
                background.color = block.background
                background.alpha = opacity
                canvas.drawRect(b.left - 2f, b.top - 2f, b.right + 2f, b.bottom + 2f, background)
                canvas.save()
                canvas.clipRect(b.left, b.top, b.right, b.bottom)
                canvas.translate(b.left.toFloat(), b.top.toFloat())
                layout.draw(canvas)
                canvas.restore()
            }
        }
    }
}
