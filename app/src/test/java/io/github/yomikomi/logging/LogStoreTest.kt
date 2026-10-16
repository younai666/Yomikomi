package io.github.yomikomi.logging

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LogStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun recordsUtcTimeSeverityModuleStateAndNumericDuration() {
        val store = LogStore(folder.newFolder())
        assertTrue(store.append(LogEntry(LogLevel.INFO, LogModule.OCR, "recognize.done",
            listOf("blocks" to 12, "elapsed_ms" to 48), time = 0)))
        assertEquals("1970-01-01T00:00:00.000Z INFO OCR recognize.done blocks=12 elapsed_ms=48\n",
            store.snapshot().toString(Charsets.UTF_8))
    }

    @Test fun exceptionsKeepFramesCausesAndSuppressedButNeverTheirMessages() {
        val store = LogStore(folder.newFolder())
        val sensitive = "sk-private-key OCR原文 译文 https://secret.example/private-path"
        val cause = IllegalArgumentException(sensitive)
        val error = IllegalStateException(sensitive, cause).apply { addSuppressed(java.io.IOException(sensitive)) }
        assertTrue(store.append(LogEntry(LogLevel.ERROR, LogModule.API, "request.failed", error = error)))
        val text = store.snapshot().toString(Charsets.UTF_8)
        assertFalse(text.contains(sensitive))
        listOf("sk-private", "OCR原文", "译文", "secret.example").forEach { assertFalse(text.contains(it)) }
        assertTrue(text.contains("java.lang.IllegalStateException [message omitted]"))
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException"))
        assertTrue(text.contains("Suppressed: java.io.IOException"))
        assertTrue(text.contains("at io.github.yomikomi.logging.LogStoreTest.exceptionsKeepFrames"))
    }

    @Test fun rotatesThreeBoundedFilesAndExportsThemInChronologicalOrder() {
        val directory = folder.newFolder()
        val store = LogStore(directory, maxBytes = 140)
        for (index in 0..9) assertTrue(store.append(LogEntry(LogLevel.INFO, LogModule.APP, "tick", listOf("sequence" to index), time = index.toLong())))
        assertEquals(3, directory.listFiles()!!.size)
        assertTrue(directory.listFiles()!!.all { it.length() <= 140 })
        val sequences = Regex("sequence=(\\d+)").findAll(store.snapshot().toString(Charsets.UTF_8)).map { it.groupValues[1].toInt() }.toList()
        assertEquals((4..9).toList(), sequences)
    }

    @Test fun oversizedOrCyclicExceptionCannotGrowStorageWithoutLimit() {
        val directory = folder.newFolder()
        val store = LogStore(directory, maxBytes = 512)
        val first = IllegalStateException("private")
        val second = IllegalStateException("private", first)
        first.initCause(second)
        first.stackTrace = Array(1000) { StackTraceElement("Example", "call", "Example.kt", it) }
        assertTrue(store.append(LogEntry(LogLevel.ERROR, LogModule.APP, "uncaught", error = first)))
        assertTrue(File(directory, "runtime.log").length() <= 512)
        assertTrue(store.snapshot().toString(Charsets.UTF_8).contains("[truncated]"))
    }

    @Test fun concurrentWritersNeverInterleaveRecordsOrCorruptRotation() {
        val store = LogStore(folder.newFolder())
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 200).map { index -> pool.submit<Boolean> {
                store.append(LogEntry(LogLevel.INFO, LogModule.APP, "tick", listOf("sequence" to index)))
            } }
            assertTrue(jobs.all { it.get(5, TimeUnit.SECONDS) })
            val lines = store.snapshot().toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
            assertEquals(200, lines.size)
            assertEquals(200, lines.map { it.substringAfter("sequence=") }.toSet().size)
        } finally { pool.shutdownNow() }
    }

    @Test fun storageFailureAndInvalidEventAreRejectedWithoutThrowing() {
        val blocked = folder.newFile()
        assertFalse(LogStore(blocked).append(LogEntry(LogLevel.INFO, LogModule.APP, "started")))
        val store = LogStore(folder.newFolder())
        assertFalse(store.append(LogEntry(LogLevel.INFO, LogModule.APP, "unsafe\ninjection")))
        assertFalse(store.append(LogEntry(LogLevel.INFO, LogModule.APP, "safe", listOf("unsafe\nfield" to 1))))
        assertEquals(0, store.snapshot().size)
    }

    @Test fun crashRecordPrecedesAndroidHandlerAndLoggerFailureCannotReplaceTheCrash() {
        val crash = IllegalStateException("private")
        val calls = mutableListOf<String>()
        val previous = Thread.UncaughtExceptionHandler { _, failure ->
            assertSame(crash, failure)
            calls += "android"
        }
        protectedCrashHandler(previous) { failure ->
            assertSame(crash, failure)
            calls += "record"
        }.uncaughtException(Thread.currentThread(), crash)
        assertEquals(listOf("record", "android"), calls)
        calls.clear()
        protectedCrashHandler(previous) { throw java.io.IOException("full disk") }
            .uncaughtException(Thread.currentThread(), crash)
        assertEquals(listOf("android"), calls)
    }
}
