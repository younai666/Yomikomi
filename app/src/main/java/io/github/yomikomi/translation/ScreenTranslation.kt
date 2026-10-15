package io.github.yomikomi.translation

import kotlin.math.abs

/** Coordinates are physical pixels in the captured display, including system bar insets. */
data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

data class RecognizedBlock(val text: String, val bounds: PixelBounds)
data class ScreenBlock(val id: String, val text: String, val bounds: PixelBounds)
data class TranslationSegment(val id: String, val text: String)
data class PreviousScreen(val segments: List<TranslationSegment>, val translations: List<TranslationSegment>)

/** One request per screen. Engines must preserve IDs, rather than rely on response order. */
data class TranslationRequest(
    val sourceLanguage: String,
    val targetLanguage: String,
    val segments: List<TranslationSegment>,
    val previousScreen: PreviousScreen?,
    val prompt: PromptSettings = PromptSettings(),
)

fun interface TranslationEngine {
    /** Runs on the pipeline worker. A Hy-MT2 adapter can serialize this entire request in one prompt. */
    fun translate(request: TranslationRequest): List<TranslationSegment>
    fun cancel() {}
}

data class TranslatedScreen(
    val blocks: List<ScreenBlock>,
    val translations: Map<String, String>,
    val sourceLanguage: String,
    val targetLanguage: String,
)

/** Session-local IDs survive OCR enumeration changes and small coordinate jitter. */
class ScreenTranslator(private val engine: TranslationEngine) {
    private var nextId = 1L
    private var last: TranslatedScreen? = null
    private var lastTime = 0L
    private var lastScope: String? = null

    @Synchronized
    fun translate(
        recognized: List<RecognizedBlock>, source: String, target: String, scope: String, now: Long,
        prompt: PromptSettings = PromptSettings(),
    ): TranslatedScreen {
        val previous = last?.takeIf {
            now - lastTime in 0..CONTEXT_TTL_MS && lastScope == scope &&
                it.sourceLanguage == source && it.targetLanguage == target
        }
        val unmatched = previous?.blocks?.toMutableList() ?: mutableListOf()
        val blocks = readingOrder(recognized.filter { it.text.isNotBlank() && it.bounds.width > 0 && it.bounds.height > 0 })
            .map { block ->
                val match = unmatched.filter { it.text.trim() == block.text.trim() }
                    .minByOrNull { distance(it.bounds, block.bounds) }
                    ?.takeIf { distance(it.bounds, block.bounds) <= 48 }
                if (match != null) unmatched.remove(match)
                ScreenBlock(match?.id ?: "b${nextId++}", block.text, block.bounds)
            }
        val context = previous?.let { page ->
            var remaining = CONTEXT_CHARS
            val kept = page.blocks.mapNotNull { block ->
                val translated = page.translations.getValue(block.id)
                val size = block.text.length + translated.length
                if (size > remaining) null else {
                    remaining -= size
                    block
                }
            }
            PreviousScreen(kept.map { TranslationSegment(it.id, it.text) },
                kept.map { TranslationSegment(it.id, page.translations.getValue(it.id)) })
        }
        val request = TranslationRequest(source, target, blocks.map { TranslationSegment(it.id, it.text) }, context, prompt)
        if (blocks.isEmpty()) return TranslatedScreen(blocks, emptyMap(), source, target)
        val result = engine.translate(request)
        require(result.size == blocks.size && result.map { it.id }.toSet() == blocks.map { it.id }.toSet()) {
            "Translation response must contain each requested ID exactly once"
        }
        require(result.all { it.text.isNotBlank() }) { "Translation response contains empty text" }
        return TranslatedScreen(blocks, result.associate { it.id to it.text }, source, target)
    }

    /** Only displayed screens become context; canceled/failed requests never replace it. */
    @Synchronized
    fun accept(screen: TranslatedScreen, scope: String, now: Long) {
        last = screen
        lastTime = now
        lastScope = scope
    }

    @Synchronized
    fun clear() { last = null; lastScope = null }

    @Synchronized
    fun expire(now: Long) { if (now - lastTime >= CONTEXT_TTL_MS) clear() }

    companion object {
        const val CONTEXT_TTL_MS = 30_000L
        const val CONTEXT_CHARS = 2_400
        private fun distance(a: PixelBounds, b: PixelBounds) =
            abs(a.left - b.left) + abs(a.top - b.top) + abs(a.right - b.right) + abs(a.bottom - b.bottom)
    }
}

/** Recursive whitespace cuts: full-width headings first, then columns, then top-to-bottom rows. */
internal fun readingOrder(blocks: List<RecognizedBlock>): List<RecognizedBlock> {
    if (blocks.size < 2) return blocks
    fun gap(vertical: Boolean): Int? {
        val sorted = blocks.sortedBy { if (vertical) it.bounds.left else it.bounds.top }
        var end = if (vertical) sorted.first().bounds.right else sorted.first().bounds.bottom
        var largest = 0
        var cut: Int? = null
        val threshold = if (vertical) blocks.map { it.bounds.height }.sorted()[blocks.size / 2] else 0
        for (block in sorted.drop(1)) {
            val start = if (vertical) block.bounds.left else block.bounds.top
            if (start - end > largest && start - end > threshold) {
                largest = start - end
                cut = end + (start - end) / 2
            }
            end = maxOf(end, if (vertical) block.bounds.right else block.bounds.bottom)
        }
        return cut
    }
    val columnCut = gap(true)
    if (columnCut != null) {
        val (left, right) = blocks.partition { it.bounds.left < columnCut }
        return readingOrder(left) + readingOrder(right)
    }
    val rowCut = gap(false)
    if (rowCut != null) {
        val (top, bottom) = blocks.partition { it.bounds.top < rowCut }
        return readingOrder(top) + readingOrder(bottom)
    }
    return blocks.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }, { it.text }))
}
