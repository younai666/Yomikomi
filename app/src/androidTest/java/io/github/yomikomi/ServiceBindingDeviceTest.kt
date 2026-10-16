package io.github.yomikomi

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yomikomi.capture.ScreenCaptureService
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog
import io.github.yomikomi.translation.NativeLlama
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ServiceBindingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val component = "io.github.yomikomi/io.github.yomikomi.capture.ScreenCaptureService"
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }

    @Test fun manifestDeclaresOnlyTheRequiredAccessibilityCapabilities() {
        val name = ComponentName(context, ScreenCaptureService::class.java)
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(name, PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", service.permission)
        assertTrue(service.exported)
        assertTrue(service.enabled)
        assertNotEquals(0, service.metaData.getInt("android.accessibilityservice"))
        val info = context.getSystemService(AccessibilityManager::class.java).installedAccessibilityServiceList
            .single { it.resolveInfo.serviceInfo.name == name.className && it.resolveInfo.serviceInfo.packageName == name.packageName }
        assertTrue(info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0)
        assertEquals(0, info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT)
        assertNotEquals(0, info.eventTypes)
    }

    /** Changes only this service's setting on an explicitly opted-in development device. */
    @Test fun firstEnableBindsWithoutReadingBrokenSettingsAndSurvivesModelFailure() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("serviceBinding") == "true")
        val enabled = shell("settings get secure enabled_accessibility_services").takeUnless { it == "null" }.orEmpty()
        check(enabled.isEmpty() || Regex("[A-Za-z0-9_.$/:]+").matches(enabled))
        val others = enabled.split(':').filter { it.isNotBlank() && it != component }
        val original = RuntimeSettings.read(context)
        fun setEnabled(value: String) {
            if (value.isBlank()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $value")
        }
        try {
            setEnabled(others.joinToString(":"))
            waitUntil { ScreenCaptureService.current == null }
            instrumentation.runOnMainSync {
                context.getSharedPreferences("settings", 0).edit().putInt("source", 42).commit()
            }
            RunLog.info(LogModule.PERMISSION, "binding_probe")
            setEnabled((others + component).joinToString(":"))
            waitUntil { ScreenCaptureService.current != null && AppRuntime.serviceConnected }
            val startup = runtimeLogSnapshot().substringAfterLast("PERMISSION binding_probe")
            assertTrue(startup.contains("accessibility_create.done"))
            assertTrue(startup.contains("accessibility_connected"))
            assertFalse(startup.contains("OCR initialize"))
            assertFalse(startup.contains("MODEL load"))
            assertFalse(startup.contains("INFERENCE load"))
            instrumentation.runOnMainSync { checkNotNull(ScreenCaptureService.current).toggle() }
            assertEquals("就绪", AppRuntime.pipelinePhase)
            assertTrue(AppRuntime.serviceConnected)
            instrumentation.runOnMainSync { original.save(context) }
            AppRuntime.worker.submit {
                assertThrows(IllegalStateException::class.java) {
                    NativeLlama.load(File(context.cacheDir, "nonexistent-binding-fixture.gguf").absolutePath)
                }
            }.get(30, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { checkNotNull(ScreenCaptureService.current).onInterrupt() }
            assertTrue(AppRuntime.serviceConnected)
            setEnabled(others.joinToString(":"))
            waitUntil { ScreenCaptureService.current == null && !AppRuntime.serviceConnected }
            assertTrue(runtimeLogSnapshot().contains("PERMISSION accessibility_unbound"))
            setEnabled((others + component).joinToString(":"))
            waitUntil { ScreenCaptureService.current != null && AppRuntime.serviceConnected }
        } finally {
            instrumentation.runOnMainSync { original.save(context) }
            setEnabled(enabled)
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Accessibility binding timed out" }
            SystemClock.sleep(50)
        }
    }
}
