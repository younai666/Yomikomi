package io.github.yomikomi.translation

import org.junit.Assert.*
import org.junit.Test
import io.github.yomikomi.RuntimeSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ContextBoundaryTest {
    private val viewport = PixelBounds(0, 0, 600, 800)
    private fun page(vararg text: String) = text.mapIndexed { index, line ->
        RecognizedBlock(line, PixelBounds(20, 180 + index * 80, 580, 220 + index * 80))
    }
    private fun appearance(pattern: Int = 0, delta: Int = 0) = ScreenAppearance.sample(viewport) { x, y ->
        val light = if (((x / 50 + y / 50 + pattern) % 2) == 0) 40 + delta else 200 + delta
        (light shl 16) or (light shl 8) or light
    }
    private val first = page("Alice carried Frost through the forest.", "She stopped beside the river.")
    private val following = page("She stopped beside the river.", "Alice lifted Frost and looked across.")
    private val unrelated = page("Your order has shipped.", "Payment confirmed. Delivery tomorrow.")
    private class Recorder {
        val requests = mutableListOf<TranslationRequest>()
        val translator = ScreenTranslator { r -> requests += r; r.segments.map { it.copy(text = "译文 ${it.text}") } }
    }
    private fun Recorder.seed(input: List<RecognizedBlock> = first, image: ScreenAppearance? = null,
                              prompt: PromptSettings = PromptSettings(), backend: String = "local") {
        val screen = translator.translate(input, "英语", "中文", "reader", 0, prompt, image, backend)
        assertTrue(translator.accept(screen, "reader", 0))
    }

    @Test fun scrollingKeepsUsefulContextAndOnlyDisplayedPagesCanAdvanceIt() {
        val r = Recorder(); r.seed()
        val continued = r.translator.translate(following.map { it.copy(bounds = it.bounds.copy(top = it.bounds.top + 5, bottom = it.bounds.bottom + 5)) },
            "英语", "中文", "reader", 100)
        assertTrue(continued.contextUsed)
        assertEquals(first.map { it.text }, r.requests.last().previousScreen!!.segments.map { it.text })
        r.translator.translate(first, "英语", "中文", "reader", 200)
        assertEquals(first.map { it.text }, r.requests.last().previousScreen!!.segments.map { it.text })
    }

    @Test fun japaneseNoSpacesAndRussianCasePunctuationStillMatch() {
        listOf(
            page("アリスは霜華を手にした。", "川の向こうを見ている。") to page("アリスは霜華を手にした！", "彼女は向こう岸へ歩き出す。"),
            page("Алиса подняла меч.", "Она подошла к реке.") to page("АЛИСА подняла меч!", "Она посмотрела на воду."),
        ).forEach { (before, after) ->
            val r = Recorder(); r.seed(before)
            r.translator.translate(after, "英语", "中文", "reader", 100)
            assertNotNull(r.requests.last().previousScreen)
        }
    }

    @Test fun oneRepeatedToolbarDoesNotMakeUnrelatedPagesContinuous() {
        val chrome = RecognizedBlock("Library Reader Account Settings", PixelBounds(20, 10, 580, 60))
        val flat = ScreenAppearance.sample(viewport) { _, _ -> 0xf9f7f2 }
        val r = Recorder(); r.seed(listOf(chrome) + first, flat)
        r.translator.translate(listOf(chrome) + unrelated, "英语", "中文", "reader", 100, appearance = flat)
        assertNull(r.requests.last().previousScreen)
    }

    @Test fun flatBackgroundCannotCarryContextAcrossDifferentText() {
        listOf(0xffffff, 0x232321).forEach { color ->
            val flat = ScreenAppearance.sample(viewport) { _, _ -> color }
            val r = Recorder(); r.seed(image = flat)
            r.translator.translate(unrelated, "英语", "中文", "reader", 100, appearance = flat)
            assertNull(r.requests.last().previousScreen)
        }
    }

    @Test fun stableDetailedSceneKeepsContextForEntirelyNewDialogue() {
        val r = Recorder(); r.seed(image = appearance())
        r.translator.translate(page("Don't move!", "Who are you?"), "英语", "中文", "reader", 100, appearance = appearance(delta = 3))
        assertNotNull(r.requests.last().previousScreen)
    }

    @Test fun majorImageChangeOrAspectChangeBreaksVisualContinuity() {
        listOf(appearance(pattern = 1), ScreenAppearance.sample(PixelBounds(0, 0, 800, 600)) { x, y ->
            if ((x / 50 + y / 50) % 2 == 0) 0x282828 else 0xc8c8c8
        }).forEach { changed ->
            val r = Recorder(); r.seed(image = appearance())
            r.translator.translate(unrelated, "英语", "中文", "reader", 100, appearance = changed)
            assertNull(r.requests.last().previousScreen)
        }
    }

    @Test fun missingOrHeavilyMaskedImageCannotCreateVisualContinuity() {
        val masked = ScreenAppearance.sample(viewport) { _, _ -> null }
        listOf(null, masked).forEach { image ->
            val r = Recorder(); r.seed(image = appearance())
            r.translator.translate(unrelated, "英语", "中文", "reader", 100, appearance = image)
            assertNull(r.requests.last().previousScreen)
        }
    }

    @Test fun appSwitchAndReturnDoNotReviveOldContext() {
        val r = Recorder(); r.seed()
        r.translator.translate(first, "英语", "中文", "browser", 100)
        assertNull(r.requests.last().previousScreen)
        r.translator.translate(first, "英语", "中文", "reader", 200)
        assertNull(r.requests.last().previousScreen)
    }

    @Test fun unrelatedFailedPageErasesOldContextBeforeCallingEngine() {
        var fail = false
        var current: TranslationRequest? = null
        val translator = ScreenTranslator { r -> current = r; check(!fail); r.segments }
        translator.accept(translator.translate(first, "英语", "中文", "reader", 0), "reader", 0)
        fail = true
        assertThrows(IllegalStateException::class.java) { translator.translate(unrelated, "英语", "中文", "reader", 100) }
        assertNull(current!!.previousScreen)
        fail = false
        translator.translate(first, "英语", "中文", "reader", 200)
        assertNull(current!!.previousScreen)
    }

    @Test fun everyLanguagePromptAndBackendBoundaryClearsEvenWhenChangedBackWithoutTranslation() {
        val original = ContextConfig("英语", "中文", PromptSettings())
        listOf(original.copy(source = "日语"), original.copy(target = "俄语"), original.copy(prompt = PromptSettings(PromptMode.Chat)),
            original.copy(backend = "api:deepseek")).forEach { changed ->
            val r = Recorder(); r.seed()
            r.translator.configure(changed)
            r.translator.configure(original)
            r.translator.translate(first, "英语", "中文", "reader", 100)
            assertNull("$changed", r.requests.last().previousScreen)
        }
    }

    @Test fun customEditsInvalidateButInactiveSavedDraftDoesNot() {
        val custom = PromptSettings(PromptMode.Custom, "original")
        val r = Recorder(); r.seed(prompt = custom)
        r.translator.configure(ContextConfig("英语", "中文", custom.copy(customTemplate = "changed")))
        r.translator.configure(ContextConfig("英语", "中文", custom))
        r.translator.translate(first, "英语", "中文", "reader", 100, custom)
        assertNull(r.requests.last().previousScreen)
        val preset = Recorder(); preset.seed()
        preset.translator.configure(ContextConfig("英语", "中文", PromptSettings(customTemplate = "inactive edit")))
        preset.translator.translate(first, "英语", "中文", "reader", 100)
        assertNotNull(preset.requests.last().previousScreen)
    }

    @Test fun runtimeConfigUsesActiveEnginePromptAndIgnoresOverlayPreferences() {
        val local = RuntimeSettings("英语", "中文", 1f, 16, true)
        assertTrue(local.contextConfig.matches(local.copy(opacity = 0.5f, textSize = 20).contextConfig))
        assertTrue(local.contextConfig.matches(local.copy(api = ApiSettings(prompt = PromptSettings(PromptMode.Manga))).contextConfig))
        val cloud = local.copy(api = ApiSettings(enabled = true))
        assertFalse(local.contextConfig.matches(cloud.contextConfig))
        assertTrue(cloud.contextConfig.matches(cloud.copy(prompt = PromptSettings(PromptMode.Formal)).contextConfig))
        assertFalse(cloud.contextConfig.matches(cloud.copy(api = cloud.api.copy(prompt = PromptSettings(PromptMode.Formal))).contextConfig))
        assertFalse(cloud.contextConfig.matches(cloud.copy(api = cloud.api.copy(model = "other-model")).contextConfig))
        assertFalse(cloud.contextConfig.matches(cloud.copy(api = cloud.api.copy(endpoint = "https://another.example")).contextConfig))
    }

    @Test fun exactTimeoutAndClockRollbackClearAndCannotResurrectExpiredResult() {
        listOf(ScreenTranslator.CONTEXT_TTL_MS, -1L).forEach { time ->
            val r = Recorder(); r.seed()
            r.translator.translate(first, "英语", "中文", "reader", time)
            assertNull(r.requests.last().previousScreen)
            r.translator.translate(first, "英语", "中文", "reader", 10)
            assertNull(r.requests.last().previousScreen)
        }
        val r = Recorder()
        val screen = r.translator.translate(first, "英语", "中文", "reader", 0)
        assertTrue(r.translator.accept(screen, "reader", 0))
        r.translator.expire(ScreenTranslator.CONTEXT_TTL_MS)
        assertFalse(r.translator.accept(screen, "reader", ScreenTranslator.CONTEXT_TTL_MS))
        r.translator.translate(first, "英语", "中文", "reader", ScreenTranslator.CONTEXT_TTL_MS + 1)
        assertNull(r.requests.last().previousScreen)
    }

    @Test fun emptyPageAndUnknownPackageNeverCarryContext() {
        val r = Recorder(); r.seed(image = appearance())
        r.translator.translate(emptyList(), "英语", "中文", "reader", 100, appearance = appearance())
        r.translator.translate(first, "英语", "中文", "reader", 200)
        assertNull(r.requests.last().previousScreen)
        val screen = r.translator.translate(first, "英语", "中文", "", 300)
        assertFalse(r.translator.accept(screen, "", 300))
        r.translator.translate(first, "英语", "中文", "", 400)
        assertNull(r.requests.last().previousScreen)
    }

    @Test fun clearingDuringSlowInferenceDoesNotBlockAndLateResultCannotRestoreContext() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val translator = ScreenTranslator { r -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); r.segments }
        try {
            val pending = pool.submit<TranslatedScreen> { translator.translate(first, "英语", "中文", "reader", 0) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            pool.submit { translator.clear() }.get(1, TimeUnit.SECONDS)
            release.countDown()
            assertFalse(translator.accept(pending.get(2, TimeUnit.SECONDS), "reader", 10))
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun expiryOfOldCacheDoesNotInvalidateNewTranslationInProgress() {
        val r = Recorder(); r.seed()
        val next = r.translator.translate(following, "英语", "中文", "reader", ScreenTranslator.CONTEXT_TTL_MS - 1)
        assertNotNull(r.requests.last().previousScreen)
        r.translator.expire(ScreenTranslator.CONTEXT_TTL_MS)
        assertTrue(r.translator.accept(next, "reader", ScreenTranslator.CONTEXT_TTL_MS + 10))
        r.translator.translate(following, "英语", "中文", "reader", ScreenTranslator.CONTEXT_TTL_MS + 11)
        assertNotNull(r.requests.last().previousScreen)
    }
}
