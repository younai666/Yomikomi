package io.github.yomikomi.capture

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
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
import io.github.yomikomi.translation.RecognizedBlock
import io.github.yomikomi.translation.TranslatedScreen
import io.github.yomikomi.translation.ScreenAppearance
import io.github.yomikomi.translation.PixelBounds
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog
import java.util.concurrent.atomic.AtomicLong

@RequiresApi(30)
class ScreenCaptureService : AccessibilityService() {
    private lateinit var overlay: TranslationOverlay
    @Volatile private var activeEngine: TranslationEngine? = null
    private val translator = ScreenTranslator { request -> checkNotNull(activeEngine).translate(request) }
    private val generation = AtomicLong()
    private val expireContext = Runnable { translator.expire(SystemClock.elapsedRealtime()) }
    private var targetPackage = ""
    private var targetWindowId = -1
    private var busy = false
    private var waitingForShade = false
    private var startedAt = 0L
    private var stage = 0 // 0 idle, 1 shade, 2 capture, 3 OCR, 4 model, 5 inference, 6 validation, 7 overlay.
    private var lastActivity: ComponentName? = null
    private var deferredEvents = 0
    private lateinit var displayBounds: Rect
    private var displayRotation = 0
    private var settings: RuntimeSettings? = null
    private lateinit var preferences: SharedPreferences
    private lateinit var observedConfig: ContextConfig
    private var observedGpuAcceleration = false
    private val configurationListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val updatedSettings = RuntimeSettings.read(this)
        val updated = updatedSettings.contextConfig
        if (!observedConfig.matches(updated) || observedGpuAcceleration != updatedSettings.gpuAcceleration) {
            cancel(CaptureCancelReason.CONFIG_CHANGED)
            translator.configure(updated)
        }
        observedConfig = updated
        observedGpuAcceleration = updatedSettings.gpuAcceleration
    }

    override fun onCreate() {
        super.onCreate()
        overlay = TranslationOverlay(this)
        displayBounds = Rect(getSystemService(WindowManager::class.java).currentWindowMetrics.bounds)
        displayRotation = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation
        preferences = getSharedPreferences("settings", MODE_PRIVATE)
        observedConfig = RuntimeSettings.read(this).contextConfig
        observedGpuAcceleration = RuntimeSettings.read(this).gpuAcceleration
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
            cancel(CaptureCancelReason.TILE)
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
        stage = 1
        deferredEvents = 0
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
        stage = 2
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
                AppRuntime.worker.execute {
                    try {
                        val bitmap = try {
                            if (token != generation.get()) return@execute
                            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("截屏缓冲区不可读")
                            try { hardware.copy(Bitmap.Config.ARGB_8888, false) ?: error("截屏转换失败") }
                            finally { hardware.recycle() }
                        } finally { buffer.close() }
                        val blocks: List<RecognizedBlock>
                        val appearance: ScreenAppearance
                        val fingerprint: ScreenshotFingerprint
                        val backgrounds: Map<PixelBounds, Int>
                        try {
                            RunLog.info(LogModule.CAPTURE, "screenshot", "request" to token,
                                "width" to bitmap.width, "height" to bitmap.height, "elapsed_ms" to RunLog.elapsed(screenshotStart))
                            if (token != generation.get()) return@execute
                            check(bitmap.width == bounds.width() && bitmap.height == bounds.height()) { "屏幕方向已变化，请重试" }
                            AppRuntime.ensureLoaded(ModelCatalog.ocr, config.source)
                            phase(token, "识别中", 3)
                            blocks = AppRuntime.ocr.recognize(bitmap, config.source, area)
                            if (blocks.isEmpty()) translator.clear(ContextReset.EMPTY)
                            check(blocks.isNotEmpty()) { "未识别到文字" }
                            appearance = screenAppearance(bitmap, area, blocks)
                            backgrounds = overlayBackgrounds(bitmap, blocks)
                            fingerprint = ScreenshotFingerprint.sample(PixelBounds(area.left, area.top, area.right, area.bottom), bitmap::getPixel)
                        } finally { bitmap.recycle() }
                        // Keep only text, bounds and sampled colors during model loading/network inference.
                        if (token != generation.get()) return@execute
                        if (!config.api.enabled) {
                            phase(token, "加载模型中", 4)
                            AppRuntime.ensureLoaded(ModelCatalog.translation, gpuAcceleration = config.gpuAcceleration)
                        }
                        if (token != generation.get()) return@execute
                        activeEngine = if (config.api.enabled) ApiTranslationEngine(config.api, { ApiSecretStore(this@ScreenCaptureService).read(config.api.endpoint) })
                            else HyMtTranslationEngine()
                        if (token != generation.get()) return@execute
                        phase(token, "翻译中", 5)
                        val screen = translator.translate(blocks, config.source, config.target, scope, capturedAt,
                            contextConfig.prompt, appearance, contextConfig.backend)
                        val render = RunLog.measure(LogModule.OVERLAY, "prepare", "request" to token) {
                            overlayBlocks(screen, backgrounds, config.textSize * resources.displayMetrics.scaledDensity)
                        }
                        AppRuntime.main.post { if (token == generation.get()) validateScreen(token, config, scope,
                            bounds, fingerprint, screen, render) }
                    } catch (error: Exception) { AppRuntime.main.post { finishFailure(token, error.message ?: "翻译失败", error) } }
                    finally { activeEngine = null }
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

    private fun phase(token: Long, text: String, currentStage: Int) { AppRuntime.main.post {
        if (token == generation.get()) { stage = currentStage; AppRuntime.pipelinePhase = text; TranslationTileService.refresh(this) }
    } }

    private fun validateScreen(token: Long, config: RuntimeSettings, scope: String, bounds: Rect,
        fingerprint: ScreenshotFingerprint, screen: TranslatedScreen, render: List<OverlayBlock>) {
        stage = 6
        AppRuntime.pipelinePhase = "核对页面中"
        TranslationTileService.refresh(this)
        val started = System.nanoTime()
        try { takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                AppRuntime.worker.execute {
                    try {
                        val bitmap = try {
                            if (token != generation.get()) return@execute
                            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("截屏缓冲区不可读")
                            try { hardware.copy(Bitmap.Config.ARGB_8888, false) ?: error("截屏转换失败") }
                            finally { hardware.recycle() }
                        } finally { buffer.close() }
                        val change = try {
                            if (bitmap.width != bounds.width() || bitmap.height != bounds.height())
                                ScreenshotFingerprint.Change(0, 0, 0, true)
                            else fingerprint.compare(ScreenshotFingerprint.sample(fingerprint.viewport, bitmap::getPixel))
                        } finally { bitmap.recycle() }
                        AppRuntime.main.post {
                            if (token != generation.get()) return@post
                            RunLog.info(LogModule.CAPTURE, "validation", "request" to token,
                                "unchanged" to if (change.changed) 0 else 1, "changed_cells" to change.changedCells,
                                "changed_rows" to change.changedRows, "total_cells" to change.totalCells, "deferred_events" to deferredEvents,
                                "elapsed_ms" to RunLog.elapsed(started))
                            if (change.changed) {
                                cancel(CaptureCancelReason.PAGE_CHANGED)
                                translator.clear(ContextReset.MANUAL)
                                message("页面已变化，请重新点按磁贴")
                            } else try {
                                stage = 7
                                overlay.show(render, config)
                                translator.accept(screen, scope, SystemClock.elapsedRealtime())
                                AppRuntime.main.removeCallbacks(expireContext)
                                AppRuntime.main.postDelayed(expireContext, ScreenTranslator.CONTEXT_TTL_MS)
                                busy = false
                                AppRuntime.pipelinePhase = "已覆盖"
                                RunLog.info(LogModule.CAPTURE, "completed", "request" to token, "blocks" to screen.blocks.size,
                                    "context" to if (screen.contextUsed) 1 else 0, "elapsed_ms" to RunLog.elapsed(startedAt))
                                TranslationTileService.refresh(this@ScreenCaptureService)
                            } catch (error: Exception) { finishFailure(token, error.message ?: "覆盖失败", error) }
                        }
                    } catch (error: Exception) { AppRuntime.main.post { finishFailure(token, "无法核对当前页面，请重试", error) } }
                }
            }
            override fun onFailure(errorCode: Int) {
                RunLog.warn(LogModule.CAPTURE, "validation_failed", null, "request" to token, "code" to errorCode)
                finishFailure(token, "无法核对当前页面，请重试")
            }
        }) } catch (error: SecurityException) { finishFailure(token, "截屏权限已失效，请重新启用服务", error) }
    }

    private fun dismissShade() = if (android.os.Build.VERSION.SDK_INT >= 31)
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) else performGlobalAction(GLOBAL_ACTION_BACK)

    private fun finishFailure(token: Long, error: String, cause: Throwable? = null) {
        if (token != generation.get()) {
            RunLog.info(LogModule.CAPTURE, "stale_result_discarded", "request" to token)
            return
        }
        RunLog.warn(LogModule.CAPTURE, "failed", cause, "request" to token, "stage" to stage,
            "elapsed_ms" to RunLog.elapsed(startedAt))
        busy = false
        waitingForShade = false
        stage = 0
        AppRuntime.pipelinePhase = "就绪"
        message(error)
        TranslationTileService.refresh(this)
    }

    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // Overlay window events use our package too; the activity lifecycle distinguishes opening the app.
    fun onAppForegrounded() {
        cancel(CaptureCancelReason.APP_FOREGROUND)
        translator.clear(ContextReset.FOREGROUND)
        targetPackage = packageName
        targetWindowId = -1
    }

    private fun cancel(reason: CaptureCancelReason, event: AccessibilityEvent? = null) {
        if (busy || overlay.visible) RunLog.info(LogModule.CAPTURE, "cancelled.${reason.key}", "request" to generation.get(),
            "stage" to stage, "event_type" to (event?.eventType ?: 0), "window" to (event?.windowId ?: -1),
            "change_types" to (event?.contentChangeTypes ?: 0), "elapsed_ms" to RunLog.elapsed(startedAt))
        generation.incrementAndGet()
        activeEngine?.cancel()
        busy = false
        waitingForShade = false
        stage = 0
        overlay.hide()
        AppRuntime.pipelinePhase = "就绪"
        TranslationTileService.refresh(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == packageName || pkg == "com.android.systemui") return
        val activityWindow = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && isActivityWindow(pkg, event)
        val reason = windowCancellation(targetPackage, targetWindowId, pkg, event.windowId, activityWindow, busy)
        if (activityWindow) {
            targetPackage = pkg
            targetWindowId = event.windowId
        }
        // App switches invalidate immediately, even while waiting for the notification shade to close.
        if (reason != null) {
            cancel(reason, event)
            if (reason == CaptureCancelReason.APP_SWITCH) translator.clear(ContextReset.APP_SWITCH)
            return
        }
        if (waitingForShade) return
        val changed = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT != 0)
        if (changed && pkg == targetPackage && overlay.visible && settings?.autoCollapse != false) {
            cancel(CaptureCancelReason.PAGE_CHANGED, event)
        }
        if (busy && (changed || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            if (deferredEvents++ == 0) RunLog.info(LogModule.CAPTURE, "change_deferred", "request" to generation.get(),
                "stage" to stage, "event_type" to event.eventType, "window" to event.windowId,
                "change_types" to event.contentChangeTypes)
        }
        // A chat refresh/IME/popup event alone does not prove that captured text moved.
        // Running work is validated with a lightweight pixel grid before any overlay is attached.
    }

    @Suppress("DEPRECATION")
    private fun isActivityWindow(pkg: String, event: AccessibilityEvent): Boolean {
        val name = event.className?.toString() ?: return false
        if (event.windowId < 0) return false
        val component = ComponentName(pkg, name)
        if (component == lastActivity) return true
        return try {
            packageManager.getActivityInfo(component, 0)
            lastActivity = component
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
    }

    override fun onInterrupt() { RunLog.warn(LogModule.PERMISSION, "accessibility_interrupted"); cancel(CaptureCancelReason.INTERRUPTED) }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val rotation = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation
        val geometryChanged = bounds != displayBounds || rotation != displayRotation
        RunLog.info(LogModule.CAPTURE, "configuration_changed", "geometry_changed" to if (geometryChanged) 1 else 0)
        displayBounds = Rect(bounds)
        displayRotation = rotation
        if (geometryChanged) cancel(CaptureCancelReason.ROTATION)
    }
    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(configurationListener)
        AppRuntime.main.removeCallbacks(expireContext)
        cancel(CaptureCancelReason.SERVICE_DESTROYED)
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
