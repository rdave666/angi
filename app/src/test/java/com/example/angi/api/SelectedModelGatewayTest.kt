package com.example.angi.api

import com.example.angi.data.settings.AngiSettings
import com.example.angi.data.settings.InferenceSource
import com.example.angi.domain.inference.FakeInferenceEngine
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SelectedModelGatewayTest {
    private var server: OpenAiHttpServer? = null

    @After fun tearDown() { server?.stop() }

    private fun start(settings: () -> AngiSettings, requireAuth: Boolean = false): Int {
        server = OpenAiHttpServer(
            port = 0,
            apiKey = "android-lan-key",
            guestApiKey = "debian-guest-key",
            requireAuth = requireAuth,
            inferenceEngine = FakeInferenceEngine(listOf("local")),
            settingsProvider = settings
        )
        return server!!.start()
    }

    private fun external(port: Int) = AngiSettings(
        inferenceSource = InferenceSource.EXTERNAL,
        externalApiBaseUrl = "http://127.0.0.1:$port/v1",
        externalApiKey = "provider-secret-NEVER-IN-DEBIAN",
        externalModelId = "remote-code-model"
    )

    private fun request(port: Int, path: String, body: String? = null, token: String? = null): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 10000
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        return code to text
    }

    /** One-request upstream with no testing-framework dependency on JDK HTTP server internals. */
    private class Upstream(private val reply: String, private val contentType: String = "application/json") : AutoCloseable {
        private val socket = ServerSocket(0)
        val port: Int = socket.localPort
        val captured = AtomicReference<String>("")
        val failure = AtomicReference<Throwable?>(null)
        private val thread = Thread {
            try {
                socket.accept().use { peer ->
                    peer.soTimeout = 10000
                    val input = peer.getInputStream()
                    val headerBytes = ByteArrayOutputStream()
                    var state = 0
                    while (state != 4) {
                        val b = input.read()
                        if (b == -1) error("Unexpected request EOF")
                        headerBytes.write(b)
                        state = when (state) {
                            0 -> if (b == 13) 1 else 0
                            1 -> if (b == 10) 2 else 0
                            2 -> if (b == 13) 3 else 0
                            else -> if (b == 10) 4 else 0
                        }
                    }
                    val header = headerBytes.toString("ISO-8859-1")
                    val length = Regex("(?im)^content-length:\\s*(\\d+)").find(header)
                        ?.groupValues?.get(1)?.toInt() ?: 0
                    val bytes = ByteArray(length)
                    var read = 0
                    while (read < bytes.size) {
                        val n = input.read(bytes, read, bytes.size - read)
                        if (n < 0) error("Truncated request body")
                        read += n
                    }
                    captured.set(header + String(bytes, Charsets.UTF_8))
                    val payload = reply.toByteArray(Charsets.UTF_8)
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                    peer.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                    peer.getOutputStream().write(payload)
                    peer.getOutputStream().flush()
                }
            } catch (e: Throwable) { failure.set(e) }
        }.apply { isDaemon = true; start() }

        override fun close() {
            socket.close()
            thread.join(4000)
            failure.get()?.let { throw AssertionError("Upstream failed", it) }
        }
    }

    @Test fun externalModelDiscoveryAndHealthFollowAndroidSelection() {
        var settings = AngiSettings()
        val port = start({ settings })
        assertEquals(0, JSONObject(request(port, "/v1/models").second).getJSONArray("data").length())
        settings = external(12345)
        val (code, json) = request(port, "/v1/models")
        assertEquals(200, code)
        val ids = JSONObject(json).getJSONArray("data")
        assertEquals("remote-code-model", ids.getJSONObject(0).getString("id"))
        assertEquals("angi-active-model", ids.getJSONObject(1).getString("id"))
        assertTrue(JSONObject(request(port, "/health").second).getBoolean("model_loaded"))
        assertFalse(json.contains("provider-secret"))
    }

    @Test fun forwardsToolCallsAndStreamingBytesWithoutDisclosingProviderKey() {
        val sse = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"shell\"}}]}}]}\n\ndata: [DONE]\n\n"
        Upstream(sse, "text/event-stream").use { upstream ->
            val port = start({ external(upstream.port) }, requireAuth = true)
            val body = """{"model":"angi-active-model","stream":true,"messages":[{"role":"user","content":"edit"}],"tools":[{"type":"function","function":{"name":"shell","parameters":{"type":"object"}}}]}"""
            assertEquals(401, request(port, "/v1/chat/completions", body).first)
            val (code, result) = request(port, "/v1/chat/completions", body, "debian-guest-key")
            assertEquals(200, code)
            assertEquals(sse, result)
            val seen = upstream.captured.get()
            assertTrue(seen.contains("POST /v1/chat/completions"))
            assertTrue(seen.contains("Bearer provider-secret-NEVER-IN-DEBIAN"))
            val forwarded = JSONObject(seen.substringAfter("\r\n\r\n"))
            assertEquals("remote-code-model", forwarded.getString("model"))
            assertTrue(forwarded.getBoolean("stream"))
            assertEquals("shell", forwarded.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
            assertFalse(result.contains("provider-secret"))
        }
    }

    @Test fun proxiesResponsesEndpointToConfiguredExternalProvider() {
        Upstream("""{"id":"resp_1","output":[]}""").use { upstream ->
            val port = start({ external(upstream.port) })
            val (status, output) = request(port, "/v1/responses",
                """{"model":"angi-active-model","input":"hello","tools":[{"type":"function","name":"shell","parameters":{"type":"object"}}]}""")
            assertEquals(200, status)
            assertEquals("resp_1", JSONObject(output).getString("id"))
            val seen = upstream.captured.get()
            assertTrue(seen.startsWith("POST /v1/responses"))
            val sent = JSONObject(seen.substringAfter("\r\n\r\n"))
            assertEquals("remote-code-model", sent.getString("model"))
            assertEquals("shell", sent.getJSONArray("tools").getJSONObject(0).getString("name"))
        }
    }

    @Test fun preservesGlmV4EndpointWithoutDuplicatingV1() {
        Upstream("""{"choices":[{"message":{"content":"ok"}}]}""").use { upstream ->
            val settings = external(upstream.port).copy(
                externalApiBaseUrl = "http://127.0.0.1:${upstream.port}/api/paas/v4"
            )
            val port = start({ settings })
            val (code, response) = request(port, "/v1/chat/completions",
                """{"model":"angi-active-model","messages":[{"role":"user","content":"test"}]}""")
            assertEquals(200, code)
            assertTrue(response.contains("choices"))
            assertTrue(upstream.captured.get().startsWith("POST /api/paas/v4/chat/completions"))
            assertFalse(upstream.captured.get().contains("/v4/v1/"))
        }
    }

    @Test fun rejectsUnknownModelsAndUnconfiguredProviders() {
        val port = start({ AngiSettings(inferenceSource = InferenceSource.EXTERNAL) })
        assertEquals(503, request(port, "/v1/chat/completions", """{"model":"angi-active-model"}""").first)
        server?.stop()
        val port2 = start({ external(12345) })
        val (status, response) = request(port2, "/v1/chat/completions", """{"model":"other-model"}""")
        assertEquals(400, status)
        assertTrue(response.contains("invalid_request_error"))
    }

    @Test fun debianTokenCannotOverrideWrongTokenAndExternalCredentialsAreNeverExposedInModels() {
        val port = start({ external(12345) }, requireAuth = true)
        assertEquals(401, request(port, "/v1/models", token = "wrong").first)
        assertEquals(200, request(port, "/v1/models", token = "debian-guest-key").first)
        assertEquals(200, request(port, "/v1/models", token = "android-lan-key").first)
        assertFalse(request(port, "/v1/models", token = "debian-guest-key").second.contains("provider-secret"))
    }
}
