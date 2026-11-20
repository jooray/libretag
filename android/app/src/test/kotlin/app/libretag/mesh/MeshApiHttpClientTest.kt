package app.libretag.mesh

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MeshApiHttpClientTest {

    private class Recorder : WebSocketListener() {
        val opened = CountDownLatch(1)
        val failed = CountDownLatch(1)
        @Volatile var failure: Throwable? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failure = t
            failed.countDown()
        }
    }

    private fun server(handle: (Socket) -> Unit): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            try {
                server.accept().use(handle)
            } catch (_: Exception) {
            }
        }
        return server
    }

    @Test
    fun `an upgrade that never answers fails instead of hanging`() {
        // A frozen MeshCore Open: the kernel accepts the connection, nobody answers.
        val server = server { Thread.sleep(10_000) }
        val client = MeshCoreApiClient.httpClient(handshakeTimeoutMs = 300, pingIntervalMs = 0)
        val recorder = Recorder()
        val request = Request.Builder().url("ws://127.0.0.1:${server.localPort}/api/v1").build()
        client.newWebSocket(request, recorder)
        try {
            assertTrue("no failure within 5 s", recorder.failed.await(5, TimeUnit.SECONDS))
            assertNotNull(recorder.failure)
        } finally {
            server.close()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test
    fun `a quiet socket after the upgrade outlives the handshake timeout`() {
        val server = server { socket ->
            val reader = socket.getInputStream().bufferedReader()
            var key: String? = null
            while (true) {
                val line = reader.readLine() ?: return@server
                if (line.isEmpty()) break
                if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                    key = line.substringAfter(':').trim()
                }
            }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
            )
            socket.getOutputStream().write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                    "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
            )
            socket.getOutputStream().flush()
            Thread.sleep(10_000)
        }
        val client = MeshCoreApiClient.httpClient(handshakeTimeoutMs = 300, pingIntervalMs = 0)
        val recorder = Recorder()
        val request = Request.Builder().url("ws://127.0.0.1:${server.localPort}/api/v1").build()
        val ws = client.newWebSocket(request, recorder)
        try {
            assertTrue("upgrade did not complete", recorder.opened.await(5, TimeUnit.SECONDS))
            // Several handshake timeouts pass without traffic; the socket must stay up.
            assertEquals(false, recorder.failed.await(1_500, TimeUnit.MILLISECONDS))
            assertNull(recorder.failure)
        } finally {
            ws.cancel()
            server.close()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
