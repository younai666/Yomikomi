package io.github.yomikomi

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yomikomi.translation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream

/** Real HTTP framing on loopback; only debug builds permit loopback cleartext, never release. */
@RunWith(AndroidJUnit4::class)
class ApiTransportDeviceTest {
    private val request = TranslationRequest("俄语", "简体中文", listOf(TranslationSegment("b7", "Привет.")), null)
    private val json = """{"choices":[{"finish_reason":"stop","message":{"content":"[b7] → 完整译文😀。"}}]}""".toByteArray()

    private fun exchange(gzip: Boolean = false, waitMs: Long = 3500,
        client: okhttp3.OkHttpClient = apiHttpClient,
        send: (Socket, ByteArray) -> Unit, test: (ApiTranslationEngine, AtomicInteger) -> Unit) {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            val calls = AtomicInteger()
            var serverError: Throwable? = null
            val worker = Thread {
                try { server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val char = input.read(); check(char >= 0); headers.append(char.toChar())
                    }
                    val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(headers)!!.groupValues[1].toInt()
                    repeat(length) { check(input.read() >= 0) }
                    val output = socket.getOutputStream()
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n" +
                        (if (gzip) "Content-Encoding: gzip\r\n" else "") + "\r\n").toByteArray())
                    output.flush()
                    val body = if (gzip) ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(json) } }.toByteArray() else json
                    send(socket, body)
                } } catch (error: Throwable) { serverError = error }
            }.apply { isDaemon = true; start() }
            try {
                val engine = ApiTranslationEngine(ApiSettings(), { "loopback-fixture-key" }, networkContext =
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext) {
                    okhttp3.Call.Factory { original ->
                        calls.incrementAndGet()
                        client.newCall(original.newBuilder().url("http://127.0.0.1:${server.localPort}/fixture").build())
                    }
                }
                test(engine, calls)
            } finally { worker.join(waitMs) }
            assertFalse("Loopback server did not finish", worker.isAlive)
            serverError?.let { throw AssertionError("Loopback fixture failed", it) }
        }
    }

    private fun chunk(socket: Socket, bytes: ByteArray) {
        socket.getOutputStream().apply {
            write("${bytes.size.toString(16)}\r\n".toByteArray()); write(bytes); write("\r\n".toByteArray()); flush()
        }
    }

    @Test fun realChunkedGzipAndSplitUtf8KeepTheCompleteTranslation() {
        exchange(gzip = true, send = { socket, body ->
            for (part in body.asList().chunked(3)) chunk(socket, part.toByteArray())
            socket.getOutputStream().write("0\r\n\r\n".toByteArray())
        }) { engine, calls ->
            assertEquals("完整译文😀。", engine.translate(request).single().text)
            assertEquals(1, calls.get())
        }
    }

    @Test fun completeJsonWithoutFinalChunkTrailerStillSucceeds() {
        exchange(send = { socket, body -> chunk(socket, body) }) { engine, calls ->
            assertEquals("完整译文😀。", engine.translate(request).single().text)
            assertEquals(1, calls.get())
        }
    }

    @Test fun http200ThenResetBeforeFirstChunkFailsOnceWithoutReplaying() {
        exchange(send = { socket, _ -> Thread.sleep(40); socket.setSoLinger(true, 0) }) { engine, calls ->
            assertThrows(IllegalStateException::class.java) { engine.translate(request) }
            assertEquals(1, calls.get())
            assertTrue(runtimeLogSnapshot().contains("API transport_failed stage=4 http_status=200"))
        }
    }

    @Test fun resetDuringJsonCannotPublishPartialOutput() {
        exchange(send = { socket, body -> chunk(socket, body.copyOf(body.size / 2)); Thread.sleep(40); socket.setSoLinger(true, 0) }) { engine, calls ->
            assertThrows(IllegalStateException::class.java) { engine.translate(request) }
            assertEquals(1, calls.get())
        }
    }

    @Test fun responseMayWaitBeyondTenSecondsWithoutClientClosingOrCancellingIt() {
        exchange(waitMs = 15_000, send = { socket, body ->
            Thread.sleep(11_000)
            chunk(socket, body)
            socket.getOutputStream().write("0\r\n\r\n".toByteArray())
        }) { engine, calls ->
            val started = System.nanoTime()
            assertEquals("完整译文😀。", engine.translate(request.copy(traceId = 711)).single().text)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 11_000)
            assertEquals(1, calls.get())
            val log = runtimeLogSnapshot()
            assertTrue(log.contains("read_ms=60000 write_ms=10000"))
            assertFalse(log.lineSequence().any { it.contains("transport.canceled") && it.contains("request=711 ") })
        }
    }

    @Test fun bodyReadTimeoutIsReportedAsTimeoutRatherThanSocketReset() {
        val client = apiHttpClient.newBuilder().readTimeout(100, TimeUnit.MILLISECONDS).build()
        exchange(client = client, send = { _, _ -> Thread.sleep(350) }) { engine, calls ->
            assertThrows(IllegalStateException::class.java) { engine.translate(request.copy(traceId = 712)) }
            assertEquals(1, calls.get())
            assertTrue(runtimeLogSnapshot().lineSequence().any {
                it.contains("transport_failed stage=4") && it.contains("request=712 ") && it.contains("kind=1")
            })
        }
    }

    @Test fun consecutiveHttp1RequestsReuseTheSocketOnlyAfterCompleteResponseConsumption() {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            var serverError: Throwable? = null
            val worker = Thread {
                try { server.accept().use { socket ->
                    socket.soTimeout = 3000
                    repeat(2) {
                        val input = socket.getInputStream()
                        val headers = StringBuilder()
                        while (!headers.endsWith("\r\n\r\n")) {
                            val char = input.read(); check(char >= 0); headers.append(char.toChar())
                        }
                        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(headers)!!.groupValues[1].toInt()
                        repeat(length) { check(input.read() >= 0) }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${json.size}\r\n\r\n".toByteArray())
                            write(json); flush()
                        }
                    }
                } } catch (error: Throwable) { serverError = error }
            }.apply { isDaemon = true; start() }
            val client = apiHttpClient.newBuilder().connectionPool(okhttp3.ConnectionPool())
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build()
            try {
                repeat(2) { index ->
                    val engine = ApiTranslationEngine(ApiSettings(), { "loopback-fixture-key" }) {
                        okhttp3.Call.Factory { original -> client.newCall(original.newBuilder()
                            .url("http://127.0.0.1:${server.localPort}/fixture").build()) }
                    }
                    assertEquals("完整译文😀。", engine.translate(request.copy(traceId = 720L + index)).single().text)
                }
            } finally { worker.join(3500); client.connectionPool.evictAll() }
            assertFalse(worker.isAlive)
            serverError?.let { throw AssertionError("Reuse fixture failed", it) }
            val log = runtimeLogSnapshot()
            assertTrue(log.lineSequence().any { it.contains("transport.acquired") && it.contains("request=721 ") && it.contains("reused=1") })
        }
    }

    @Test fun explicitCancellationStopsOnlyItsOwnBlockedBodyRead() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = apiHttpClient.newBuilder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            reading.countDown()
            response
        }.build()
        exchange(client = client, send = { _, _ -> release.await(3, TimeUnit.SECONDS) }) { engine, calls ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val pending = worker.submit<List<TranslationSegment>> { engine.translate(request.copy(traceId = 713)) }
                assertTrue(reading.await(2, TimeUnit.SECONDS))
                engine.cancel()
                val failure = assertThrows(ExecutionException::class.java) { pending.get(2, TimeUnit.SECONDS) }
                assertEquals("已取消", failure.cause!!.message)
                assertEquals(1, calls.get())
                // Same shared client still serves a different call after cancellation.
                exchange(send = { socket, body -> chunk(socket, body) }) { other, _ ->
                    assertEquals("完整译文😀。", other.translate(request.copy(traceId = 714)).single().text)
                }
                assertTrue(runtimeLogSnapshot().lineSequence().any {
                    it.contains("transport.canceled") && it.contains("request=713 ")
                })
            } finally { release.countDown(); worker.shutdownNow() }
        }
    }
}
