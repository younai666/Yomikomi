package io.github.yomikomi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yomikomi.models.ModelCatalog
import io.github.yomikomi.translation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TranslationPromptDeviceTest {
    @Test fun everyModeUsesRealModelAndReturnsOnlyCurrentIdsInOrder() {
        AppRuntime.worker.submit {
            AppRuntime.ensureLoaded(ModelCatalog.translation)
            val previous = PreviousScreen(
                listOf(TranslationSegment("b99", "Captain Alice calls her sword Frost.")),
                listOf(TranslationSegment("b99", "爱丽丝队长称她的剑为霜华。")),
            )
            val segments = listOf(TranslationSegment("b7", "Alice picked up Frost."), TranslationSegment("b12", "She said, 'Let's go.'"))
            val custom = """
                将当前屏翻译成自然中文，结合上一屏理解人物与术语。Alice 固定译为“阿璃”，Frost 固定译为“霜华”。
                上一屏仅供参考：{{previous_screen}}
                当前整屏：{{current_screen}}
                仅按 [ID] → 译文 输出本屏，保持 ID 及顺序。
            """.trimIndent()
            PromptMode.entries.forEach { mode ->
                val result = HyMtTranslationEngine().translate(TranslationRequest("英语", "简体中文", segments,
                    previous, PromptSettings(mode, custom)))
                assertEquals(mode.title, segments.map { it.id }, result.map { it.id })
                assertTrue(mode.title, result.all { it.text.isNotBlank() && it.text.any { char -> char in '\u4e00'..'\u9fff' } })
                assertTrue("${mode.title}: $result", result.first().text.contains("霜华"))
                assertTrue("${mode.title}: $result", result.first().text.contains(if (mode == PromptMode.Custom) "阿璃" else "爱丽丝"))
                assertTrue("${mode.title}: $result", result.first().text.any { it in "拾捡拿" })
                android.util.Log.i("YomikomiPromptTest", "${mode.title}: $result") // Public test fixture only.
            }
        }.get(240, TimeUnit.SECONDS)
    }

    @Test fun promptSettingsPersistWithoutChangingTheLanguageOrOverlaySettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = RuntimeSettings.read(context)
        try {
            val custom = "完整自定义任务\n{{previous_screen}}\n{{current_screen}}"
            val changed = original.copy(prompt = PromptSettings(PromptMode.Custom, custom))
            changed.save(context)
            assertEquals(changed, RuntimeSettings.read(context))
            changed.copy(prompt = PromptSettings(PromptMode.Chat, custom)).save(context)
            assertEquals(PromptSettings(PromptMode.Chat, custom), RuntimeSettings.read(context).prompt)
        } finally { original.save(context) }
    }
}
