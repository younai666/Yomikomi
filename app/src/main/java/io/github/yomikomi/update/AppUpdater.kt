package io.github.yomikomi.update

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yomikomi.AppRuntime
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog
import io.github.yomikomi.models.ModelAsset
import io.github.yomikomi.models.ModelDownloader
import io.github.yomikomi.models.ModelSpec
import java.io.File
import java.util.concurrent.Executors
import java.net.HttpURLConnection
import java.net.URL

enum class UpdatePhase { Idle, Checking, Downloading, Verifying, Downloaded, Ready }
data class UpdateStatus(val phase: UpdatePhase = UpdatePhase.Idle, val percent: Int = 0)

/** Process-local work survives page changes/rotation. A killed process resumes the pinned partial on the next check. */
object AppUpdater {
    private val worker = Executors.newSingleThreadExecutor()
    private val releases = GitHubUpdates()
    var status by mutableStateOf(UpdateStatus())
        private set
    private var ready: File? = null

    internal fun check(context: Context, releases: GitHubUpdates = this.releases,
        connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
        if (status.phase != UpdatePhase.Idle) return
        val app = context.applicationContext
        status = UpdateStatus(UpdatePhase.Checking)
        RunLog.info(LogModule.ACTION, "check_update")
        worker.execute {
            try {
                val current = AppVersion.parse(ApkInstaller.installed(app).versionName.orEmpty())
                    ?: throw UpdateException("当前版本号无效")
                val release = releases.latest(current)
                if (release == null || release.version <= current) {
                    RunLog.info(LogModule.UPDATE, "up_to_date")
                    AppRuntime.main.post { status = UpdateStatus(); message(app, "已是最新") }
                    return@execute
                }
                val asset = release.apk(Build.SUPPORTED_ABIS.toList()) ?: throw UpdateException("新版本暂无适用安装包")
                if (!asset.valid()) throw UpdateException("更新文件信息无效")
                val directory = File(app.cacheDir, "updates").apply { check(isDirectory || mkdirs()) }
                directory.listFiles()?.filter { it.name != asset.fileName && it.name != "${asset.fileName}.part" }?.forEach { it.delete() }
                val spec = ModelSpec("app-update", "Yomikomi", listOf(ModelAsset(asset.fileName, asset.url, asset.bytes, asset.sha256)))
                var lastBucket = -1
                ModelDownloader(directory, connect).download(spec) { bytes, phase ->
                    val progress = (bytes * 100 / asset.bytes).toInt()
                    val state = if (phase == "下载中") UpdatePhase.Downloading else UpdatePhase.Verifying
                    AppRuntime.main.post { status = UpdateStatus(state, progress) }
                    if (progress / 10 != lastBucket) {
                        RunLog.info(LogModule.UPDATE, "download_progress", "percent" to progress)
                        lastBucket = progress / 10
                    }
                }
                val apk = File(directory, asset.fileName)
                try { ApkInstaller.verify(app, apk, release.version) }
                catch (error: Exception) { apk.delete(); throw error }
                AppRuntime.main.post { ready = apk; status = UpdateStatus(UpdatePhase.Downloaded, 100) }
            } catch (error: Exception) {
                RunLog.error(LogModule.UPDATE, "failed", error)
                AppRuntime.main.post {
                    status = UpdateStatus()
                    message(app, (error as? UpdateException)?.userMessage ?: "更新失败，请稍后重试")
                }
            }
        }
    }

    fun installation(context: Context): File? {
        val apk = ready
        if (apk == null || !apk.isFile) {
            status = UpdateStatus(); message(context, "安装包已失效，请重新检查")
            return null
        }
        status = UpdateStatus(UpdatePhase.Ready, 100) // Consume automatic launch before leaving for system UI.
        return apk
    }

    private fun message(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
