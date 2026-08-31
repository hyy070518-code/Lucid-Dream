package com.huyang.luciddream.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHealthClientTest {
    private val client = AgentHealthClient(OkHttpClient())

    @Test
    fun statusOkReturnsSuccess() = withHealthServer(200, """{"status":"ok"}""") { url ->
        val result = runBlocking { client.checkAt(url) }

        assertEquals(AgentHealthResult.Success, result)
    }

    @Test
    fun malformedJsonReturnsDiagnosticFailure() = withHealthServer(200, "not-json") { url ->
        val result = runBlocking { client.checkAt(url) }

        assertTrue(result is AgentHealthResult.Failure)
        assertEquals("响应不是有效的 health JSON", (result as AgentHealthResult.Failure).detail)
    }

    @Test
    fun httpErrorReturnsStatusCode() = withHealthServer(503, "{}") { url ->
        val result = runBlocking { client.checkAt(url) }

        assertTrue(result is AgentHealthResult.Failure)
        assertEquals("Agent 服务返回 HTTP 503", (result as AgentHealthResult.Failure).detail)
    }

    private fun withHealthServer(
        responseCode: Int,
        responseBody: String,
        test: (String) -> Unit,
    ) {
        val server = ServerSocket()
        server.bind(
            InetSocketAddress(
                InetAddress.getByName("127.0.0.1"),
                0,
            ),
        )
        val serverThread = thread(name = "agent-health-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    writeResponse(socket, responseCode, responseBody)
                }
            }
        }

        try {
            test("http://127.0.0.1:${server.localPort}/health")
        } finally {
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun readRequest(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
    }

    private fun writeResponse(socket: Socket, responseCode: Int, responseBody: String) {
        val body = responseBody.toByteArray(Charsets.UTF_8)
        val reason = if (responseCode == 200) "OK" else "Service Unavailable"
        val headers = buildString {
            append("HTTP/1.1 $responseCode $reason\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)

        socket.getOutputStream().use { output ->
            output.write(headers)
            output.write(body)
            output.flush()
        }
    }
}
