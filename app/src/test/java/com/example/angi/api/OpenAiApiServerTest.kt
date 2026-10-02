package com.example.angi.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.AngiApp
import com.example.angi.domain.inference.FakeInferenceEngine
import com.example.angi.domain.inference.RuntimeState
import com.example.angi.domain.models.ComputeUnit
import com.example.angi.domain.models.ModelDescriptor
import com.example.angi.domain.models.ModelFormat
import com.example.angi.domain.models.RuntimeType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class OpenAiApiServerTest {

    private lateinit var fakeEngine: FakeInferenceEngine
    private lateinit var modelA: ModelDescriptor
    private lateinit var modelB: ModelDescriptor
    private var server: OpenAiHttpServer? = null
    private var originalIpDetector = NetworkUtils.ipDetector

    @Before
    fun setup() {
        originalIpDetector = NetworkUtils.ipDetector
        fakeEngine = FakeInferenceEngine(listOf("Hello", " world", " from", " ANGI"))
        modelA = ModelDescriptor(
            id = "qwen_0_6b",
            name = "Qwen 0.6B Test",
            family = "Qwen",
            format = ModelFormat.GGUF,
            runtime = RuntimeType.LLAMA_CPP,
            preferredCompute = ComputeUnit.CPU,
            contextLength = 2048,
            modelPath = "/tmp/qwen.gguf",
            tokenizerPath = "",
            fileSizeBytes = 600_000_000L,
            isBundled = false,
            description = "Test model A"
        )
        modelB = ModelDescriptor(
            id = "llama_3_2_1b",
            name = "Llama 3.2 1B Test",
            family = "Llama",
            format = ModelFormat.GGUF,
            runtime = RuntimeType.LLAMA_CPP,
            preferredCompute = ComputeUnit.CPU,
            contextLength = 4096,
            modelPath = "/tmp/llama.gguf",
            tokenizerPath = "",
            fileSizeBytes = 1_200_000_000L,
            isBundled = false,
            description = "Test model B"
        )
    }

    @After
    fun tearDown() {
        NetworkUtils.ipDetector = originalIpDetector
        server?.stop()
        server = null
    }

    private fun startServer(
        host: String = "127.0.0.1",
        requireAuth: Boolean = false,
        apiKey: String = "test-key"
    ): Int {
        val s = OpenAiHttpServer(
            host = host,
            port = 0, // dynamic ephemeral port
            apiKey = apiKey,
            requireAuth = requireAuth,
            inferenceEngine = fakeEngine
        )
        val port = s.start()
        server = s
        return port
    }

    @Test
    fun `health endpoint returns 200 and model status`() {
        val port = startServer()
        val url = URL("http://127.0.0.1:$port/health")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        assertEquals(200, conn.responseCode)
        val body = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(body)
        assertEquals("ok", json.getString("status"))
        assertFalse(json.getBoolean("model_loaded"))

        // Load model and check health again
        runBlocking { fakeEngine.loadModel(modelA) }
        val conn2 = url.openConnection() as HttpURLConnection
        assertEquals(200, conn2.responseCode)
        val json2 = JSONObject(conn2.inputStream.bufferedReader().readText())
        assertTrue(json2.getBoolean("model_loaded"))
        assertEquals("qwen_0_6b", json2.getString("model"))
    }

    @Test
    fun `v1 models endpoint with loaded model returns model details and alias`() {
        runBlocking { fakeEngine.loadModel(modelA) }
        val port = startServer()
        val url = URL("http://127.0.0.1:$port/v1/models")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        assertEquals(200, conn.responseCode)
        val body = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(body)
        assertEquals("list", json.getString("object"))
        val data = json.getJSONArray("data")
        assertEquals(2, data.length())

        val ids = listOf(data.getJSONObject(0).getString("id"), data.getJSONObject(1).getString("id"))
        assertTrue("Must include active model ID", ids.contains("qwen_0_6b"))
        assertTrue("Must include stable alias angi-loaded-model", ids.contains("angi-loaded-model"))
    }

    @Test
    fun `v1 models endpoint with no model returns empty model list`() {
        val port = startServer()
        val url = URL("http://127.0.0.1:$port/v1/models")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        assertEquals(200, conn.responseCode)
        val body = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(body)
        assertEquals("list", json.getString("object"))
        val data = json.getJSONArray("data")
        assertEquals(0, data.length())
    }

    @Test
    fun `model change reflected dynamically without API restart`() {
        runBlocking { fakeEngine.loadModel(modelA) }
        val port = startServer()

        val url = URL("http://127.0.0.1:$port/v1/models")
        val conn1 = url.openConnection() as HttpURLConnection
        assertEquals(200, conn1.responseCode)
        val json1 = JSONObject(conn1.inputStream.bufferedReader().readText())
        assertEquals("qwen_0_6b", json1.getJSONArray("data").getJSONObject(0).getString("id"))

        // Unload Model A (no server restart)
        runBlocking { fakeEngine.unloadModel() }
        val conn2 = url.openConnection() as HttpURLConnection
        assertEquals(200, conn2.responseCode)
        val json2 = JSONObject(conn2.inputStream.bufferedReader().readText())
        assertEquals(0, json2.getJSONArray("data").length())

        // Load Model B (no server restart)
        runBlocking { fakeEngine.loadModel(modelB) }
        val conn3 = url.openConnection() as HttpURLConnection
        assertEquals(200, conn3.responseCode)
        val json3 = JSONObject(conn3.inputStream.bufferedReader().readText())
        assertEquals("llama_3_2_1b", json3.getJSONArray("data").getJSONObject(0).getString("id"))
    }

    @Test
    fun `LAN bind uses 0_0_0_0 internally but client endpoint never exposes 0_0_0_0`() {
        NetworkUtils.ipDetector = { "192.168.1.188" }
        val port = startServer(host = "0.0.0.0", requireAuth = true, apiKey = "test-lan-key")

        assertEquals("0.0.0.0", server!!.host)
        assertTrue(server!!.isRunning)

        val lanIp = NetworkUtils.getLocalIpv4Address()
        assertEquals("192.168.1.188", lanIp)

        // Verify computed client endpoint never contains 0.0.0.0
        val clientEndpoint = "http://$lanIp:$port/v1"
        assertFalse(clientEndpoint.contains("0.0.0.0"))
        assertTrue(clientEndpoint.startsWith("http://192.168.1.188"))
    }

    @Test
    fun `LAN endpoint uses detected device IP or reports unavailable`() {
        // 1. IP detected
        NetworkUtils.ipDetector = { "10.0.0.42" }
        val detected = NetworkUtils.getLocalIpv4Address()
        assertEquals("10.0.0.42", detected)
        val endpointWithIp = "http://$detected:8080/v1"
        assertEquals("http://10.0.0.42:8080/v1", endpointWithIp)
        assertFalse(endpointWithIp.contains("0.0.0.0"))

        // 2. IP unavailable
        NetworkUtils.ipDetector = { null }
        val noIp = NetworkUtils.getLocalIpv4Address()
        assertEquals(null, noIp)
        val fallbackEndpoint = if (noIp != null) "http://$noIp:8080/v1" else "LAN address unavailable"
        assertEquals("LAN address unavailable", fallbackEndpoint)
        assertFalse(fallbackEndpoint.contains("0.0.0.0"))
    }

    @Test
    fun `accepted active model ID and angi-loaded-model alias succeed`() {
        runBlocking { fakeEngine.loadModel(modelA) }
        val port = startServer()

        // 1. Explicit active model ID
        val url = URL("http://127.0.0.1:$port/v1/chat/completions")
        val conn1 = url.openConnection() as HttpURLConnection
        conn1.requestMethod = "POST"
        conn1.setRequestProperty("Content-Type", "application/json")
        conn1.doOutput = true
        conn1.outputStream.bufferedWriter().use {
            it.write("{\"model\":\"qwen_0_6b\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
        }
        assertEquals(200, conn1.responseCode)
        conn1.inputStream.bufferedReader().readText()
        conn1.disconnect()

        // 2. Stable alias "angi-loaded-model"
        val conn2 = url.openConnection() as HttpURLConnection
        conn2.requestMethod = "POST"
        conn2.setRequestProperty("Content-Type", "application/json")
        conn2.doOutput = true
        conn2.outputStream.bufferedWriter().use {
            it.write("{\"model\":\"angi-loaded-model\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
        }
        assertEquals(200, conn2.responseCode)
        conn2.inputStream.bufferedReader().readText()
        conn2.disconnect()
    }

    @Test
    fun `wrong model ID returns 400 error`() {
        runBlocking { fakeEngine.loadModel(modelA) }
        val port = startServer()

        val url = URL("http://127.0.0.1:$port/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.bufferedWriter().use {
            it.write("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
        }

        assertEquals(400, conn.responseCode)
        val body = conn.errorStream.bufferedReader().readText()
        val json = JSONObject(body)
        assertTrue(json.has("error"))
        val error = json.getJSONObject("error")
        assertEquals("invalid_request_error", error.getString("type"))
        assertTrue("Error message must mention requested model", error.getString("message").contains("gpt-4o"))
    }

    @Test
    fun `UTF-8 non-ASCII request body with exact byte Content-Length is parsed correctly`() {
        runBlocking { fakeEngine.loadModel(modelA) }
        val port = startServer()

        val nonAsciiContent = "こんにちは世界 🌍 Привет мир! ✨ Multi-byte test."
        val jsonPayload = JSONObject().apply {
            put("model", "qwen_0_6b")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", nonAsciiContent)
                })
            })
            put("stream", false)
        }.toString()

        val bodyBytes = jsonPayload.toByteArray(Charsets.UTF_8)
        // Verify byte length != character length
        assertNotEquals(jsonPayload.length, bodyBytes.size)

        val socket = Socket("127.0.0.1", port)
        val reqHeaders = "POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n\r\n"

        val out = socket.getOutputStream()
        out.write(reqHeaders.toByteArray(Charsets.US_ASCII))
        out.write(bodyBytes)
        out.flush()

        val responseReader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val statusLine = responseReader.readLine()
        assertTrue("Status line must be 200 OK: $statusLine", statusLine.contains("200 OK"))
        socket.close()
    }

    @Test
    fun `streaming disconnect cancels inference and releases inference lock`() = runBlocking {
        runBlocking { fakeEngine.loadModel(modelA) }
        fakeEngine.tokenDelayMs = 150L // Introduce token delay to test active generation cancel
        val port = startServer()

        val socket = Socket("127.0.0.1", port)
        val body = "{\"model\":\"qwen_0_6b\",\"messages\":[{\"role\":\"user\",\"content\":\"stream-disconnect\"}],\"stream\":true}"
        val req = "POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n\r\n" +
                body

        val out = socket.getOutputStream()
        out.write(req.toByteArray())
        out.flush()

        // Read initial SSE headers and first chunk
        val input = socket.getInputStream()
        val buf = ByteArray(128)
        val read = input.read(buf)
        assertTrue(read > 0)

        val initialCancelCount = fakeEngine.cancelCalledCount

        // Abruptly close socket while streaming tokens are in flight
        socket.close()
        delay(250)

        assertTrue(
            "Inference engine cancel must be triggered on streaming disconnect",
            fakeEngine.cancelCalledCount > initialCancelCount
        )

        // Verify inference lock is released: next request must succeed and NOT return 429
        val connNext = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
        connNext.requestMethod = "POST"
        connNext.setRequestProperty("Content-Type", "application/json")
        connNext.doOutput = true
        connNext.outputStream.bufferedWriter().use {
            it.write("{\"model\":\"qwen_0_6b\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"stream\":false}")
        }
        assertEquals("Subsequent request must succeed after disconnect", 200, connNext.responseCode)
    }

    @Test
    fun `non-streaming disconnect cancels inference and releases inference lock`() = runBlocking {
        runBlocking { fakeEngine.loadModel(modelA) }
        fakeEngine.tokenDelayMs = 250L // Delay during generation before sending completion response
        val port = startServer()

        val socket = Socket("127.0.0.1", port)
        val body = "{\"model\":\"qwen_0_6b\",\"messages\":[{\"role\":\"user\",\"content\":\"non-stream-disconnect\"}],\"stream\":false}"
        val req = "POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n\r\n" +
                body

        val out = socket.getOutputStream()
        out.write(req.toByteArray())
        out.flush()

        val initialCancelCount = fakeEngine.cancelCalledCount

        // Close socket immediately while model is generating tokens (before response is written)
        delay(50)
        socket.close()
        delay(350)

        assertTrue(
            "Inference engine cancel must be triggered on non-streaming disconnect",
            fakeEngine.cancelCalledCount > initialCancelCount
        )

        // Verify inference lock is released: next request must succeed and NOT return 429
        val connNext = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
        connNext.requestMethod = "POST"
        connNext.setRequestProperty("Content-Type", "application/json")
        connNext.doOutput = true
        connNext.outputStream.bufferedWriter().use {
            it.write("{\"model\":\"qwen_0_6b\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"stream\":false}")
        }
        assertEquals("Subsequent request must succeed after non-streaming disconnect", 200, connNext.responseCode)
    }

    @Test
    fun `persisted enabled state starts service after restart and failed start reports error`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepo = AngiApp.instance.settingsRepository

        // 1. Update settings to enabled
        val initial = settingsRepo.settings.value
        settingsRepo.updateSettings(initial.copy(isApiServerEnabled = true))
        assertTrue(settingsRepo.settings.value.isApiServerEnabled)

        // 2. Failed service start reconciles truthfully
        // Occupy port specifically on 127.0.0.1 to force bind collision
        val blockerSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val busyPort = blockerSocket.localPort

        settingsRepo.updateSettings(settingsRepo.settings.value.copy(apiServerPort = busyPort, isApiServerEnabled = true))
        val serviceController = org.robolectric.Robolectric.buildService(OpenAiApiService::class.java)
        serviceController.create().startCommand(0, 0)

        val state = OpenAiApiService.serverState.value
        assertFalse("Server should not be running if bind failed", state.isRunning)
        assertNotNull("Error message should be set on failure", state.errorMessage)
        assertFalse("Persisted isApiServerEnabled must reconcile to false on start failure", settingsRepo.settings.value.isApiServerEnabled)

        blockerSocket.close()
        serviceController.destroy()
    }

    @Test
    fun `auth requirement blocks unauthorized requests when LAN mode is active`() {
        runBlocking { fakeEngine.loadModel(modelA) }
        val apiKey = "secret-token-12345"
        val port = startServer(requireAuth = true, apiKey = apiKey)

        // Missing Authorization header -> 401
        val url = URL("http://127.0.0.1:$port/v1/chat/completions")
        val conn1 = url.openConnection() as HttpURLConnection
        conn1.requestMethod = "POST"
        conn1.setRequestProperty("Content-Type", "application/json")
        conn1.doOutput = true
        conn1.outputStream.bufferedWriter().use { it.write("{\"messages\":[]}") }
        assertEquals(401, conn1.responseCode)

        // Wrong token -> 401
        val conn2 = url.openConnection() as HttpURLConnection
        conn2.requestMethod = "POST"
        conn2.setRequestProperty("Content-Type", "application/json")
        conn2.setRequestProperty("Authorization", "Bearer wrong-token")
        conn2.doOutput = true
        conn2.outputStream.bufferedWriter().use { it.write("{\"messages\":[]}") }
        assertEquals(401, conn2.responseCode)

        // Valid token -> 200
        val conn3 = url.openConnection() as HttpURLConnection
        conn3.requestMethod = "POST"
        conn3.setRequestProperty("Content-Type", "application/json")
        conn3.setRequestProperty("Authorization", "Bearer $apiKey")
        conn3.doOutput = true
        conn3.outputStream.bufferedWriter().use {
            it.write("{\"model\":\"qwen_0_6b\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
        }
        assertEquals(200, conn3.responseCode)
    }

    @Test
    fun `no model loaded returns 503`() {
        val port = startServer()
        val url = URL("http://127.0.0.1:$port/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.bufferedWriter().use { it.write("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}") }

        assertEquals(503, conn.responseCode)
        val body = conn.errorStream.bufferedReader().readText()
        val json = JSONObject(body)
        assertTrue(json.has("error"))
        assertEquals("no_model_loaded", json.getJSONObject("error").getString("code"))
    }

    @Test
    fun `busy returns 429`() {
        runBlocking { fakeEngine.loadModel(modelA) }
        fakeEngine.setRuntimeState(RuntimeState.GENERATION_ACTIVE)

        val port = startServer()
        val url = URL("http://127.0.0.1:$port/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.bufferedWriter().use { it.write("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}") }

        assertEquals(429, conn.responseCode)
        val body = conn.errorStream.bufferedReader().readText()
        val json = JSONObject(body)
        assertTrue(json.has("error"))
        assertEquals("rate_limit_exceeded", json.getJSONObject("error").getString("code"))
    }
}
