package com.example.angi.api

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class OpenAiApiServerTest {

    private lateinit var fakeEngine: FakeInferenceEngine
    private lateinit var modelA: ModelDescriptor
    private lateinit var modelB: ModelDescriptor
    private var server: OpenAiHttpServer? = null

    @Before
    fun setup() {
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
        server?.stop()
        server = null
    }

    private fun startServer(
        requireAuth: Boolean = false,
        apiKey: String = "test-key"
    ): Int {
        val s = OpenAiHttpServer(
            host = "127.0.0.1",
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

        // Now load model and check health again
        runBlocking { fakeEngine.loadModel(modelA) }
        val conn2 = url.openConnection() as HttpURLConnection
        assertEquals(200, conn2.responseCode)
        val json2 = JSONObject(conn2.inputStream.bufferedReader().readText())
        assertTrue(json2.getBoolean("model_loaded"))
        assertEquals("qwen_0_6b", json2.getString("model"))
    }

    @Test
    fun `v1 models endpoint with loaded model returns model details`() {
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
        assertEquals(1, data.length())
        val item = data.getJSONObject(0)
        assertEquals("qwen_0_6b", item.getString("id"))
        assertEquals("model", item.getString("object"))
    }

    @Test
    fun `v1 models endpoint with no model returns empty model list`() {
        // No model loaded in fakeEngine
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

        // 1. Initial query: Model A loaded
        val url = URL("http://127.0.0.1:$port/v1/models")
        val conn1 = url.openConnection() as HttpURLConnection
        assertEquals(200, conn1.responseCode)
        val json1 = JSONObject(conn1.inputStream.bufferedReader().readText())
        assertEquals("qwen_0_6b", json1.getJSONArray("data").getJSONObject(0).getString("id"))

        // 2. Unload Model A (no server restart)
        runBlocking { fakeEngine.unloadModel() }
        val conn2 = url.openConnection() as HttpURLConnection
        assertEquals(200, conn2.responseCode)
        val json2 = JSONObject(conn2.inputStream.bufferedReader().readText())
        assertEquals(0, json2.getJSONArray("data").length())

        // 3. Load Model B (no server restart)
        runBlocking { fakeEngine.loadModel(modelB) }
        val conn3 = url.openConnection() as HttpURLConnection
        assertEquals(200, conn3.responseCode)
        val json3 = JSONObject(conn3.inputStream.bufferedReader().readText())
        assertEquals(1, json3.getJSONArray("data").length())
        assertEquals("llama_3_2_1b", json3.getJSONArray("data").getJSONObject(0).getString("id"))
    }

    @Test
    fun `chat completion endpoint non-streaming returns valid OpenAI response`() {
        runBlocking { fakeEngine.loadModel(modelA) }
        val port = startServer()
        val url = URL("http://127.0.0.1:$port/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val requestJson = JSONObject().apply {
            put("model", "qwen_0_6b")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Hello ANGI")
                })
            })
            put("stream", false)
        }

        conn.outputStream.bufferedWriter().use { it.write(requestJson.toString()) }

        assertEquals(200, conn.responseCode)
        val body = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(body)

        assertEquals("chat.completion", json.getString("object"))
        val choices = json.getJSONArray("choices")
        assertEquals(1, choices.length())
        val choice = choices.getJSONObject(0)
        val msg = choice.getJSONObject("message")
        assertEquals("assistant", msg.getString("role"))
        assertTrue("Content must contain generated tokens", msg.getString("content").contains("Hello world from ANGI"))
        assertEquals("stop", choice.getString("finish_reason"))
    }

    @Test
    fun `streaming SSE chat completion returns data chunks and ends with DONE`() {
        runBlocking { fakeEngine.loadModel(modelA) }
        val port = startServer()
        val url = URL("http://127.0.0.1:$port/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val requestJson = JSONObject().apply {
            put("model", "qwen_0_6b")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Stream test")
                })
            })
            put("stream", true)
        }

        conn.outputStream.bufferedWriter().use { it.write(requestJson.toString()) }

        assertEquals(200, conn.responseCode)
        assertEquals("text/event-stream; charset=utf-8", conn.getHeaderField("Content-Type"))

        val lines = conn.inputStream.bufferedReader().readLines()
        val dataLines = lines.filter { it.startsWith("data: ") }

        assertTrue("Must receive multiple SSE data events", dataLines.size >= 2)
        assertTrue("Final event must be [DONE]", dataLines.last() == "data: [DONE]")

        // Validate chunk structure
        val firstChunk = JSONObject(dataLines.first().removePrefix("data: "))
        assertEquals("chat.completion.chunk", firstChunk.getString("object"))
    }

    @Test
    fun `auth requirement blocks unauthorized requests when LAN mode is active`() {
        runBlocking { fakeEngine.loadModel(modelA) }
        val apiKey = "secret-token-12345"
        val port = startServer(requireAuth = true, apiKey = apiKey)

        // 1. Missing Authorization header -> 401
        val url = URL("http://127.0.0.1:$port/v1/chat/completions")
        val conn1 = url.openConnection() as HttpURLConnection
        conn1.requestMethod = "POST"
        conn1.setRequestProperty("Content-Type", "application/json")
        conn1.doOutput = true
        conn1.outputStream.bufferedWriter().use { it.write("{\"messages\":[]}") }
        assertEquals(401, conn1.responseCode)

        // 2. Wrong token -> 401
        val conn2 = url.openConnection() as HttpURLConnection
        conn2.requestMethod = "POST"
        conn2.setRequestProperty("Content-Type", "application/json")
        conn2.setRequestProperty("Authorization", "Bearer wrong-token")
        conn2.doOutput = true
        conn2.outputStream.bufferedWriter().use { it.write("{\"messages\":[]}") }
        assertEquals(401, conn2.responseCode)

        // 3. Valid token -> 200
        val conn3 = url.openConnection() as HttpURLConnection
        conn3.requestMethod = "POST"
        conn3.setRequestProperty("Content-Type", "application/json")
        conn3.setRequestProperty("Authorization", "Bearer $apiKey")
        conn3.doOutput = true
        conn3.outputStream.bufferedWriter().use { it.write("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}") }
        assertEquals(200, conn3.responseCode)
    }

    @Test
    fun `no model loaded returns 503`() {
        // No model loaded in fakeEngine
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

    @Test
    fun `disconnect cancellation triggers cancel on inference engine`() = runBlocking {
        runBlocking { fakeEngine.loadModel(modelA) }
        val port = startServer()

        val socket = Socket("127.0.0.1", port)
        val body = "{\"model\":\"qwen\",\"messages\":[{\"role\":\"user\",\"content\":\"test\"}],\"stream\":true}"
        val req = "POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n\r\n" +
                body

        val out = socket.getOutputStream()
        out.write(req.toByteArray())
        out.flush()

        // Read first few bytes of response header, then immediately abruptly close the socket
        val input = socket.getInputStream()
        val buf = ByteArray(64)
        val read = input.read(buf)
        assertTrue(read > 0)

        // Close socket mid-stream to simulate client disconnect
        socket.close()

        // Give server coroutine a brief moment to detect broken socket
        delay(200)

        assertTrue(
            "fakeEngine.cancel must be called when client abruptly disconnects",
            fakeEngine.cancelCalledCount > 0
        )
    }
}
