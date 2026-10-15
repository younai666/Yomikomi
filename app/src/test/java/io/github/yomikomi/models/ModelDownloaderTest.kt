package io.github.yomikomi.models

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ModelDownloaderTest {
    @get:Rule val folder = TemporaryFolder()
    private val bytes = "a complete pinned model file".toByteArray()
    private val asset = ModelAsset("model.bin", "https://models.invalid/model", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    private val spec = ModelSpec("test", "test", listOf(asset))

    private class Response(private val body: ByteArray, private val status: Int, private val range: String? = null) :
        HttpURLConnection(URL("https://models.invalid/model")) {
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getHeaderField(name: String?) = if (name == "Content-Range") range else null
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    @Test fun resumesPartialAndDeploysOnlyAfterSha256() {
        val root = folder.root
        File(root, "model.bin.part").writeBytes(bytes.take(7).toByteArray())
        val response = Response(bytes.drop(7).toByteArray(), 206, "bytes 7-${bytes.lastIndex}/${bytes.size}")
        ModelDownloader(root) { response }.download(spec) { _, _ -> }
        assertEquals("bytes=7-", response.getRequestProperty("Range"))
        assertArrayEquals(bytes, File(root, "model.bin").readBytes())
        assertFalse(File(root, "model.bin.part").exists())
    }

    @Test fun serverIgnoringRangeRestartsInsteadOfAppending() {
        val root = folder.root
        File(root, "model.bin.part").writeBytes(bytes.take(7).toByteArray())
        ModelDownloader(root) { Response(bytes, 200) }.download(spec) { _, _ -> }
        assertArrayEquals(bytes, File(root, "model.bin").readBytes())
    }

    @Test fun rejectsWrongRangeAndNeverDeploysIt() {
        val root = folder.root
        File(root, "model.bin.part").writeBytes(bytes.take(7).toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            ModelDownloader(root) { Response(bytes, 206, "bytes 0-${bytes.lastIndex}/${bytes.size}") }
                .download(spec) { _, _ -> }
        }
        assertFalse(File(root, "model.bin").exists())
        assertEquals(7, File(root, "model.bin.part").length())
    }

    @Test fun rejectsCorruptionEvenWithCorrectSize() {
        val root = folder.root
        assertThrows(IllegalStateException::class.java) {
            ModelDownloader(root) { Response(ByteArray(bytes.size), 200) }.download(spec) { _, _ -> }
        }
        assertFalse(File(root, "model.bin").exists())
        assertFalse(File(root, "model.bin.part").exists())
    }

    @Test fun incompleteTransferKeepsPartialForNextRun() {
        val root = folder.root
        assertThrows(IllegalArgumentException::class.java) {
            ModelDownloader(root) { Response(bytes.take(7).toByteArray(), 200) }.download(spec) { _, _ -> }
        }
        assertFalse(File(root, "model.bin").exists())
        assertEquals(7, File(root, "model.bin.part").length())
        ModelDownloader(root) { Response(bytes.drop(7).toByteArray(), 206, "bytes 7-${bytes.lastIndex}/${bytes.size}") }
            .download(spec) { _, _ -> }
        assertArrayEquals(bytes, File(root, "model.bin").readBytes())
    }
}
