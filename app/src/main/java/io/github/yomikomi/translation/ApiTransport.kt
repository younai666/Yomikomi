package io.github.yomikomi.translation

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import io.github.yomikomi.logging.LogModule
import io.github.yomikomi.logging.RunLog
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

/** Shared pool outlives tiles, pages and individual responses. Never replay a billed POST. */
internal val apiHttpClient by lazy {
    OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            RunLog.info(LogModule.API, "transport.timeouts", "request" to
                (chain.request().tag(ApiTraceId::class.java)?.value ?: 0),
                "connect_ms" to chain.connectTimeoutMillis(), "read_ms" to chain.readTimeoutMillis(),
                "write_ms" to chain.writeTimeoutMillis())
            chain.proceed(chain.request())
        }
        .eventListenerFactory { ApiTransportTrace(it) }.build()
}

internal data class ApiTraceId(val value: Long, val context: Context? = null)

/** Fixed categories only: exception messages may contain hosts, credentials or response data. */
internal fun apiFailureKind(error: IOException): Int = when {
    error is SocketTimeoutException || error.message.equals("timeout", true) -> 1
    error.message.orEmpty().contains("socket closed", true) || error.message.orEmpty().contains("socket is closed", true) -> 2
    error.message.orEmpty().contains("connection reset", true) -> 3
    error.message.orEmpty().contains("connection abort", true) -> 4
    error is EOFException -> 5
    error.javaClass.simpleName == "StreamResetException" -> 6
    error is InterruptedIOException -> 7
    error is SSLException -> 8
    error.javaClass.simpleName == "ConnectionShutdownException" -> 9
    else -> 0
}

/** Observes OkHttp without reading, closing, cancelling or retaining response content. */
private class ApiTransportTrace(private val call: Call) : EventListener() {
    private val id = nextCall.incrementAndGet()
    private val request = call.request().tag(ApiTraceId::class.java)?.value ?: 0
    private val context = call.request().tag(ApiTraceId::class.java)?.context
    private val started = System.nanoTime()
    private var connecting = false
    @Volatile private var socket: Socket? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun event(name: String, vararg metrics: Pair<String, Number>) =
        RunLog.info(LogModule.API, "transport.$name", "call" to id, "request" to request,
            "elapsed_ms" to RunLog.elapsed(started), *metrics)

    private fun importance(): Int = try {
        ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState).importance
    } catch (_: RuntimeException) { -1 }

    override fun callStart(call: Call) {
        event("start", "call_timeout_ms" to TimeUnit.NANOSECONDS.toMillis(call.timeout().timeoutNanos()), "importance" to importance())
        networkState()
        if (Build.VERSION.SDK_INT >= 24 && context != null) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) = event("network_lost")
                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = event("network_blocked", "blocked" to if (blocked) 1 else 0)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = event("network_changed",
                    "vpn" to if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) 1 else 0,
                    "validated" to if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 1 else 0)
            }
            try {
                context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
                networkCallback = callback
            } catch (error: RuntimeException) { RunLog.warn(LogModule.API, "transport.network_observer_failed", error) }
        }
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connecting = true
        event("connect_start", "proxy" to proxy.type().ordinal)
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        socket = connection.socket()
        event("acquired", "connection" to System.identityHashCode(connection),
            "reused" to if (connecting) 0 else 1,
            "http2" to if (connection.protocol() == Protocol.HTTP_2) 1 else 0,
            "socket_timeout_ms" to try { connection.socket().soTimeout } catch (_: IOException) { -1 })
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) = event("request_sent", "bytes" to byteCount)

    override fun responseHeadersEnd(call: Call, response: Response) = event("headers",
        "http_status" to response.code, "http2" to if (response.protocol == Protocol.HTTP_2) 1 else 0)

    // OkHttp also emits this when an empty body is closed; first_byte is counted by the reader.
    override fun responseBodyStart(call: Call) = event("body_start")
    override fun responseBodyEnd(call: Call, byteCount: Long) = event("body_end", "bytes" to byteCount)
    override fun requestFailed(call: Call, ioe: IOException) = failure("request_failed", ioe)
    override fun responseFailed(call: Call, ioe: IOException) = failure("response_failed", ioe)
    override fun callFailed(call: Call, ioe: IOException) { failure("failed", ioe); stopObserving() }
    override fun callEnd(call: Call) { event("end"); stopObserving() }
    override fun canceled(call: Call) = event("canceled", "importance" to importance())
    override fun connectionReleased(call: Call, connection: Connection) = event("released",
        "connection" to System.identityHashCode(connection), "socket_closed" to if (connection.socket().isClosed) 1 else 0)

    private fun failure(name: String, error: IOException) {
        RunLog.warn(LogModule.API, "transport.$name", error, "call" to id, "request" to request,
            "elapsed_ms" to RunLog.elapsed(started), "kind" to apiFailureKind(error),
            "call_cancelled" to if (call.isCanceled()) 1 else 0,
            "thread_interrupted" to if (Thread.currentThread().isInterrupted) 1 else 0,
            "socket_closed" to (socket?.let { if (it.isClosed) 1 else 0 } ?: -1), "importance" to importance())
        networkState()
    }

    private fun networkState() {
        if (context == null) return
        try {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            val activity = context.getSystemService(ActivityManager::class.java)
            event("network", "available" to if (caps != null) 1 else 0,
                "vpn" to if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) 1 else 0,
                "validated" to if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 1 else 0,
                "metered" to if (manager.isActiveNetworkMetered) 1 else 0,
                "data_saver" to if (Build.VERSION.SDK_INT >= 24) manager.restrictBackgroundStatus else -1,
                "background_restricted" to if (Build.VERSION.SDK_INT >= 28) { if (activity.isBackgroundRestricted) 1 else 0 } else -1)
        } catch (error: RuntimeException) { RunLog.warn(LogModule.API, "transport.network_state_failed", error) }
    }

    private fun stopObserving() {
        val callback = networkCallback ?: return
        networkCallback = null
        try { context!!.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        catch (error: RuntimeException) { RunLog.warn(LogModule.API, "transport.network_observer_stop_failed", error) }
    }

    private companion object { val nextCall = AtomicLong() }
}
