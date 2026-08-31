package com.huyang.luciddream.network

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskClientTest {
    private val client = AgentTaskClient(OkHttpClient())

    @Test
    fun acceptedResponseReturnsTaskIdAndSendsBearerToken() = withTaskServer(
        responseCode = 202,
        responseBody = """{"task_id":"abc","status":"queued"}""",
    ) { url, capturedRequest ->
        val result = runBlocking {
            client.submitAt(url, "打开系统设置", "test-token")
        }

        assertEquals(AgentTaskSubmitResult.Submitted("abc", "queued"), result)
        assertEquals("POST /tasks HTTP/1.1", capturedRequest().requestLine)
        assertEquals("Bearer test-token", capturedRequest().headers["authorization"])
        assertEquals(
            "打开系统设置",
            Json.parseToJsonElement(capturedRequest().body)
                .jsonObject["task"]
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun malformedJsonReturnsDiagnosticFailure() = withTaskServer(202, "not-json") { url, _ ->
        val result = runBlocking { client.submitAt(url, "task", "token") }

        assertEquals(
            AgentTaskSubmitResult.Failure("Agent 服务响应 JSON 无法解析"),
            result,
        )
    }

    @Test
    fun unauthorizedReturnsAuthenticationFailure() = withTaskServer(401, "{}") { url, _ ->
        val result = runBlocking { client.submitAt(url, "task", "token") }

        assertEquals(
            AgentTaskSubmitResult.Failure("Agent 服务鉴权失败（HTTP 401）"),
            result,
        )
    }

    @Test
    fun busyReturnsDiagnosticFailure() = withTaskServer(409, "{}") { url, _ ->
        val result = runBlocking { client.submitAt(url, "task", "token") }

        assertEquals(
            AgentTaskSubmitResult.Failure("Agent 服务正忙（HTTP 409）"),
            result,
        )
    }

    @Test
    fun serverErrorReturnsDiagnosticFailure() = withTaskServer(500, "{}") { url, _ ->
        val result = runBlocking { client.submitAt(url, "task", "token") }

        assertEquals(
            AgentTaskSubmitResult.Failure("Agent 服务内部错误（HTTP 500）"),
            result,
        )
    }

    @Test
    fun emptyTaskFailsBeforeNetworkRequest() {
        val result = runBlocking {
            client.submitAt("http://127.0.0.1:1/tasks", "   ", "token")
        }

        assertEquals(AgentTaskSubmitResult.Failure("任务不能为空"), result)
    }

    @Test
    fun statusQueryParsesQueuedAndSendsBearerToken() = withTaskServer(
        responseCode = 200,
        responseBody = """{"task_id":"task-1","status":"queued"}""",
    ) { url, capturedRequest ->
        val result = runBlocking {
            client.getStatusAt("$url/task-1", "task-1", "test-token")
        }

        assertEquals(
            AgentTaskStatusResult.Success(
                AgentTaskStatusSnapshot("task-1", AgentServerTaskStatus.QUEUED, null),
            ),
            result,
        )
        assertEquals("GET /tasks/task-1 HTTP/1.1", capturedRequest().requestLine)
        assertEquals("Bearer test-token", capturedRequest().headers["authorization"])
    }

    @Test
    fun statusQueryUnauthorizedReturnsAuthenticationFailure() = withTaskServer(401, "{}") { url, _ ->
        val result = runBlocking {
            client.getStatusAt("$url/task-1", "task-1", "token")
        }

        assertEquals(
            AgentTaskStatusResult.Failure("Agent 服务鉴权失败（HTTP 401）"),
            result,
        )
    }

    @Test
    fun missingTaskReturnsNotFoundFailure() = withTaskServer(404, "{}") { url, _ ->
        val result = runBlocking {
            client.getStatusAt("$url/missing", "missing", "token")
        }

        assertEquals(
            AgentTaskStatusResult.Failure("Agent 任务不存在（HTTP 404）"),
            result,
        )
    }

    @Test
    fun malformedStatusJsonReturnsDiagnosticFailure() = withTaskServer(200, "not-json") { url, _ ->
        val result = runBlocking {
            client.getStatusAt("$url/task-1", "task-1", "token")
        }

        assertEquals(
            AgentTaskStatusResult.Failure("Agent 任务状态 JSON 无法解析"),
            result,
        )
    }

    @Test
    fun unknownTaskStatusReturnsDiagnosticFailure() = withTaskServer(
        responseCode = 200,
        responseBody = """{"task_id":"task-1","status":"mystery"}""",
    ) { url, _ ->
        val result = runBlocking {
            client.getStatusAt("$url/task-1", "task-1", "token")
        }

        assertEquals(
            AgentTaskStatusResult.Failure("Agent 服务返回未知任务状态：mystery"),
            result,
        )
    }

    private fun withTaskServer(
        responseCode: Int,
        responseBody: String,
        test: (String, () -> CapturedRequest) -> Unit,
    ) {
        val server = ServerSocket()
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val capturedRequest = AtomicReference<CapturedRequest?>()
        val serverError = AtomicReference<Throwable?>()
        val serverThread = thread(name = "agent-task-test-server") {
            try {
                server.accept().use { socket ->
                    capturedRequest.set(readRequest(socket))
                    writeResponse(socket, responseCode, responseBody)
                }
            } catch (error: Throwable) {
                if (!server.isClosed) serverError.set(error)
            }
        }

        try {
            test(
                "http://127.0.0.1:${server.localPort}/tasks",
                {
                    capturedRequest.get()
                        ?: error("The local test server did not capture a request")
                },
            )
        } finally {
            server.close()
            serverThread.join(1_000)
        }

        serverError.get()?.let { throw AssertionError("Local test server failed", it) }
        assertTrue("Local test server thread did not stop", !serverThread.isAlive)
    }

    private fun readRequest(socket: Socket): CapturedRequest {
        val input = socket.getInputStream()
        val headerBytes = ByteArrayOutputStream()
        var matched = 0

        while (matched < HEADER_TERMINATOR.size) {
            val next = input.read()
            check(next >= 0) { "Unexpected end of HTTP request headers" }
            headerBytes.write(next)
            matched = if (next.toByte() == HEADER_TERMINATOR[matched]) matched + 1 else 0
        }

        val headerLines = headerBytes.toString(Charsets.US_ASCII).trim().lines()
        val headers = headerLines.drop(1).associate { line ->
            val separator = line.indexOf(':')
            check(separator > 0) { "Malformed HTTP header" }
            line.substring(0, separator).lowercase() to line.substring(separator + 1).trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = input.readNBytes(contentLength).toString(Charsets.UTF_8)

        return CapturedRequest(
            requestLine = headerLines.first(),
            headers = headers,
            body = body,
        )
    }

    private fun writeResponse(socket: Socket, responseCode: Int, responseBody: String) {
        val body = responseBody.toByteArray(Charsets.UTF_8)
        val reason = when (responseCode) {
            200 -> "OK"
            202 -> "Accepted"
            401 -> "Unauthorized"
            404 -> "Not Found"
            409 -> "Conflict"
            else -> "Internal Server Error"
        }
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

    private data class CapturedRequest(
        val requestLine: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private companion object {
        val HEADER_TERMINATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    }
}
