package io.github.yomikomi.logging

import java.io.File
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Logging must never throw into the translation pipeline, including on full disks and queue overflow. */
object RunLog {
    private var store: LogStore? = null
    @Volatile private var worker: ThreadPoolExecutor? = null
    private var diagnostic: () -> Unit = {}
    private val warned = AtomicBoolean()
    private val dropped = AtomicLong()

    fun initialize(directory: File, onFailure: () -> Unit) {
        if (store != null) return
        store = LogStore(directory)
        diagnostic = onFailure
        worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128),
            { task -> Thread(task, "YomikomiLog").apply { isDaemon = true } })
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(protectedCrashHandler(previous) { error ->
            write(LogEntry(LogLevel.ERROR, LogModule.APP, "uncaught", error = error))
        })
    }

    fun info(module: LogModule, event: String, vararg metrics: Pair<String, Number>) =
        enqueue(LogEntry(LogLevel.INFO, module, event, metrics.toList()))

    internal fun inference(event: String, device: InferenceDevice, vararg metrics: Pair<String, Number>) =
        enqueue(LogEntry(LogLevel.INFO, LogModule.INFERENCE, event, metrics.toList(), device = device))

    fun warn(module: LogModule, event: String, error: Throwable? = null, vararg metrics: Pair<String, Number>) =
        enqueue(LogEntry(LogLevel.WARN, module, event, metrics.toList(), error))

    fun error(module: LogModule, event: String, error: Throwable, vararg metrics: Pair<String, Number>) =
        enqueue(LogEntry(LogLevel.ERROR, module, event, metrics.toList(), error))

    fun <T> measure(module: LogModule, event: String, vararg metrics: Pair<String, Number>, block: () -> T): T {
        val started = System.nanoTime()
        info(module, "$event.started", *metrics)
        try {
            return block().also { info(module, "$event.done", *metrics, "elapsed_ms" to elapsed(started)) }
        } catch (error: Throwable) {
            error(module, "$event.failed", error, *metrics, "elapsed_ms" to elapsed(started))
            throw error
        }
    }

    fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000

    /** Queued after earlier records. No URI, file name, provider or document contents enter the log. */
    fun export(destination: () -> OutputStream, complete: (Boolean) -> Unit) {
        val task = Runnable {
            var success = false
            try {
                write(LogEntry(LogLevel.INFO, LogModule.EXPORT, "started"))
                val bytes = checkNotNull(store).snapshot()
                check(bytes.isNotEmpty()) { "Log unavailable" }
                destination().use { it.write(bytes); it.flush() }
                write(LogEntry(LogLevel.INFO, LogModule.EXPORT, "done", listOf("bytes" to bytes.size)))
                success = true
            } catch (error: Exception) { write(LogEntry(LogLevel.ERROR, LogModule.EXPORT, "failed", error = error)) }
            finally { complete(success) }
        }
        try { checkNotNull(worker).execute(task) }
        catch (_: Exception) { complete(false) }
    }

    private fun enqueue(entry: LogEntry) {
        val executor = worker ?: return // Pure translation tests and very early startup need no Android runtime.
        try {
            executor.execute {
                val lost = dropped.getAndSet(0)
                if (lost > 0) write(LogEntry(LogLevel.WARN, LogModule.APP, "log_queue_overflow", listOf("dropped" to lost)))
                write(entry)
            }
        } catch (_: RejectedExecutionException) { dropped.incrementAndGet() }
    }

    private fun write(entry: LogEntry) {
        try {
            if (store?.append(entry) == false && warned.compareAndSet(false, true)) diagnostic()
        } catch (_: Throwable) {
            // Even a broken exception object or logger failure must not replace the original failure.
        }
    }
}

internal fun protectedCrashHandler(previous: Thread.UncaughtExceptionHandler?, record: (Throwable) -> Unit) =
    Thread.UncaughtExceptionHandler { thread, error ->
        try { record(error) } catch (_: Throwable) { /* Preserve Android's original crash handler. */ }
        finally { previous?.uncaughtException(thread, error) }
    }
