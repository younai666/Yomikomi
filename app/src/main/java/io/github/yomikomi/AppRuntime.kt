package io.github.yomikomi

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yomikomi.models.ModelCatalog
import io.github.yomikomi.models.ModelDownloader
import io.github.yomikomi.models.ModelSpec
import io.github.yomikomi.ocr.TesseractOcr
import io.github.yomikomi.translation.NativeLlama
import java.io.File
import java.util.concurrent.Executors

data class ModelStatus(val phase: String, val bytes: Long, val total: Long, val error: String? = null) {
    val busy get() = phase in listOf("下载中", "校验中", "加载中")
}

class YomikomiApplication : Application() {
    override fun onCreate() { super.onCreate(); AppRuntime.initialize(this) }
}

/** One worker owns OCR and llama.cpp; downloads use the foreground service's separate worker. */
object AppRuntime {
    val worker = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())
    lateinit var root: File
        private set
    lateinit var ocr: TesseractOcr
        private set
    var models by mutableStateOf<Map<String, ModelStatus>>(emptyMap())
        private set
    var serviceConnected by mutableStateOf(false)
    var tileAdded by mutableStateOf(false)
    var pipelinePhase by mutableStateOf("就绪")
    private val ready = mutableSetOf<String>() // Worker-thread owned.

    fun initialize(context: Context) {
        root = File(context.noBackupFilesDir, "models").apply { mkdirs() }
        ocr = TesseractOcr(root)
        tileAdded = context.getSharedPreferences("runtime", 0).getBoolean("tile", false)
        models = ModelCatalog.all.associate { spec ->
            val complete = spec.assets.all { File(root, it.path).length() == it.bytes }
            val bytes = spec.assets.sumOf { asset ->
                val file = File(root, asset.path)
                if (file.length() == asset.bytes) asset.bytes else File(root, "${asset.path}.part").length()
            }
            spec.id to ModelStatus(if (complete) "已下载" else if (bytes > 0) "已暂停" else "未下载", bytes, spec.bytes)
        }
    }

    fun status(spec: ModelSpec) = models.getValue(spec.id)
    fun update(spec: ModelSpec, phase: String, bytes: Long, error: String? = null) {
        main.post { models = models + (spec.id to ModelStatus(phase, bytes, spec.bytes, error)) }
    }

    /** Called only on worker. On-disk deployment is reverified once per process before use. */
    fun ensureLoaded(spec: ModelSpec) {
        if (spec.id in ready) return
        update(spec, "加载中", spec.bytes)
        try {
            val verifier = ModelDownloader(root)
            check(spec.assets.all { verifier.valid(File(root, it.path), it) }) { "请先下载完整模型" }
            if (spec == ModelCatalog.ocr) ocr.load()
            else NativeLlama.load(File(root, spec.assets.single().path).absolutePath)
            ready += spec.id
            update(spec, "已加载", spec.bytes)
        } catch (error: Exception) {
            update(spec, "加载失败", 0, error.message)
            throw error
        }
    }

    fun load(spec: ModelSpec) {
        worker.execute { try { ensureLoaded(spec) } catch (_: Exception) { /* Exposed through ModelStatus. */ } }
    }
}
