package io.github.yomikomi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yomikomi.update.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class GitHubUpdatesDeviceTest {
    private val hash = "b".repeat(64)
    private val repo = "https://github.com/younai666/Yomikomi/releases"
    private fun version(text: String) = AppVersion.parse(text)!!
    private fun feed(vararg tags: String) = """<feed xmlns="http://www.w3.org/2005/Atom">${tags.joinToString("") {
        """<entry><link rel="alternate" href="$repo/tag/$it"/></entry>"""
    }}</feed>"""
    private fun page(tag: String) = """<ul>
        <li><a href="/younai666/Yomikomi/releases/download/$tag/SHA256SUMS">SHA256SUMS</a><clipboard-copy value="sha256:${"a".repeat(64)}"></clipboard-copy></li>
        <li><a href="/younai666/Yomikomi/releases/download/$tag/Yomikomi-$tag.apk">APK</a><clipboard-copy value="sha256:$hash"></clipboard-copy></li>
        </ul>"""

    @Test fun firstTapSurvivesSharedIpLimitAndCachesVerifiedMetadataAcrossSources() {
        val connections = mutableListOf<Connection>()
        val client = GitHubUpdates(now = { 100_000 }) { url ->
            val conn = when {
                url.host == "api.github.com" -> Connection(code = 403, headers = mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "400"))
                url.path.endsWith(".atom") -> Connection(feed("v0.1.9", "v0.1.10", "nightly"))
                url.path.contains("expanded_assets") -> Connection(page("v0.1.10"))
                url.host == "github.com" && url.path.endsWith(".apk") -> Connection(code = 302, headers = mapOf("Location" to "https://release-assets.githubusercontent.com/asset"))
                url.host == "release-assets.githubusercontent.com" -> Connection(headers = mapOf("Content-Length" to "37421335"))
                else -> error("Unexpected request")
            }
            connections += conn; conn
        }
        val release = client.latest(version("0.1.1"))!!
        assertEquals(version("0.1.10"), release.version)
        val apk = release.assets.single()
        assertTrue(apk.valid())
        assertEquals(hash, apk.sha256)
        assertEquals(37_421_335, apk.bytes.toInt())
        assertEquals(apk.fileName, apk.copy(id = 99).fileName)
        assertEquals(release, client.latest(version("0.1.1")))
        assertEquals(5, connections.size)
        assertEquals("HEAD", connections.last().requestMethod)
        assertTrue(connections.all { it.disconnected && !it.instanceFollowRedirects })
    }

    @Test fun cacheExpiresButApiResetIsRespectedAndFreshFeedCanDiscoverNewVersions() {
        var time = 100_000L
        var apiCalls = 0
        var feedCalls = 0
        val client = GitHubUpdates(now = { time }) { url ->
            when {
                url.host == "api.github.com" -> {
                    apiCalls++
                    if (apiCalls == 1) Connection(code = 429, headers = mapOf("Retry-After" to "300")) else Connection("[]")
                }
                url.path.endsWith(".atom") -> { feedCalls++; Connection(feed(if (feedCalls == 1) "v0.1.0" else "v0.1.1")) }
                else -> error("No assets needed for an older release")
            }
        }
        assertEquals(version("0.1.0"), client.latest(version("1.0.0"))!!.version)
        time += 59_999
        assertEquals(version("0.1.0"), client.latest(version("1.0.0"))!!.version)
        assertEquals(1, feedCalls)
        time++
        assertEquals(version("0.1.1"), client.latest(version("1.0.0"))!!.version)
        assertEquals(1, apiCalls)
        assertEquals(2, feedCalls)
        time = 400_000
        assertNull(client.latest(version("1.0.0")))
        assertEquals(2, apiCalls)
    }

    @Test fun failedCheckIsNotCachedAndNeitherStaleDataNorErrorsMeanLatest() {
        var time = 100_000L
        var feedWorks = true
        val client = GitHubUpdates(now = { time }) { url ->
            if (url.host == "api.github.com") throw IOException("private-response")
            if (feedWorks) Connection(feed("v0.1.0")) else Connection(code = 403)
        }
        assertNotNull(client.latest(version("0.1.1")))
        time += 60_000
        feedWorks = false
        val error = assertThrows(UpdateException::class.java) { client.latest(version("0.1.1")) }
        assertFalse(error.userMessage.contains("频繁"))
        assertFalse(error.userMessage.contains("private-response"))
        feedWorks = true
        assertNotNull(client.latest(version("0.1.1")))
    }

    @Test fun fallbackRejectsInvalidXmlForeignRepositoriesAndMissingDigests() {
        assertNull(GitHubReleasePage.latestTag(feed()))
        for (invalid in listOf("<html/>", "private-response", feed("nightly"),
            feed("v0.1.0").replace("younai666", "another-owner"),
            """<!DOCTYPE feed [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>""" + feed("v0.1.0"))) {
            assertThrows(UpdateException::class.java) { GitHubReleasePage.latestTag(invalid) }
        }
        assertThrows(UpdateException::class.java) { GitHubReleasePage.assets("v0.1.2", page("v0.1.2").replace("sha256:$hash", "sha256:")) }
        assertThrows(UpdateException::class.java) { GitHubReleasePage.assets("v0.1.2", page("v0.1.1")) }
        val unsafe = GitHubUpdates { url -> when {
            url.host == "api.github.com" -> Connection(code = 403)
            url.path.endsWith(".atom") -> Connection(feed("v0.1.2"))
            url.path.contains("expanded_assets") -> Connection(page("v0.1.2"))
            else -> Connection(code = 302, headers = mapOf("Location" to "http://unsafe.test/file"))
        } }
        assertThrows(UpdateException::class.java) { unsafe.latest(version("0.1.1")) }
    }

    /** Opt-in public network check: reproduces a first-tap API 403, then reads real GitHub metadata. */
    @Test fun liveGitHubFallbackReadsRealDigestAndExactApkSize() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveUpdate") == "true")
        val client = GitHubUpdates { url ->
            if (url.host == "api.github.com") Connection(code = 403)
            else url.openConnection() as HttpURLConnection
        }
        val release = client.latest(version("0.0.1"))!!
        assertTrue(release.version >= version("0.1.0"))
        val asset = release.apk(listOf("x86_64"))!!
        assertTrue(asset.valid())
        assertTrue(asset.bytes > 1_000_000)
    }

    private class Connection(body: String = "", private val code: Int = 200, private val headers: Map<String, String> = emptyMap()) : HttpURLConnection(URL("https://github.com")) {
        private val bytes = body.toByteArray()
        var disconnected = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getHeaderField(name: String) = headers[name]
        override fun disconnect() { disconnected = true }
        override fun connect() {}
        override fun usingProxy() = false
    }
}
