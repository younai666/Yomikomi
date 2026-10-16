package io.github.yomikomi.logging

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TimeZone

enum class LogModule { APP, ACTION, PERMISSION, CAPTURE, OCR, MODEL, INFERENCE, API, CONTEXT, OVERLAY, DOWNLOAD, EXPORT, UPDATE }
enum class LogLevel { INFO, WARN, ERROR }

/** The only text metadata allowed is a bounded hardware name supplied by the native driver. */
internal data class InferenceDevice(val vulkan: Boolean, val gpuName: String) {
    fun fields(): String = "backend=${if (vulkan) "vulkan" else "cpu"} gpu_name=\"${gpuName.filter {
        it in ' '..'~' && it != '"' && it != '\\'
    }.take(96)}\""
}

internal data class LogEntry(
    val level: LogLevel, val module: LogModule, val event: String,
    val metrics: List<Pair<String, Number>> = emptyList(), val error: Throwable? = null,
    val time: Long = System.currentTimeMillis(),
    val device: InferenceDevice? = null,
    val diagnostic: String? = null,
)

/** Fixed metadata plus explicitly requested, escaped content diagnostics. Exception messages stay omitted. */
internal class LogStore(private val directory: File, private val maxBytes: Int = 1_048_576) {
    private val identifier = Regex("[a-z][a-z0-9_.]{0,63}")
    private val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun file(index: Int) = File(directory, if (index == 0) "runtime.log" else "runtime.$index.log")

    @Synchronized
    fun append(entry: LogEntry): Boolean = try {
        require(identifier.matches(entry.event) && entry.metrics.all { identifier.matches(it.first) })
        check(directory.isDirectory || directory.mkdirs())
        val text = buildString {
            append(date.format(Date(entry.time))).append(' ').append(entry.level).append(' ').append(entry.module)
                .append(' ').append(entry.event)
            entry.device?.let { append(' ').append(it.fields()) }
            entry.metrics.forEach { (key, value) ->
                append(' ').append(key).append('=').append(when (value) {
                    is Double, is Float -> {
                        require(value.toDouble().isFinite())
                        String.format(Locale.ROOT, "%.3f", value.toDouble())
                    }
                    else -> value.toLong().toString()
                })
            }
            entry.diagnostic?.let { value ->
                append(" text=\"")
                value.forEach { char -> when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                } }
                append('"')
            }
            append('\n')
            entry.error?.let { append(safeStack(it)) }
        }
        val bytes = text.toByteArray(Charsets.UTF_8).let {
            if (it.size <= maxBytes) it else (text.take((maxBytes - 32).coerceAtLeast(0) / 4) + "\n[truncated]\n").toByteArray(Charsets.UTF_8)
        }
        if (file(0).length() + bytes.size > maxBytes) {
            if (file(2).exists()) check(file(2).delete())
            for (index in 1 downTo 0) if (file(index).exists()) check(file(index).renameTo(file(index + 1)))
        }
        FileOutputStream(file(0), true).use { it.write(bytes) }
        true
    } catch (_: Exception) { false }

    /** Oldest first, read under the same lock as rotation; the exported snapshot is bounded to 3 MiB. */
    @Synchronized
    fun snapshot(): ByteArray = java.io.ByteArrayOutputStream().use { output ->
        for (index in 2 downTo 0) if (file(index).isFile) file(index).inputStream().use { it.copyTo(output) }
        output.toByteArray()
    }

    private fun safeStack(error: Throwable): String = buildString {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        fun trace(value: Throwable, relation: String) {
            if (seen.size >= 8 || !seen.add(value)) { append("  [exception chain truncated]\n"); return }
            append("  ").append(relation).append(value.javaClass.name).append(" [message omitted]\n")
            value.stackTrace.take(64).forEach { frame ->
                append("    at ").append(frame.className).append('.').append(frame.methodName)
                    .append('(').append(frame.fileName ?: "Unknown Source").append(':').append(frame.lineNumber).append(")\n")
            }
            if (value.stackTrace.size > 64) append("    [frames truncated]\n")
            value.suppressed.take(8).forEach { trace(it, "Suppressed: ") }
            value.cause?.let { trace(it, "Caused by: ") }
        }
        trace(error, "")
    }
}
