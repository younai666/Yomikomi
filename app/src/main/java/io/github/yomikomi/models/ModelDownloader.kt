package io.github.yomikomi.models

import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog

/** Immutable URLs + pinned SHA-256 make partial files safe to resume across process restarts. */
class ModelDownloader(
    private val root: File,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private val stopped = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { stopped.set(true); connection?.disconnect() }
    private fun checkpoint() { if (stopped.get()) throw InterruptedIOException("下载已暂停") }

    fun download(spec: ModelSpec, progress: (Long, String) -> Unit) =
        RunLog.measure(LogModule.DOWNLOAD, "transfer", "bytes" to spec.bytes) {
            var completed = 0L
            for ((assetIndex, asset) in spec.assets.withIndex()) {
                checkpoint()
                val destination = File(root, asset.path)
                destination.parentFile!!.mkdirs()
                if (destination.exists()) {
                    progress(completed, "校验中")
                    if (valid(destination, asset)) {
                        completed += asset.bytes
                        continue
                    }
                    check(destination.delete()) { "无法移除损坏的模型文件" }
                }
                val partial = File(root, "${asset.path}.part")
                if (partial.length() > asset.bytes) check(partial.delete())
                var offset = partial.length()
                RunLog.info(LogModule.DOWNLOAD, "asset", "index" to assetIndex, "offset" to offset, "bytes" to asset.bytes)
                require(root.usableSpace > asset.bytes - offset + 16 * 1024 * 1024) { "存储空间不足" }
                progress(completed + offset, "下载中")
                if (offset < asset.bytes) {
                    val conn = open(asset.url, offset)
                    connection = conn
                    try {
                        checkpoint()
                        val code = conn.responseCode
                        if (code == HttpURLConnection.HTTP_OK) {
                            // Servers that ignore Range must restart, never append a full file.
                            offset = 0
                        } else if (code == HttpURLConnection.HTTP_PARTIAL) {
                            val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(conn.getHeaderField("Content-Range") ?: "")
                            require(range != null && range.groupValues[1].toLong() == offset &&
                                range.groupValues[3].toLong() == asset.bytes) { "下载服务器返回了不匹配的断点" }
                        } else error("下载失败：HTTP $code")
                        FileOutputStream(partial, offset > 0).use { output ->
                            conn.inputStream.use { input ->
                                val buffer = ByteArray(128 * 1024)
                                var received = offset
                                var lastUpdate = 0L
                                while (true) {
                                    checkpoint()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(received + count <= asset.bytes) { "模型文件大小不匹配" }
                                    output.write(buffer, 0, count)
                                    received += count
                                    val now = System.nanoTime()
                                    if (now - lastUpdate > 250_000_000) {
                                        progress(completed + received, "下载中")
                                        lastUpdate = now
                                    }
                                }
                                require(received == asset.bytes) { "下载中断，可继续下载" }
                            }
                            output.fd.sync()
                        }
                    } finally { conn.disconnect(); connection = null }
                }
                checkpoint()
                progress(completed + asset.bytes, "校验中")
                if (!valid(partial, asset)) {
                    RunLog.warn(LogModule.DOWNLOAD, "checksum_rejected", null, "index" to assetIndex)
                    check(partial.delete())
                    error("SHA-256 校验失败，请重新下载")
                }
                checkpoint()
                check(partial.renameTo(destination)) { "模型部署失败" }
                RunLog.info(LogModule.DOWNLOAD, "deployed", "index" to assetIndex)
                completed += asset.bytes
            }
            progress(completed, "已下载")
        }

    private fun open(address: String, offset: Long): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            require(url.protocol == "https") { "模型下载仅允许 HTTPS" }
            checkpoint()
            val conn = connect(url)
            connection = conn
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
            try {
                val code = conn.responseCode
                RunLog.info(LogModule.DOWNLOAD, "response", "http_status" to code, "offset" to offset)
                if (code !in listOf(301, 302, 303, 307, 308)) return conn
                val location = conn.getHeaderField("Location") ?: error("下载重定向缺少地址")
                url = URL(url, location)
            } catch (error: Exception) {
                conn.disconnect()
                connection = null
                throw error
            }
            conn.disconnect()
        }
        error("下载重定向次数过多")
    }

    fun valid(file: File, asset: ModelAsset): Boolean {
        if (file.length() != asset.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                checkpoint()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256
    }
}
