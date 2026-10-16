package io.github.yomikomi

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RuntimeLogDeviceTest {
    @Test fun explicitTextDiagnosticsEscapeRecordsAndRedactTheActualKey() {
        RunLog.text(LogModule.API, "trace_probe", 99, "OCR/译文\nforged record sk-private-probe-key custom-secret-probe", "custom-secret-probe")
        val line = runtimeLogSnapshot().lineSequence().last { "API trace_probe" in it }
        assertTrue(line.contains("OCR/译文\\nforged record"))
        assertTrue(line.contains("[REDACTED]"))
        assertFalse(line.contains("sk-private-probe-key"))
        assertFalse(line.contains("custom-secret-probe"))
    }

    @Test fun snapshotFlushesEarlierEventsAndOmitsSensitiveExceptionMessages() {
        val secrets = listOf("sk-log-test-key-123456", "OCR原文不要保存", "翻译内容不要保存", "https://private.example/secret")
        val error = IllegalStateException(secrets.joinToString(), IOException(secrets.joinToString()))
        error.addSuppressed(IllegalArgumentException(secrets.joinToString()))
        RunLog.info(LogModule.OCR, "privacy_probe", "blocks" to 3, "elapsed_ms" to 17)
        RunLog.error(LogModule.API, "privacy_probe", error)
        val text = runtimeLogSnapshot()
        assertTrue(text.contains("INFO OCR privacy_probe blocks=3 elapsed_ms=17"))
        assertTrue(text.contains("ERROR API privacy_probe"))
        assertTrue(text.contains("Caused by: java.io.IOException"))
        assertTrue(text.contains("RuntimeLogDeviceTest.kt"))
        secrets.forEach { assertFalse(text.contains(it)) }
    }

    @Test fun failedDestinationReportsFailureAndLaterExportStillWorks() {
        val completed = CountDownLatch(1)
        var success = true
        RunLog.export({ object : OutputStream() {
            override fun write(value: Int) { throw IOException("PRIVATE_EXPORT_DESTINATION") }
        } }) { success = it; completed.countDown() }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertFalse(success)
        val text = runtimeLogSnapshot()
        assertTrue(text.contains("ERROR EXPORT failed"))
        assertFalse(text.contains("PRIVATE_EXPORT_DESTINATION"))
    }
}

internal fun runtimeLogSnapshot(): String {
    val output = ByteArrayOutputStream()
    val completed = CountDownLatch(1)
    var success = false
    RunLog.export({ output }) { success = it; completed.countDown() }
    assertTrue(completed.await(5, TimeUnit.SECONDS))
    assertTrue(success)
    return output.toString("UTF-8")
}
