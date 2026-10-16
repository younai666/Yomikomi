package io.github.yomikomi

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yomikomi.translation.PromptMode
import io.github.yomikomi.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory settings only: animated dialog disposal and draft lifetime must not regress. */
@RunWith(AndroidJUnit4::class)
class MotionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun connectAccessibilityBeforeCreatingComposeContent() {
        // Compose must observe accessibility as enabled before its first semantics tree is built.
        instrumentation.uiAutomation
        instrumentation.waitForIdleSync()
    }

    @Test fun opacityDragUpdatesPreviewWithoutCommittingSettingsUntilRelease() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val state = UiState(RuntimeSettings("日语", "简体中文", 1f, 16, true))
        try {
            instrumentation.runOnMainSync {
                activity.setContent { YomikomiTheme { Column(Modifier.padding(24.dp)) { SettingsScreen(state) { } } } }
            }
            waitUntil { has("Overlay 不透明度") }
            val bounds = Rect()
            find(screenRoot(), "Overlay 不透明度")!!.getBoundsInScreen(bounds)
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, x: Float) {
                SystemClock.sleep(40)
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, bounds.centerY().toFloat(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            var released = false
            try {
                touch(MotionEvent.ACTION_DOWN, bounds.right - bounds.width() / 8f)
                for (step in 2..5) touch(MotionEvent.ACTION_MOVE, bounds.right - bounds.width() * step / 8f)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { assertEquals(1f, state.opacity, 0f) }
                touch(MotionEvent.ACTION_UP, bounds.centerX().toFloat())
                released = true
            } finally { if (!released) touch(MotionEvent.ACTION_CANCEL, bounds.centerX().toFloat()) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertTrue(state.opacity in 0.3f..0.9f) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun closingPanelReleasesWindowAndAllowsOpeningItAgain() = withPanel(Panel.Source) { panel, _ ->
        click("关闭源语言")
        waitUntil { !has("关闭源语言") }
        click("打开面板")
        waitUntil { has("关闭源语言") }
        instrumentation.runOnMainSync { assertEquals(Panel.Source, panel.value) }
        click("关闭源语言")
        waitUntil { !has("关闭源语言") }
        click("打开面板")
        waitUntil { has("关闭源语言") }
    }

    @Test fun apiPromptSelectionSurvivesAnimatedSubpanelRoundTrip() = withPanel(Panel.Api) { panel, state ->
        click("翻译提示词")
        waitUntil { has("关闭翻译提示词") }
        click("网络聊天")
        waitUntil { has("关闭第三方 API") }
        waitUntil { has("网络聊天") }
        click("完成")
        waitUntil { !has("关闭第三方 API") }
        instrumentation.runOnMainSync {
            assertNull(panel.value)
            assertEquals(PromptMode.Chat, state.api.prompt.mode)
            assertFalse(state.api.enabled)
        }
    }

    private fun withPanel(initial: Panel, test: (androidx.compose.runtime.MutableState<Panel?>, UiState) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val panel = mutableStateOf<Panel?>(initial)
        val state = UiState(RuntimeSettings("日语", "简体中文", 1f, 16, true))
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    YomikomiTheme {
                        Box(Modifier.fillMaxSize()) {
                            ActionButton("打开面板", { panel.value = initial })
                            SettingsPanel(panel.value, state, { panel.value = null }, { panel.value = it })
                        }
                    }
                }
            }
            waitUntil { has(if (initial == Panel.Source) "关闭源语言" else "关闭第三方 API") }
            test(panel, state)
        } catch (error: AssertionError) {
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try {
                java.io.File(instrumentation.targetContext.cacheDir, "motion-failure.png").outputStream().use {
                    screenshot?.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { screenshot?.recycle() }
            throw AssertionError("Panel state at failure: ${panel.value}", error)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun screenRoot(): AccessibilityNodeInfo? {
        val automation = instrumentation.uiAutomation
        // AnimatedContent replaces virtual nodes without replacing the dialog window.
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
        return automation.rootInActiveWindow
    }
    private fun has(label: String) = find(screenRoot(), label) != null
    private fun click(label: String) {
        waitUntil {
            var node = find(screenRoot(), label)
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        if (node.text?.lineSequence()?.any { it == label } == true || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Dialog interaction did not complete")
            Thread.sleep(80)
        }
        // Let outgoing accessibility nodes disappear before the next interaction.
        Thread.sleep(280)
    }
}
