package io.github.yomikomi.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import io.github.yomikomi.AppRuntime
import io.github.yomikomi.MainActivity
import io.github.yomikomi.RuntimeSettings
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog

class ModelDownloadService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var active: ModelSpec? = null
    private var downloader: ModelDownloader? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) {
            RunLog.info(LogModule.ACTION, "download_pause")
            downloader?.cancel() ?: stopSelf()
            return START_NOT_STICKY
        }
        if (active != null) return START_NOT_STICKY
        val spec = ModelCatalog.all.find { it.id == intent?.getStringExtra("model") }
            ?: run { stopSelf(); return START_NOT_STICKY }
        active = spec
        RunLog.info(LogModule.ACTION, "download_start", "kind" to if (spec == ModelCatalog.ocr) 0 else 1,
            "notification_permission" to if (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) 1 else 0)
        AppRuntime.update(spec, "下载中", AppRuntime.status(spec).bytes)
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "模型下载", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, notification(spec, 0, "下载中"))
        val download = ModelDownloader(AppRuntime.root)
        downloader = download
        worker.execute {
            try {
                var previousBucket = -1
                var previousPhase = ""
                download.download(spec) { bytes, phase ->
                    val percent = (bytes * 100 / spec.bytes).toInt()
                    if (percent / 10 != previousBucket || phase != previousPhase) {
                        RunLog.info(LogModule.DOWNLOAD, "progress", "bytes" to bytes, "percent" to percent,
                            "phase" to when (phase) { "下载中" -> 0; "校验中" -> 1; else -> 2 })
                        previousBucket = percent / 10; previousPhase = phase
                    }
                    AppRuntime.update(spec, phase, bytes)
                    if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                        manager.notify(NOTIFICATION, notification(spec, bytes, phase))
                }
                val settings = RuntimeSettings.read(this)
                AppRuntime.load(spec, settings.source, settings.gpuAcceleration)
            } catch (error: Exception) {
                val bytes = spec.assets.sumOf { asset ->
                    val complete = java.io.File(AppRuntime.root, asset.path)
                    if (complete.exists()) complete.length() else java.io.File(AppRuntime.root, "${asset.path}.part").length()
                }
                AppRuntime.update(spec, "已暂停", bytes,
                    if (error is InterruptedIOException) null else error.message ?: "下载失败")
            } finally {
                AppRuntime.main.post {
                    active = null
                    downloader = null
                    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
                    else { @Suppress("DEPRECATION") stopForeground(true) }
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(spec: ModelSpec, bytes: Long, phase: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(io.github.yomikomi.R.drawable.ic_tile).setContentTitle(spec.title)
            .setContentText("$phase · ${bytes * 100 / spec.bytes}%").setContentIntent(open)
            .setProgress(100, (bytes * 100 / spec.bytes).toInt(), phase != "下载中")
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "暂停", pause).build()).build()
    }

    override fun onDestroy() { downloader?.cancel(); worker.shutdown(); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) {
        RunLog.warn(LogModule.DOWNLOAD, "service_timeout")
        downloader?.cancel(); stopSelf()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "models"
        private const val NOTIFICATION = 10
        const val PAUSE = "io.github.yomikomi.PAUSE_DOWNLOAD"
        fun start(context: Context, spec: ModelSpec) {
            val intent = Intent(context, ModelDownloadService::class.java).putExtra("model", spec.id)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        fun pause(context: Context) { context.startService(Intent(context, ModelDownloadService::class.java).setAction(PAUSE)) }
    }
}
