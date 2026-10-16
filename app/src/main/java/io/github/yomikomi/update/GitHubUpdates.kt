package io.github.yomikomi.update

import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog
import org.json.JSONArray
import org.json.JSONException
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException

class GitHubUpdates(
    private val now: () -> Long = System::currentTimeMillis,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private var cached: UpdateRelease? = null
    private var cachedFor: AppVersion? = null
    private var checkedAt: Long? = null
    private var apiRetryAt = 0L

    /** Only successful, recent checks are cached. An unavailable source never means "latest". */
    fun latest(current: AppVersion? = null): UpdateRelease? = RunLog.measure(LogModule.UPDATE, "check") {
        if (cachedFor == current && checkedAt?.let { now() - it in 0 until 60_000 } == true) {
            RunLog.info(LogModule.UPDATE, "cache_hit")
            return@measure cached
        }
        val release = if (now() < apiRetryAt) fromFeed(current) else {
            try { parse(body("https://api.github.com/repos/younai666/Yomikomi/releases?per_page=100", "application/vnd.github+json")) }
            catch (error: HttpFailure) {
                if (error.status !in listOf(403, 429) && error.status !in 500..599)
                    throw UpdateException("无法获取 GitHub 更新，请稍后重试", error)
                apiRetryAt = error.retryAt
                RunLog.warn(LogModule.UPDATE, "api_unavailable", null, "http_status" to error.status)
                fromFeed(current)
            } catch (error: IOException) {
                RunLog.warn(LogModule.UPDATE, "api_unreachable", error)
                fromFeed(current)
            }
        }
        cached = release; cachedFor = current; checkedAt = now()
        release
    }

    private fun fromFeed(current: AppVersion?): UpdateRelease? {
        RunLog.info(LogModule.UPDATE, "feed_fallback")
        try {
            val tag = GitHubReleasePage.latestTag(body("https://github.com/younai666/Yomikomi/releases.atom", "application/atom+xml"))
                ?: return null
            val version = checkNotNull(AppVersion.parse(tag))
            // No APK metadata or download request is needed for an already installed version.
            if (current != null && version <= current) return UpdateRelease(version, emptyList())
            val assets = GitHubReleasePage.assets(tag, body("https://github.com/younai666/Yomikomi/releases/expanded_assets/$tag", "text/html"))
            return UpdateRelease(version, assets.map { asset -> asset.copy(bytes = assetSize(asset.url)) })
        } catch (error: HttpFailure) {
            throw UpdateException(if (error.status == 403 || error.status == 429) "GitHub 暂时限制访问，请稍后重试" else "无法获取 GitHub 更新，请稍后重试", error)
        } catch (error: IOException) { throw UpdateException("无法连接 GitHub，请检查网络后重试", error) }
    }

    private fun connection(url: URL, accept: String): HttpURLConnection = connect(url).apply {
        connectTimeout = 10_000; readTimeout = 15_000
        instanceFollowRedirects = false
        setRequestProperty("Accept", accept)
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("User-Agent", "Yomikomi")
        if (url.host == "api.github.com") setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    }

    private fun body(address: String, accept: String): String {
        val conn = connection(URL(address), accept)
        try {
            val status = conn.responseCode
            RunLog.info(LogModule.UPDATE, "response", "http_status" to status)
            if (status != 200) {
                val retryAfter = conn.getHeaderField("Retry-After")?.toLongOrNull()?.takeIf { it in 1..86_400 }
                val reset = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }
                val retryAt = when {
                    retryAfter != null -> now() + retryAfter * 1000
                    conn.getHeaderField("X-RateLimit-Remaining") == "0" && reset != null -> maxOf(now() + 60_000, reset * 1000)
                    else -> now() + 60_000
                }
                throw HttpFailure(status, retryAt)
            }
            return conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 2_097_152) throw UpdateException("更新信息过大")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally { conn.disconnect() }
    }

    private fun assetSize(address: String): Long {
        var url = URL(address)
        repeat(6) {
            if (url.protocol != "https" || url.userInfo != null) throw UpdateException("更新下载地址无效")
            val conn = connection(url, "application/octet-stream")
            try {
                conn.requestMethod = "HEAD"
                val status = conn.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    url = URL(url, conn.getHeaderField("Location") ?: throw UpdateException("更新下载地址无效"))
                } else {
                    if (status != 200) throw HttpFailure(status, now() + 60_000)
                    return conn.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it in 1..268_435_456 }
                        ?: throw UpdateException("更新文件大小无效")
                }
            } finally { conn.disconnect() }
        }
        throw UpdateException("更新下载重定向过多")
    }

    private class HttpFailure(val status: Int, val retryAt: Long) : Exception()

    companion object {
        /** Include published previews: the project's first public release is marked prerelease. */
        fun parse(body: String): UpdateRelease? {
            try {
                val rows = JSONArray(body)
                val releases = (0 until rows.length()).mapNotNull { index ->
                    val row = rows.getJSONObject(index)
                    if (row.getBoolean("draft")) return@mapNotNull null
                    val version = AppVersion.parse(row.getString("tag_name")) ?: return@mapNotNull null
                    val assets = row.getJSONArray("assets")
                    UpdateRelease(version, (0 until assets.length()).mapNotNull { assetIndex ->
                        val asset = assets.getJSONObject(assetIndex)
                        if (asset.optString("state") != "uploaded") return@mapNotNull null
                        val digest = asset.optString("digest")
                        UpdateAsset(asset.optLong("id", -1), asset.optString("name"), asset.optString("browser_download_url"),
                            asset.optLong("size", -1), if (digest.startsWith("sha256:")) digest.removePrefix("sha256:") else "")
                    })
                }
                if (rows.length() > 0 && releases.isEmpty()) throw UpdateException("未找到可识别的发行版本")
                return releases.maxByOrNull { it.version }
            } catch (error: JSONException) { throw UpdateException("更新信息无效", error) }
        }
    }
}
