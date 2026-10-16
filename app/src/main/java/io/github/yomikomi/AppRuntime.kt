package io.github.yomikomi

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yomikomi.models.ModelCatalog
import io.github.yomikomi.models.ModelDownloader
import io.github.yomikomi.models.ModelSpec
import io.github.yomikomi.ocr.TesseractOcr
import io.github.yomikomi.translation.NativeLlama
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog
import java.io.File
import java.util.concurrent.Executors

data class ModelStatus(val phase: String, val bytes: Long, val error: String? = null) {
    val busy get() = phase in listOf("下载中", "校验中", "加载中")
}

class YomikomiApplication : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        RunLog.info(LogModule.APP, "trim_memory", "level" to level)
    }

    override fun onCreate() {
        super.onCreate()
        RunLog.initialize(File(noBackupFilesDir, "logs")) { Log.w("YomikomiLog", "file_logging_failed") }
        RunLog.info(LogModule.APP, "started", "sdk" to Build.VERSION.SDK_INT, "pid" to android.os.Process.myPid(),
            "processors" to Runtime.getRuntime().availableProcessors(), "max_heap_mb" to Runtime.getRuntime().maxMemory() / 1_048_576)
        AppRuntime.initialize(this)
        AppRuntime.worker.execute { if (Build.VERSION.SDK_INT >= 30) try {
            getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()?.let {
                RunLog.info(LogModule.APP, "previous_exit", "reason" to it.reason, "status" to it.status,
                    "timestamp_ms" to it.timestamp, "pss_kb" to it.pss, "rss_kb" to it.rss)
            }
        } catch (error: Exception) { RunLog.warn(LogModule.APP, "previous_exit_unavailable", error) } }
        RunLog.info(LogModule.APP, "created")
    }
}

/** One worker owns OCR and llama.cpp; downloads use the foreground service's separate worker. */
object AppRuntime {
    val worker = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())
    lateinit var root: File
        private set
    val ocr by lazy { TesseractOcr(root) }
    var models by mutableStateOf(ModelCatalog.all.associate { it.id to ModelStatus("未下载", 0) })
        private set
    var serviceConnected by mutableStateOf(false)
    var tileAdded by mutableStateOf(false)
    var pipelinePhase by mutableStateOf("就绪")
    private val ready = mutableSetOf<String>() // Worker-thread owned.

    fun initialize(context: Context) {
        root = File(context.noBackupFilesDir, "models")
        worker.execute { try {
            val added = context.getSharedPreferences("runtime", 0).getBoolean("tile", false)
            val discovered = ModelCatalog.all.associate { spec ->
                val complete = spec.assets.all { File(root, it.path).length() == it.bytes }
                val bytes = spec.assets.sumOf { asset ->
                    val file = File(root, asset.path)
                    if (file.length() == asset.bytes) asset.bytes else File(root, "${asset.path}.part").length()
                }
                spec.id to ModelStatus(if (complete) "已下载" else if (bytes > 0) "已暂停" else "未下载", bytes)
            }
            main.post { tileAdded = added; models = discovered }
            RunLog.info(LogModule.APP, "runtime_ready")
        } catch (error: Exception) { RunLog.error(LogModule.APP, "runtime_discovery_failed", error) } }
    }

    fun status(spec: ModelSpec) = models.getValue(spec.id)
    fun update(spec: ModelSpec, phase: String, bytes: Long, error: String? = null) {
        main.post { models = models + (spec.id to ModelStatus(phase, bytes, error)) }
    }

    /** Called only on worker. On-disk deployment is reverified once per process before use. */
    fun ensureLoaded(spec: ModelSpec, source: String = "自动检测", gpuAcceleration: Boolean = false) {
        try {
            val path = File(root, spec.assets.first().path).absolutePath
            val verified = spec.id in ready
            if (!verified || (spec == ModelCatalog.translation && !NativeLlama.isLoaded(path, gpuAcceleration))) {
                update(spec, "加载中", spec.bytes)
                RunLog.measure(LogModule.MODEL, "load", "kind" to if (spec == ModelCatalog.ocr) 0 else 1) {
                    val verifier = ModelDownloader(root)
                    if (!verified) RunLog.measure(LogModule.MODEL, "verify", "bytes" to spec.bytes) {
                        check(spec.assets.all { verifier.valid(File(root, it.path), it) }) { "请先下载完整模型" }
                    }
                    if (spec == ModelCatalog.ocr) ocr.load(source)
                    else NativeLlama.load(path, gpuAcceleration)
                }
            } else if (spec == ModelCatalog.ocr) ocr.load(source)
            ready += spec.id
            update(spec, "已加载", spec.bytes)
        } catch (error: Exception) {
            ready.remove(spec.id)
            update(spec, "加载失败", 0, error.message)
            throw error
        }
    }

    fun load(spec: ModelSpec, source: String, gpuAcceleration: Boolean = false) {
        update(spec, "加载中", spec.bytes)
        worker.execute { try { ensureLoaded(spec, source, gpuAcceleration) } catch (_: Exception) { /* Exposed through ModelStatus. */ } }
    }
}
