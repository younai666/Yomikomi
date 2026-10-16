package io.github.yomikomi.translation

import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog
import io.github.yomikomi.logging.InferenceDevice
import android.os.Debug

/** Serialize ownership of the native model/context; cancellation only touches an atomic flag. */
object NativeLlama {
    private val library = lazy {
        try { System.loadLibrary("yomikomi") }
        catch (error: LinkageError) { throw IllegalStateException("本地推理组件不可用，请重新安装应用", error) }
    }
    private var loadedPath: String? = null
    private var requestedGpu = false
    internal var usesVulkan = false
        private set
    private var device = InferenceDevice(false, "none")
    private var promptTokens = 0
    private var prefillTokens = 0
    private var generatedTokens = 0
    private var prefillMs = 0L
    private var decodeMs = 0L

    @Synchronized
    fun isLoaded(path: String, gpuAcceleration: Boolean) = loadedPath == path && requestedGpu == gpuAcceleration

    @Synchronized
    fun load(path: String, gpuAcceleration: Boolean = false) {
        if (isLoaded(path, gpuAcceleration)) return
        loadedPath = null
        usesVulkan = false
        requestedGpu = gpuAcceleration
        RunLog.measure(LogModule.INFERENCE, "load", "gpu_requested" to if (gpuAcceleration) 1 else 0) {
            library.value
            nativeLoad(path, gpuAcceleration)
        }
        usesVulkan = nativeBackendInfo()[0] == 1L
        device = InferenceDevice(usesVulkan, nativeGpuName())
        loadedPath = path
        logMemory("backend")
    }

    @Synchronized
    fun generate(prompt: String, grammar: String, cancellationVersion: Long, reusePrefix: Boolean): String {
        check(loadedPath != null) { "请先下载并加载翻译模型" }
        promptTokens = 0; prefillTokens = 0; generatedTokens = 0; prefillMs = 0; decodeMs = 0
        val started = System.nanoTime()
        var succeeded = false
        try {
            return RunLog.measure(LogModule.INFERENCE, "generate") {
                nativeGenerate(prompt.toByteArray(Charsets.UTF_8), grammar, 4096, cancellationVersion, reusePrefix).toString(Charsets.UTF_8)
            }.also { succeeded = true }
        } finally {
            RunLog.inference("summary", device, "success" to if (succeeded) 1 else 0,
                "prompt_tokens" to promptTokens, "prefill_tokens" to prefillTokens, "generated_tokens" to generatedTokens,
                "prefill_tok_s" to rate(prefillTokens, prefillMs), "decode_tok_s" to rate(generatedTokens, decodeMs),
                "total_ms" to RunLog.elapsed(started))
            logMemory("memory")
        }
    }

    fun cancel() { RunLog.info(LogModule.INFERENCE, "cancel_requested"); if (library.isInitialized()) nativeCancel() }
    fun cancellationVersion(): Long { library.value; return nativeCancellationVersion() }

    // Called only with fixed stage codes and numeric counters; no native text crosses into diagnostics.
    @Suppress("unused")
    private fun onNativeStage(stage: Int, elapsedMs: Long, tokens: Int) {
        when (stage) {
            0 -> { prefillTokens = tokens; prefillMs = elapsedMs }
            1 -> { generatedTokens = tokens; decodeMs = elapsedMs }
            3 -> promptTokens = tokens
        }
        RunLog.info(LogModule.INFERENCE, when (stage) { 0 -> "prefill"; 1 -> "decode"; 2 -> "prefix_reuse"; else -> "prompt" },
            "elapsed_ms" to elapsedMs, "tokens" to tokens, "tok_s" to if (stage in 0..1) rate(tokens, elapsedMs) else 0.0)
    }

    private fun rate(tokens: Int, elapsedMs: Long) = if (elapsedMs > 0) tokens * 1000.0 / elapsedMs else 0.0

    private fun logMemory(event: String) = try {
        val info = nativeBackendInfo()
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        RunLog.inference(event, device, "gpu_requested" to if (requestedGpu) 1 else 0,
            "offload_layers" to info[1], "total_layers" to info[2],
            "gpu_free_bytes" to info[3], "gpu_total_bytes" to info[4], "model_bytes" to info[5],
            "fallback_reason" to info[6], "model_gpu_buffer_mib" to info[7] / 1048576.0,
            "gpu_error" to info[8],
            "process_pss_kb" to memory.totalPss, "native_heap_bytes" to Debug.getNativeHeapAllocatedSize(),
            "java_heap_bytes" to Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() })
    } catch (error: Exception) { RunLog.warn(LogModule.INFERENCE, "memory_unavailable", error) }

    private external fun nativeLoad(path: String, gpuAcceleration: Boolean)
    private external fun nativeBackendInfo(): LongArray
    private external fun nativeGpuName(): String
    private external fun nativeGenerate(prompt: ByteArray, grammar: String, maxTokens: Int, cancellationVersion: Long, reusePrefix: Boolean): ByteArray
    private external fun nativeCancel()
    private external fun nativeCancellationVersion(): Long
}
