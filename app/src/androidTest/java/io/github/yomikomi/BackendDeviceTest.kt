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
class BackendDeviceTest {
    @Test fun gpuPreferenceSelectsVulkanOrExplicitCpuFallbackAndCanSwitchBack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = RuntimeSettings.read(context)
        try {
            original.copy(gpuAcceleration = true).save(context)
            assertTrue(RuntimeSettings.read(context).gpuAcceleration)
            AppRuntime.worker.submit {
                val segments = listOf(TranslationSegment("b1", "Hello."))
                fun translate() = HyMtTranslationEngine().translate(TranslationRequest("英语", "简体中文", segments, null))
                AppRuntime.ensureLoaded(ModelCatalog.translation, gpuAcceleration = true)
                val gpu = NativeLlama.usesVulkan
                if (InstrumentationRegistry.getArguments().getString("requireVulkan") == "true") {
                    assertTrue("Vulkan offload required on this device", gpu)
                }
                val diagnostics = runtimeLogSnapshot().lineSequence().last { "INFERENCE backend " in it }
                assertTrue(diagnostics.contains("gpu_requested=1"))
                val layers = Regex("offload_layers=(\\d+)").find(diagnostics)!!.groupValues[1].toInt()
                assertEquals(gpu, layers > 0)
                if (!gpu) assertFalse(diagnostics.contains("fallback_reason=0 "))
                assertTrue(translate().single().text.any { it in '\u4e00'..'\u9fff' })
                AppRuntime.ensureLoaded(ModelCatalog.translation, gpuAcceleration = false)
                assertFalse(NativeLlama.usesVulkan)
                assertTrue(translate().single().text.any { it in '\u4e00'..'\u9fff' })
                val cpu = runtimeLogSnapshot().lineSequence().last { "INFERENCE backend " in it }
                assertTrue(cpu.contains("backend=cpu"))
                assertTrue(cpu.contains("offload_layers=0"))
                assertTrue(cpu.contains("gpu_requested=0"))
                assertTrue(runtimeLogSnapshot().contains("decode_tok_s="))
            }.get(240, TimeUnit.SECONDS)
        } finally { original.save(context) }
    }
}
