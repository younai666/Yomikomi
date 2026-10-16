package io.github.yomikomi

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yomikomi.translation.PromptMode
import io.github.yomikomi.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory settings only: animated dialog disposal and draft lifetime must not regress. */
@RunWith(AndroidJUnit4::class)
class MotionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

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
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun has(label: String) = find(instrumentation.uiAutomation.rootInActiveWindow, label) != null
    private fun click(label: String) {
        waitUntil {
            var node = find(instrumentation.uiAutomation.rootInActiveWindow, label)
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
