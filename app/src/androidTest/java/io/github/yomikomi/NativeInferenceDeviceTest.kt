package io.github.yomikomi

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yomikomi.models.ModelCatalog
import io.github.yomikomi.translation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeInferenceDeviceTest {
    private val segments = listOf(TranslationSegment("b1", "Hello."))
    private val grammar = TranslationProtocol.grammar(segments)

    @Test fun cancellationBeforeEntryAndDuringPrefillCannotBeResetByGenerate() {
        AppRuntime.worker.submit { AppRuntime.ensureLoaded(ModelCatalog.translation) }.get(60, TimeUnit.SECONDS)
        val engine = HyMtTranslationEngine()
        engine.cancel()
        AppRuntime.worker.submit {
            assertThrows(IllegalStateException::class.java) {
                engine.translate(TranslationRequest("英语", "简体中文", segments, null))
            }
        }.get(5, TimeUnit.SECONDS)
        val entered = CountDownLatch(1)
        val active = HyMtTranslationEngine()
        val pending = AppRuntime.worker.submit {
            entered.countDown()
            active.translate(TranslationRequest("英语", "简体中文", segments, null))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        Thread.sleep(150)
        active.cancel()
        assertThrows(ExecutionException::class.java) { pending.get(5, TimeUnit.SECONDS) }
    }

    @Test fun prefixReuseRecomputesLastTokenAndMatchesFreshInferenceAfterPromptChanges() {
        AppRuntime.worker.submit {
            AppRuntime.ensureLoaded(ModelCatalog.translation)
            val version = NativeLlama.cancellationVersion()
            fun translate(text: String, reuse: Boolean) = NativeLlama.generate(
                "Translate into Simplified Chinese. Only output [b1] → translation.\n$text", grammar, version, reuse)
            val first = translate("Hello.", false)
            assertEquals(first, translate("Hello.", true))
            val log = runtimeLogSnapshot()
            assertTrue(Regex("INFERENCE prefix_reuse elapsed_ms=\\d+ tokens=(\\d+)").findAll(log).last().groupValues[1].toInt() > 0)
            assertEquals(1, Regex("INFERENCE prefill elapsed_ms=\\d+ tokens=(\\d+)").findAll(log).last().groupValues[1].toInt())
            val changed = translate("Goodbye.", true)
            assertEquals(changed, translate("Goodbye.", false))
            assertTrue(TranslationProtocol.parse(changed, segments).single().text.any { it in '\u4e00'..'\u9fff' })
        }.get(90, TimeUnit.SECONDS)
    }

    @Test fun freshRequestsAndFailedInferenceDiscardCachedPrefixes() {
        AppRuntime.worker.submit {
            AppRuntime.ensureLoaded(ModelCatalog.translation)
            val version = NativeLlama.cancellationVersion()
            val prompt = "Translate into Simplified Chinese. Only output [b1] → translation.\nHello."
            fun generate(reuse: Boolean) = NativeLlama.generate(prompt, grammar, version, reuse)
            fun reusedTokens() = Regex("INFERENCE prefix_reuse elapsed_ms=\\d+ tokens=(\\d+)")
                .findAll(runtimeLogSnapshot()).last().groupValues[1].toInt()
            val expected = generate(false)
            assertEquals(0, reusedTokens())
            assertEquals(expected, generate(true))
            assertTrue(reusedTokens() > 0)
            assertEquals(expected, generate(false))
            assertEquals(0, reusedTokens())
            assertThrows(IllegalStateException::class.java) {
                NativeLlama.generate(prompt, "root ::= missing-rule", version, true)
            }
            assertEquals(expected, generate(true))
            assertEquals(0, reusedTokens())
        }.get(60, TimeUnit.SECONDS)
    }

}
