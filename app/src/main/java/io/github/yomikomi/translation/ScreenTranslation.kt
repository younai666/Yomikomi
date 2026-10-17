package io.github.yomikomi.translation

import kotlin.math.abs
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog
import java.util.Locale

enum class ContextReset { MANUAL, APP_SWITCH, FOREGROUND, EMPTY, UNKNOWN_PACKAGE, UNRELATED, EXPIRED, CONFIG_CHANGED, SERVICE_STOP }

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
    val traceId: Long = 0,
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
    val prompt: PromptSettings = PromptSettings(),
    val backend: String = "local",
    val packageName: String = "",
    val appearance: ScreenAppearance? = null,
    val contextUsed: Boolean = false,
    internal val contextVersion: Long = 0,
)

/** Session-local IDs survive OCR enumeration changes and small coordinate jitter. */
class ScreenTranslator(private val engine: TranslationEngine) {
    private var nextId = 1L
    private var last: TranslatedScreen? = null
    private var lastTime = 0L
    private var lastScope: String? = null
    private var configured: ContextConfig? = null
    private var version = 0L

    fun translate(
        recognized: List<RecognizedBlock>, source: String, target: String, scope: String, now: Long,
        prompt: PromptSettings = PromptSettings(),
        appearance: ScreenAppearance? = null, backend: String = "local", traceId: Long = 0,
    ): TranslatedScreen {
        val (draft, context) = synchronized(this) {
            configure(ContextConfig(source, target, prompt, backend))
            val valid = readingOrder(recognized.filter { it.text.isNotBlank() && it.bounds.width > 0 && it.bounds.height > 0 })
            val previous = last?.takeIf {
                valid.isNotEmpty() && scope.isNotBlank() && now - lastTime in 0 until CONTEXT_TTL_MS && lastScope == scope &&
                    ContextContinuity.related(it, valid, appearance)
            }
            // Actually erase rejected context: A → unrelated B → A must not resurrect A, even if B fails.
            if (last != null && previous == null) clear(when {
                valid.isEmpty() -> ContextReset.EMPTY
                scope.isBlank() -> ContextReset.UNKNOWN_PACKAGE
                now - lastTime !in 0 until CONTEXT_TTL_MS -> ContextReset.EXPIRED
                lastScope != scope -> ContextReset.APP_SWITCH
                else -> ContextReset.UNRELATED
            })
            val requestVersion = ++version
            val unmatched = previous?.blocks?.toMutableList() ?: mutableListOf()
            val blocks = valid.map { block ->
                val match = unmatched.asSequence().filter { it.text.trim() == block.text.trim() }
                    .minByOrNull { distance(it.bounds, block.bounds) }
                    ?.takeIf { distance(it.bounds, block.bounds) <= 48 }
                if (match != null) unmatched.remove(match)
                ScreenBlock(match?.id ?: "b${nextId++}", block.text, block.bounds)
            }
            val context = previous?.let { page ->
                var remaining = CONTEXT_CHARS
                // The next page follows the last dialogue, so retain a contiguous suffix in reading order.
                val kept = page.blocks.takeLastWhile { block ->
                    val translated = page.translations.getValue(block.id)
                    val size = block.text.length + translated.length
                    if (size > remaining) false else {
                        remaining -= size
                        true
                    }
                }
                if (kept.isEmpty()) null else PreviousScreen(kept.map { TranslationSegment(it.id, it.text) },
                    kept.map { TranslationSegment(it.id, page.translations.getValue(it.id)) })
            }
            TranslatedScreen(blocks, emptyMap(), source, target, prompt, backend, scope, appearance, context != null, requestVersion) to context
        }
        RunLog.info(LogModule.CONTEXT, "decision", "reused" to if (context != null) 1 else 0,
            "blocks" to draft.blocks.size, "version" to draft.contextVersion)
        if (draft.blocks.isEmpty()) return draft
        val request = TranslationRequest(source, target, draft.blocks.map { TranslationSegment(it.id, it.text) }, context, prompt, traceId)
        // Slow model/network work must not hold the cache lock: app/config invalidations are immediate.
        val result = engine.translate(request)
        val translations = result.associate { it.id to it.text }
        require(result.size == draft.blocks.size && translations.keys == draft.blocks.map { it.id }.toSet()) {
            "Translation response must contain each requested ID exactly once"
        }
        result.forEach { TranslationProtocol.validateText(it.text) }
        return draft.copy(translations = translations)
    }

    /** Only displayed screens become context; canceled/failed requests never replace it. */
    @Synchronized
    fun accept(screen: TranslatedScreen, scope: String, now: Long): Boolean {
        if (screen.contextVersion != version || screen.packageName != scope || scope.isBlank() || screen.blocks.isEmpty()) {
            RunLog.info(LogModule.CONTEXT, "accept_rejected")
            return false
        }
        version++ // Consume the result once; expired/already accepted screens cannot be accepted again.
        last = screen
        lastTime = now
        lastScope = scope
        RunLog.info(LogModule.CONTEXT, "accepted", "blocks" to screen.blocks.size)
        return true
    }

    @Synchronized
    fun configure(config: ContextConfig) {
        if (configured?.matches(config) == false) clear(ContextReset.CONFIG_CHANGED)
        configured = config
    }

    @Synchronized
    fun clear(reason: ContextReset = ContextReset.MANUAL) {
        last = null; lastScope = null; version++
        RunLog.info(LogModule.CONTEXT, "cleared.${reason.name.lowercase(Locale.ROOT)}")
    }

    @Synchronized
    fun expire(now: Long) {
        // Expiring the older cached page must not invalidate a new translation already in progress.
        if (now < lastTime || now - lastTime >= CONTEXT_TTL_MS) {
            if (last != null) RunLog.info(LogModule.CONTEXT, "expired")
            last = null; lastScope = null
        }
    }

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
