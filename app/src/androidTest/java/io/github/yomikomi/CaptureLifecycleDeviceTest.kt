package io.github.yomikomi

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yomikomi.capture.ScreenCaptureService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Requires an already enabled accessibility service and downloaded models; never reads a UI tree. */
@RunWith(AndroidJUnit4::class)
class CaptureLifecycleDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test fun slowRequestSurvivesShadeDetachAndSpuriousEventsButRejectsChangedPage() {
        // Do not let the test automation connection suppress the service being tested.
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureLifecycle") == "true")
        val enabled = shell("settings get secure enabled_accessibility_services").trim()
        val component = "io.github.yomikomi/io.github.yomikomi.capture.ScreenCaptureService"
        assertTrue("Enable Yomikomi accessibility before this opt-in test", component in enabled.split(':'))
        check(Regex("[A-Za-z0-9_.$/:]+").matches(enabled))
        if (ScreenCaptureService.current == null) {
            val others = enabled.split(':').filter { it != component }.joinToString(":")
            try {
                if (others.isEmpty()) shell("settings delete secure enabled_accessibility_services")
                else shell("settings put secure enabled_accessibility_services $others")
                SystemClock.sleep(300)
            } finally { shell("settings put secure enabled_accessibility_services $enabled") }
        }
        waitUntil(10_000) { ScreenCaptureService.current != null }
        val context = instrumentation.targetContext
        val original = RuntimeSettings.read(context)
        val release = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync { original.copy(source = "俄语", target = "简体中文",
                api = original.api.copy(enabled = false)).save(context) }
            shell("am start -n io.github.yomikomi.test/io.github.yomikomi.fixture.ScreenFixtureActivity --es language 俄语 --ez dark true")
            SystemClock.sleep(800)
            val blocked = CountDownLatch(1)
            AppRuntime.worker.execute { blocked.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            clickTile()
            SystemClock.sleep(6_000) // Longer than the S23's observed 3-5 second failure window.
            injectNoise()
            instrumentation.runOnMainSync { assertNotEquals("就绪", AppRuntime.pipelinePhase) }
            release.countDown()
            waitUntil(120_000) { AppRuntime.pipelinePhase == "加载模型中" || AppRuntime.pipelinePhase == "翻译中" }
            injectNoise()
            waitUntil(120_000) { AppRuntime.pipelinePhase == "翻译中" }
            injectNoise() // Same events while the real native engine owns the worker.
            waitUntil(120_000) { AppRuntime.pipelinePhase == "已覆盖" || AppRuntime.pipelinePhase == "就绪" }
            assertEquals("已覆盖", AppRuntime.pipelinePhase)
            val log = runtimeLogSnapshot()
            assertTrue(log.contains("ACTION tile_stop_listening"))
            assertTrue(log.contains("CAPTURE validation request="))
            assertTrue(log.contains("unchanged=1"))
            clickTile()
            waitUntil(5_000) { AppRuntime.pipelinePhase == "就绪" }

            clickTile()
            waitUntil(120_000) { AppRuntime.pipelinePhase == "翻译中" }
            shell("am start --activity-single-top -n io.github.yomikomi.test/io.github.yomikomi.fixture.ScreenFixtureActivity --es page unrelated --ez dark true")
            waitUntil(120_000) { AppRuntime.pipelinePhase == "就绪" }
            assertTrue(runtimeLogSnapshot().contains("CAPTURE cancelled.page_changed"))

            val blockedAgain = CountDownLatch(1)
            val resume = CountDownLatch(1)
            try {
                AppRuntime.worker.execute { blockedAgain.countDown(); check(resume.await(15, TimeUnit.SECONDS)) }
                assertTrue(blockedAgain.await(5, TimeUnit.SECONDS))
                clickTile()
                SystemClock.sleep(800)
                clickTile() // A user's second tap must still cancel queued/slow work.
                waitUntil(5_000) { AppRuntime.pipelinePhase == "就绪" }
            } finally { resume.countDown() }
            AppRuntime.worker.submit { }.get(10, TimeUnit.SECONDS)
            assertEquals("就绪", AppRuntime.pipelinePhase)
            assertTrue(runtimeLogSnapshot().contains("CAPTURE cancelled.tile"))
        } finally {
            release.countDown()
            instrumentation.runOnMainSync {
                ScreenCaptureService.current?.onAppForegrounded()
                original.save(context)
            }
        }
    }

    private fun injectNoise() = instrumentation.runOnMainSync {
        val service = checkNotNull(ScreenCaptureService.current)
        for ((type, pkg, name) in listOf(
            Triple(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.keyboard", "android.inputmethodservice.SoftInputWindow"),
            Triple(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "io.github.yomikomi.test", "android.app.Dialog"),
            Triple(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "io.github.yomikomi.test", "android.view.View"),
            Triple(AccessibilityEvent.TYPE_VIEW_SCROLLED, "io.github.yomikomi.test", "androidx.recyclerview.widget.RecyclerView"),
        )) {
            val event = AccessibilityEvent.obtain(type).apply {
                packageName = pkg; className = name
                contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
            }
            try { service.onAccessibilityEvent(event) } finally { event.recycle() }
        }
    }

    private fun clickTile() {
        shell("cmd statusbar expand-settings")
        SystemClock.sleep(700)
        shell("cmd statusbar click-tile io.github.yomikomi/.capture.TranslationTileService")
    }

    private fun waitUntil(timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Capture stage timed out: ${AppRuntime.pipelinePhase}" }
            SystemClock.sleep(100)
        }
    }
}
