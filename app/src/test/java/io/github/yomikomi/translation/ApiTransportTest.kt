package io.github.yomikomi.translation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

class ApiTransportTest {
    @Test fun failureCategoriesDistinguishTimeoutAndSocketTerminationWithoutReturningMessages() {
        assertEquals(1, apiFailureKind(SocketTimeoutException("private")))
        assertEquals(1, apiFailureKind(InterruptedIOException("timeout")))
        assertEquals(2, apiFailureKind(SocketException("Socket closed")))
        assertEquals(2, apiFailureKind(SocketException("Socket is closed")))
        assertEquals(3, apiFailureKind(SocketException("recvfrom failed: ECONNRESET (Connection reset by peer)")))
        assertEquals(4, apiFailureKind(SocketException("Software caused connection abort")))
        assertEquals(5, apiFailureKind(EOFException("private")))
        assertEquals(7, apiFailureKind(InterruptedIOException("private")))
        assertEquals(8, apiFailureKind(SSLException("private")))
        assertEquals(0, apiFailureKind(IOException("https://private.example/sk-private")))
    }
}
