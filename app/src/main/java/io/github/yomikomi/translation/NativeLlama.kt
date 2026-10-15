package io.github.yomikomi.translation

/** Serialize ownership of the native model/context; cancellation only touches an atomic flag. */
object NativeLlama {
    init { System.loadLibrary("yomikomi") }
    private var loadedPath: String? = null

    @Synchronized
    fun load(path: String) {
        if (loadedPath == path) return
        nativeLoad(path)
        loadedPath = path
    }

    @Synchronized
    fun generate(prompt: String, grammar: String): String {
        check(loadedPath != null) { "请先下载并加载翻译模型" }
        return nativeGenerate(prompt.toByteArray(Charsets.UTF_8), grammar, 4096).toString(Charsets.UTF_8)
    }

    fun cancel() = nativeCancel()

    @Synchronized
    fun close() { nativeClose(); loadedPath = null }

    private external fun nativeLoad(path: String)
    private external fun nativeGenerate(prompt: ByteArray, grammar: String, maxTokens: Int): ByteArray
    private external fun nativeCancel()
    private external fun nativeClose()
}
