package io.github.yomikomi.translation

import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog

/** Serialize ownership of the native model/context; cancellation only touches an atomic flag. */
object NativeLlama {
    init { System.loadLibrary("yomikomi") }
    private var loadedPath: String? = null

    @Synchronized
    fun load(path: String) {
        if (loadedPath == path) return
        RunLog.measure(LogModule.INFERENCE, "load") { nativeLoad(path) }
        loadedPath = path
    }

    @Synchronized
    fun generate(prompt: String, grammar: String): String {
        check(loadedPath != null) { "请先下载并加载翻译模型" }
        return RunLog.measure(LogModule.INFERENCE, "generate") {
            nativeGenerate(prompt.toByteArray(Charsets.UTF_8), grammar, 4096).toString(Charsets.UTF_8)
        }
    }

    fun cancel() { RunLog.info(LogModule.INFERENCE, "cancel_requested"); nativeCancel() }

    // Called only with fixed stage codes and numeric counters; no native text crosses into diagnostics.
    @Suppress("unused")
    private fun onNativeStage(stage: Int, elapsedMs: Long, tokens: Int) {
        RunLog.info(LogModule.INFERENCE, if (stage == 0) "prefill" else "decode",
            "elapsed_ms" to elapsedMs, "tokens" to tokens)
    }

    @Synchronized
    fun close() { nativeClose(); loadedPath = null }

    private external fun nativeLoad(path: String)
    private external fun nativeGenerate(prompt: ByteArray, grammar: String, maxTokens: Int): ByteArray
    private external fun nativeCancel()
    private external fun nativeClose()
}
