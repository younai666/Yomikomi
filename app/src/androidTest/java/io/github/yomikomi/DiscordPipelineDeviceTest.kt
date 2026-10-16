package io.github.yomikomi

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yomikomi.capture.ScreenCaptureService
import io.github.yomikomi.translation.ApiSecretStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** User-supplied fixture is pushed separately, never packaged or committed. Live API use is explicit. */
@RunWith(AndroidJUnit4::class)
class DiscordPipelineDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test fun suppliedRussianImageUsesApiAndOverlayDisappearsOnScroll() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("discordFixture") == "true")
        val context = instrumentation.targetContext
        val original = RuntimeSettings.read(context)
        check(ApiSecretStore(context).hasKey(original.api.endpoint)) { "Save an API key before running the live fixture" }
        val enabled = shell("settings get secure enabled_accessibility_services").trim()
        val own = "io.github.yomikomi/io.github.yomikomi.capture.ScreenCaptureService"
        check(own in enabled.split(':'))
        if (ScreenCaptureService.current == null) {
            val others = enabled.split(':').filter { it != own }.joinToString(":")
            if (others.isBlank()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $others")
            SystemClock.sleep(250)
            shell("settings put secure enabled_accessibility_services $enabled")
        }
        waitUntil(10_000) { ScreenCaptureService.current != null }
        try {
            instrumentation.runOnMainSync { original.copy(source = "俄语", target = "简体中文",
                api = original.api.copy(enabled = true)).save(context) }
            translateImage()
            val log = runtimeLogSnapshot().substringAfterLast("CAPTURE requested")
            assertTrue("Must recognize the supplied Russian Discord image", log.contains("пакеты") && log.contains("библиотека").not())
            listOf("OCR ocr_raw", "API api_input", "API api_response", "API id_map", "API first_byte", "OVERLAY layout").forEach {
                assertTrue(it, log.contains(it))
            }
            assertFalse(log.substringAfter("API id_map").substringBefore('\n').contains("�"))
            shell("screencap -p /data/local/tmp/yomikomi-discord-overlay.png")
            instrumentation.runOnMainSync {
                for (type in listOf(2048, 4096)) {
                    val event = android.view.accessibility.AccessibilityEvent.obtain(type).apply {
                        packageName = "io.github.yomikomi.test"
                        contentChangeTypes = android.view.accessibility.AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
                    }
                    checkNotNull(ScreenCaptureService.current).onAccessibilityEvent(event)
                    event.recycle()
                }
            }
            SystemClock.sleep(1800) // The image is static; own Overlay pixels must not invalidate the watcher.
            assertEquals("已覆盖", AppRuntime.pipelinePhase)
            val start = SystemClock.elapsedRealtime()
            shell("input swipe 800 1800 800 1100 350")
            waitUntil(2000) { AppRuntime.pipelinePhase == "就绪" }
            android.util.Log.i("DiscordFixture", "Scroll dismissal ms=${SystemClock.elapsedRealtime() - start}")
            assertTrue(runtimeLogSnapshot().contains("CAPTURE cancelled.page_changed"))
            for ((name, command) in listOf(
                "page" to "am start --activity-single-top -n io.github.yomikomi.test/io.github.yomikomi.fixture.ScreenFixtureActivity --es page unrelated",
                "home" to "input keyevent 3", "recents" to "input keyevent 187",
            )) {
                translateImage()
                val before = SystemClock.elapsedRealtime()
                shell(command)
                waitUntil(2000) { AppRuntime.pipelinePhase == "就绪" }
                android.util.Log.i("DiscordFixture", "$name dismissal ms=${SystemClock.elapsedRealtime() - before}")
            }
        } finally {
            instrumentation.runOnMainSync { ScreenCaptureService.current?.onAppForegrounded(); original.save(context) }
        }
    }

    private fun translateImage() {
        val launch = shell("am start --activity-single-top -W -n io.github.yomikomi.test/io.github.yomikomi.fixture.ScreenFixtureActivity --ez image true")
        assertFalse(launch, launch.contains("Error"))
        SystemClock.sleep(1000)
        assertTrue("Fixture must be foreground", shell("dumpsys activity activities").lineSequence()
            .any { "topResumedActivity=" in it && "fixture.ScreenFixtureActivity" in it })
        shell("cmd statusbar expand-settings")
        SystemClock.sleep(700)
        shell("cmd statusbar click-tile io.github.yomikomi/.capture.TranslationTileService")
        waitUntil(90_000) { AppRuntime.pipelinePhase == "已覆盖" || AppRuntime.pipelinePhase == "翻译中" }
        waitUntil(90_000) { AppRuntime.pipelinePhase == "已覆盖" || AppRuntime.pipelinePhase == "就绪" }
        assertEquals(runtimeLogSnapshot().takeLast(1800), "已覆盖", AppRuntime.pipelinePhase)
        assertTrue("Every dismissal scenario must translate the same Russian image",
            runtimeLogSnapshot().substringAfterLast("CAPTURE requested").contains("пакеты"))
    }

    private fun waitUntil(timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Discord pipeline timed out: ${AppRuntime.pipelinePhase}" }
            SystemClock.sleep(50)
        }
    }
}
