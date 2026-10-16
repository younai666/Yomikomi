package io.github.yomikomi

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yomikomi.update.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class UpdateDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val digest = "a".repeat(64)

    private fun row(tag: String, draft: Boolean = false, preview: Boolean = false) = JSONObject()
        .put("tag_name", tag).put("draft", draft).put("prerelease", preview)
        .put("assets", JSONArray().put(JSONObject().put("id", 17).put("name", "Yomikomi-$tag.apk")
            .put("state", "uploaded").put("size", 1000).put("digest", "sha256:$digest")
            .put("browser_download_url", "https://github.com/younai666/Yomikomi/releases/download/$tag/Yomikomi-$tag.apk")))

    @Test fun releaseListIncludesPublishedPreviewAndComparesVersionsRatherThanPublicationOrder() {
        val rows = JSONArray().put(row("v0.1.9")).put(row("v1.0.0", draft = true))
            .put(row("v0.1.10", preview = true)).put(row("nightly"))
        val release = GitHubUpdates.parse(rows.toString())!!
        assertEquals(0, AppVersion.parse("0.1.10")!!.compareTo(release.version))
        assertEquals(17, release.assets.single().id.toInt())
        assertTrue(release.assets.single().valid())
        assertNull(GitHubUpdates.parse("[]"))
    }

    @Test fun malformedMetadataAndRateLimitsNeverClaimUpToDateOrExposeResponseBodies() {
        listOf("{private-response}", "[{}]", JSONArray().put(row("nightly")).toString()).forEach {
            assertThrows(UpdateException::class.java) { GitHubUpdates.parse(it) }
        }
        for (status in listOf(302, 403, 429, 500)) {
            val connection = Connection("private-api-response".toByteArray(), status)
            val error = assertThrows(UpdateException::class.java) { GitHubUpdates { connection }.latest() }
            assertFalse(error.userMessage.contains("private-api-response"))
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.disconnected)
        }
        val broken = row("v0.1.2").apply { getJSONArray("assets").getJSONObject(0).put("digest", JSONObject.NULL) }
        assertFalse(GitHubUpdates.parse(JSONArray().put(broken).toString())!!.assets.single().valid())
    }

    @Test fun equalOlderOrEmptyReleasesReturnIdleWithoutDownloading() {
        for (body in listOf(JSONArray().put(row("v0.1.0")).toString(), JSONArray().put(row("v0.1.1")).toString(), "[]")) {
            var downloads = 0
            instrumentation.runOnMainSync {
                AppUpdater.check(context, GitHubUpdates { Connection(body.toByteArray()) }, { downloads++; error("Unexpected download") })
            }
            waitUntil { AppUpdater.status.phase == UpdatePhase.Idle }
            assertEquals(0, downloads)
        }
    }

    /** Optional tiny, locally signed APK fixtures; never installs or replaces the user's app. */
    @Test fun downloadedApkUsesRealPackageAndSignerValidationThenOpensSystemInstaller() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("apkFixture") == "true")
        val bytes = shellBytes("cat /data/local/tmp/yomikomi-update-fixture.apk")
        assertTrue(bytes.size > 100)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val releaseRow = row("v0.1.2").apply {
            getJSONArray("assets").getJSONObject(0).put("size", bytes.size).put("digest", "sha256:$hash")
        }
        val directory = File(context.cacheDir, "updates")
        val apk = File(directory, "$hash.apk")
        try {
            instrumentation.runOnMainSync {
                AppUpdater.check(context, GitHubUpdates { Connection(JSONArray().put(releaseRow).toString().toByteArray()) }, { Connection(bytes) })
            }
            waitUntil { AppUpdater.status.phase == UpdatePhase.Downloaded }
            assertArrayEquals(bytes, apk.readBytes())
            ApkInstaller.verify(context, apk, AppVersion.parse("0.1.2")!!)
            for (name in listOf("wrong-signer", "wrong-package", "old")) {
                val bad = File(directory, "$name.apk").apply { writeBytes(shellBytes("cat /data/local/tmp/yomikomi-update-$name.apk")) }
                try { assertThrows(UpdateException::class.java) { ApkInstaller.verify(context, bad, AppVersion.parse("0.1.2")!!) } }
                finally { bad.delete() }
            }
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.updates", File(context.noBackupFilesDir, "logs/runtime.log"))
            }
            assertTrue("Allow REQUEST_INSTALL_PACKAGES before this opt-in test", ApkInstaller.allowed(context))
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            waitUntil { shell("dumpsys activity activities").lineSequence().any { it.contains("ResumedActivity", ignoreCase = true) && it.contains("packageinstaller", ignoreCase = true) } }
            assertEquals(UpdatePhase.Ready, AppUpdater.status.phase)
            val intent = ApkInstaller.intent(context, apk)
            assertEquals("content", intent.data!!.scheme)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            shell("input keyevent KEYCODE_BACK") // Cancel the real installer; do not replace the installed app with the fixture.
        } finally {
            apk.delete()
            instrumentation.runOnMainSync { AppUpdater.installation(context) }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Update stage timed out")
            Thread.sleep(100)
        }
    }
    private fun shellBytes(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    private fun shell(command: String) = shellBytes(command).toString(Charsets.UTF_8)
    private open class Connection(private val body: ByteArray, private val code: Int = 200) : HttpURLConnection(URL("https://github.com")) {
        var disconnected = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun disconnect() { disconnected = true }
        override fun connect() {}
        override fun usingProxy() = false
    }
}
