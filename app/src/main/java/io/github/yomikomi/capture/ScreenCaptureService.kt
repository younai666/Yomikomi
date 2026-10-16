package io.github.yomikomi.capture

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.RequiresApi
import io.github.yomikomi.AppRuntime
import io.github.yomikomi.RuntimeSettings
import io.github.yomikomi.models.ModelCatalog
import io.github.yomikomi.translation.HyMtTranslationEngine
import io.github.yomikomi.translation.ScreenTranslator
import io.github.yomikomi.translation.TranslationEngine
import io.github.yomikomi.translation.ApiTranslationEngine
import io.github.yomikomi.translation.ApiSecretStore
import io.github.yomikomi.translation.ContextConfig
import java.util.concurrent.atomic.AtomicLong

@RequiresApi(30)
class ScreenCaptureService : AccessibilityService() {
    private lateinit var overlay: TranslationOverlay
    @Volatile private var activeEngine: TranslationEngine? = null
    private val translator = ScreenTranslator { request -> checkNotNull(activeEngine).translate(request) }
    private val generation = AtomicLong()
    private var targetPackage = ""
    private var targetWindowId = -1
    private var busy = false
    private var waitingForShade = false
    private var settings: RuntimeSettings? = null
    private lateinit var preferences: SharedPreferences
    private lateinit var observedConfig: ContextConfig
    private val configurationListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val updated = RuntimeSettings.read(this).contextConfig
        if (!observedConfig.matches(updated)) {
            cancel()
            translator.configure(updated)
        }
        observedConfig = updated
    }

    override fun onCreate() {
        super.onCreate()
        overlay = TranslationOverlay(this)
        preferences = getSharedPreferences("settings", MODE_PRIVATE)
        observedConfig = RuntimeSettings.read(this).contextConfig
        translator.configure(observedConfig)
        preferences.registerOnSharedPreferenceChangeListener(configurationListener)
    }

    override fun onServiceConnected() {
        current = this
        AppRuntime.serviceConnected = true
        TranslationTileService.refresh(this)
    }

    fun toggle() {
        if (busy || overlay.visible) {
            cancel()
            dismissShade()
            return
        }
        val config = RuntimeSettings.read(this)
        val required = if (config.api.enabled) listOf(ModelCatalog.ocr) else ModelCatalog.all
        if (required.any { !it.assets.all { a -> java.io.File(AppRuntime.root, a.path).length() == a.bytes } }) {
            message(if (config.api.enabled) "请先下载 OCR 模型" else "请先下载 OCR 和翻译模型")
            return
        }
        settings = config
        busy = true
        waitingForShade = true
        AppRuntime.pipelinePhase = "截屏中"
        TranslationTileService.refresh(this)
        val token = generation.incrementAndGet()
        val dismissed = dismissShade()
        Log.d(TAG, "dismiss shade=$dismissed")
        if (!dismissed) { finishFailure(token, "无法收起快捷设置，请重试"); return }
        // The shade must finish its close animation before capture; never photograph our own QS tile.
        AppRuntime.main.postDelayed({
            if (token == generation.get()) {
                waitingForShade = false
                capture(token, config, targetPackage)
            }
        }, 500)
    }

    private fun capture(token: Long, config: RuntimeSettings, scope: String) {
        val contextConfig = config.contextConfig
        val metrics = getSystemService(WindowManager::class.java).currentWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val area = Rect(insets.left, insets.top, bounds.width() - insets.right, bounds.height() - insets.bottom)
        try { takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val capturedAt = SystemClock.elapsedRealtime()
                val buffer = result.hardwareBuffer
                val bitmap = try {
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("截屏缓冲区不可读")
                    try { hardware.copy(Bitmap.Config.ARGB_8888, false) ?: error("截屏转换失败") }
                    finally { hardware.recycle() }
                } catch (error: Exception) {
                    finishFailure(token, error.message ?: "截屏失败")
                    return
                } finally { buffer.close() }
                if (token != generation.get()) { bitmap.recycle(); return }
                AppRuntime.worker.execute {
                    try {
                        if (token != generation.get()) return@execute
                        check(bitmap.width == bounds.width() && bitmap.height == bounds.height()) { "屏幕方向已变化，请重试" }
                        Log.d(TAG, "capture ${bitmap.width}x${bitmap.height} area=$area source=${config.source}")
                        AppRuntime.ensureLoaded(ModelCatalog.ocr)
                        phase(token, "识别中")
                        val blocks = AppRuntime.ocr.recognize(bitmap, config.source, area)
                        if (blocks.isEmpty()) translator.clear()
                        check(blocks.isNotEmpty()) { "未识别到文字" }
                        val appearance = screenAppearance(bitmap, area, blocks)
                        if (token != generation.get()) return@execute
                        if (!config.api.enabled) AppRuntime.ensureLoaded(ModelCatalog.translation)
                        if (token != generation.get()) return@execute
                        activeEngine = if (config.api.enabled) ApiTranslationEngine(config.api, { ApiSecretStore(this@ScreenCaptureService).read(config.api.endpoint) })
                            else HyMtTranslationEngine()
                        if (token != generation.get()) return@execute
                        phase(token, "翻译中")
                        val screen = translator.translate(blocks, config.source, config.target, scope, capturedAt,
                            contextConfig.prompt, appearance, contextConfig.backend)
                        val render = overlayBlocks(screen, bitmap)
                        AppRuntime.main.post {
                            if (token == generation.get()) {
                                try {
                                    overlay.show(render, config)
                                    translator.accept(screen, scope, SystemClock.elapsedRealtime())
                                    AppRuntime.main.postDelayed({
                                        translator.expire(SystemClock.elapsedRealtime())
                                    }, ScreenTranslator.CONTEXT_TTL_MS)
                                    busy = false
                                    AppRuntime.pipelinePhase = "已覆盖"
                                    Log.i(TAG, "overlay engine=${if (config.api.enabled) "api" else "local"} context=${screen.contextUsed} blocks=${screen.blocks.size} ids=${screen.blocks.joinToString { it.id }}")
                                    TranslationTileService.refresh(this@ScreenCaptureService)
                                } catch (error: Exception) { finishFailure(token, error.message ?: "覆盖失败") }
                            }
                        }
                    } catch (error: Exception) { AppRuntime.main.post { finishFailure(token, error.message ?: "翻译失败") } }
                    finally { activeEngine = null; bitmap.recycle() }
                }
            }
            override fun onFailure(errorCode: Int) {
                finishFailure(token, when (errorCode) {
                    ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "受保护的页面无法截屏"
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截屏间隔过短，请稍后重试"
                    else -> "截屏失败（$errorCode）"
                })
            }
        }) } catch (error: SecurityException) { finishFailure(token, "截屏权限已失效，请重新启用服务") }
    }

    private fun phase(token: Long, text: String) { AppRuntime.main.post {
        if (token == generation.get()) { AppRuntime.pipelinePhase = text; TranslationTileService.refresh(this) }
    } }

    private fun dismissShade() = if (android.os.Build.VERSION.SDK_INT >= 31)
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) else performGlobalAction(GLOBAL_ACTION_BACK)

    private fun finishFailure(token: Long, error: String) {
        if (token != generation.get()) return
        busy = false
        waitingForShade = false
        AppRuntime.pipelinePhase = "就绪"
        Log.w(TAG, error)
        message(error)
        TranslationTileService.refresh(this)
    }

    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // Overlay window events use our package too; the activity lifecycle distinguishes opening the app.
    fun onAppForegrounded() {
        cancel()
        translator.clear()
        targetPackage = packageName
        targetWindowId = -1
    }

    private fun cancel() {
        generation.incrementAndGet()
        activeEngine?.cancel()
        busy = false
        waitingForShade = false
        overlay.hide()
        AppRuntime.pipelinePhase = "就绪"
        TranslationTileService.refresh(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == packageName || pkg == "com.android.systemui") return
        val windowChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.windowId != targetWindowId
        val switched = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && targetPackage.isNotEmpty() && pkg != targetPackage
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            targetPackage = pkg
            targetWindowId = event.windowId
        }
        // App switches invalidate immediately, even while waiting for the notification shade to close.
        if (switched) { cancel(); translator.clear(); return }
        if (waitingForShade) return
        val changed = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT != 0)
        if (windowChanged || (changed && pkg == targetPackage)) {
            if (busy || (overlay.visible && (windowChanged || settings?.autoCollapse != false))) cancel()
        }
    }

    override fun onInterrupt() { cancel() }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) { super.onConfigurationChanged(newConfig); cancel() }
    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(configurationListener)
        cancel()
        translator.clear()
        current = null
        AppRuntime.serviceConnected = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "YomikomiPipeline"
        @Volatile var current: ScreenCaptureService? = null
            private set
    }
}
