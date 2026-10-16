package io.github.yomikomi.capture

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
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
import io.github.yomikomi.translation.ContextReset
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog
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
    private var startedAt = 0L
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
        RunLog.info(LogModule.PERMISSION, "accessibility_connected")
        current = this
        AppRuntime.serviceConnected = true
        TranslationTileService.refresh(this)
    }

    fun toggle() {
        RunLog.info(LogModule.ACTION, "tile_toggle", "busy" to if (busy) 1 else 0, "overlay" to if (overlay.visible) 1 else 0)
        if (busy || overlay.visible) {
            cancel()
            dismissShade()
            return
        }
        val config = RuntimeSettings.read(this)
        val required = if (config.api.enabled) listOf(ModelCatalog.ocr) else ModelCatalog.all
        if (required.any { !it.assets.all { a -> java.io.File(AppRuntime.root, a.path).length() == a.bytes } }) {
            RunLog.warn(LogModule.MODEL, "missing")
            message(if (config.api.enabled) "请先下载 OCR 模型" else "请先下载 OCR 和翻译模型")
            return
        }
        settings = config
        busy = true
        waitingForShade = true
        AppRuntime.pipelinePhase = "截屏中"
        TranslationTileService.refresh(this)
        val token = generation.incrementAndGet()
        startedAt = System.nanoTime()
        RunLog.info(LogModule.CAPTURE, "requested", "request" to token, "api" to if (config.api.enabled) 1 else 0)
        val dismissed = dismissShade()
        RunLog.info(LogModule.CAPTURE, "shade", "dismissed" to if (dismissed) 1 else 0)
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
        val screenshotStart = System.nanoTime()
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
                    finishFailure(token, error.message ?: "截屏失败", error)
                    return
                } finally { buffer.close() }
                RunLog.info(LogModule.CAPTURE, "screenshot", "request" to token,
                    "width" to bitmap.width, "height" to bitmap.height, "elapsed_ms" to RunLog.elapsed(screenshotStart))
                if (token != generation.get()) {
                    RunLog.info(LogModule.CAPTURE, "stale_screenshot", "request" to token)
                    bitmap.recycle(); return
                }
                AppRuntime.worker.execute {
                    try {
                        if (token != generation.get()) return@execute
                        check(bitmap.width == bounds.width() && bitmap.height == bounds.height()) { "屏幕方向已变化，请重试" }
                        AppRuntime.ensureLoaded(ModelCatalog.ocr)
                        phase(token, "识别中")
                        val blocks = AppRuntime.ocr.recognize(bitmap, config.source, area)
                        if (blocks.isEmpty()) translator.clear(ContextReset.EMPTY)
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
                        val render = RunLog.measure(LogModule.OVERLAY, "prepare", "request" to token) { overlayBlocks(screen, bitmap) }
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
                                    RunLog.info(LogModule.CAPTURE, "completed", "request" to token, "blocks" to screen.blocks.size,
                                        "context" to if (screen.contextUsed) 1 else 0, "elapsed_ms" to RunLog.elapsed(startedAt))
                                    TranslationTileService.refresh(this@ScreenCaptureService)
                                } catch (error: Exception) { finishFailure(token, error.message ?: "覆盖失败", error) }
                            }
                        }
                    } catch (error: Exception) { AppRuntime.main.post { finishFailure(token, error.message ?: "翻译失败", error) } }
                    finally { activeEngine = null; bitmap.recycle() }
                }
            }
            override fun onFailure(errorCode: Int) {
                RunLog.warn(LogModule.PERMISSION, "screenshot_failed", null, "code" to errorCode,
                    "request" to token, "elapsed_ms" to RunLog.elapsed(screenshotStart))
                finishFailure(token, when (errorCode) {
                    ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "受保护的页面无法截屏"
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截屏间隔过短，请稍后重试"
                    else -> "截屏失败（$errorCode）"
                })
            }
        }) } catch (error: SecurityException) {
            RunLog.error(LogModule.PERMISSION, "screenshot_denied", error)
            finishFailure(token, "截屏权限已失效，请重新启用服务", error)
        }
    }

    private fun phase(token: Long, text: String) { AppRuntime.main.post {
        if (token == generation.get()) { AppRuntime.pipelinePhase = text; TranslationTileService.refresh(this) }
    } }

    private fun dismissShade() = if (android.os.Build.VERSION.SDK_INT >= 31)
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) else performGlobalAction(GLOBAL_ACTION_BACK)

    private fun finishFailure(token: Long, error: String, cause: Throwable? = null) {
        RunLog.warn(LogModule.CAPTURE, "failed", cause, "request" to token, "elapsed_ms" to RunLog.elapsed(startedAt),
            "stale" to if (token == generation.get()) 0 else 1)
        if (token != generation.get()) return
        busy = false
        waitingForShade = false
        AppRuntime.pipelinePhase = "就绪"
        message(error)
        TranslationTileService.refresh(this)
    }

    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // Overlay window events use our package too; the activity lifecycle distinguishes opening the app.
    fun onAppForegrounded() {
        cancel()
        translator.clear(ContextReset.FOREGROUND)
        targetPackage = packageName
        targetWindowId = -1
    }

    private fun cancel() {
        if (busy || overlay.visible) RunLog.info(LogModule.CAPTURE, "cancelled", "request" to generation.get(),
            "elapsed_ms" to RunLog.elapsed(startedAt))
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
        if (switched) { cancel(); translator.clear(ContextReset.APP_SWITCH); return }
        if (waitingForShade) return
        val changed = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT != 0)
        if (windowChanged || (changed && pkg == targetPackage)) {
            if (busy || (overlay.visible && (windowChanged || settings?.autoCollapse != false))) cancel()
        }
    }

    override fun onInterrupt() { RunLog.warn(LogModule.PERMISSION, "accessibility_interrupted"); cancel() }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        RunLog.info(LogModule.CAPTURE, "configuration_changed")
        super.onConfigurationChanged(newConfig); cancel()
    }
    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(configurationListener)
        cancel()
        translator.clear(ContextReset.SERVICE_STOP)
        RunLog.info(LogModule.PERMISSION, "accessibility_disconnected")
        current = null
        AppRuntime.serviceConnected = false
        super.onDestroy()
    }

    companion object {
        @Volatile var current: ScreenCaptureService? = null
            private set
    }
}
