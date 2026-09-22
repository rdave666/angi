package com.example.angi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.angi.domain.conversation.Message
import com.example.angi.domain.conversation.StandardPromptBuilder
import com.example.angi.domain.conversation.ConversationService
import com.example.angi.domain.inference.GenerationEvent
import com.example.angi.domain.inference.GenerationRequest
import com.example.angi.domain.inference.RuntimeState
import com.example.angi.domain.models.ComputeUnit
import com.example.angi.domain.models.ModelDescriptor
import com.example.angi.domain.models.ModelFormat
import com.example.angi.domain.models.RuntimeType
import com.example.angi.domain.tools.AngiTool
import com.example.angi.domain.tools.CapabilityPolicy
import com.example.angi.domain.tools.ToolCategory
import com.example.angi.domain.tools.ToolDefinition
import com.example.angi.domain.tools.ToolExecutor
import com.example.angi.domain.tools.ToolParameter
import com.example.angi.domain.tools.ToolRegistry
import com.example.angi.domain.tools.ToolResult
import com.example.angi.domain.tools.validateToolArguments
import com.example.angi.runtime.geniex.GenieXInferenceEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AngiRegressionTest {

    // A9: Tool policy enforcement
    @Test
    fun `tool policy enforcement blocks disabled tools`() = runBlocking {
        val registry = ToolRegistry()
        val dummyTool = object : AngiTool {
            override val definition = ToolDefinition(
                name = "test_tool",
                description = "A test tool",
                parameters = emptyMap(),
                category = ToolCategory.SYSTEM
            )
            override suspend fun execute(arguments: Map<String, Any?>) = ToolResult(
                callId = "call_test",
                toolName = "test_tool",
                isSuccess = true,
                output = "Success"
            )
        }
        registry.register(dummyTool)

        val policy = object : CapabilityPolicy {
            override fun isToolEnabled(toolName: String) = false
            override fun canExecuteWithoutPrompt(toolName: String) = true
            override fun setToolEnabled(toolName: String, enabled: Boolean) {}
        }

        val executor = ToolExecutor(registry, policy)
        val result = executor.execute("test_tool", emptyMap())

        assertFalse("Disabled tool must not succeed", result.isSuccess)
        assertEquals("TOOL_DISABLED", result.error)
        assertTrue("Output must mention disabled by policy", result.output.contains("disabled by ANGI capability policy"))
    }

    @Test
    fun `tool policy requires confirmation when tool is sensitive`() = runBlocking {
        val registry = ToolRegistry()
        val dummyTool = object : AngiTool {
            override val definition = ToolDefinition(
                name = "dangerous_action",
                description = "A sensitive action",
                parameters = emptyMap(),
                category = ToolCategory.FILESYSTEM
            )
            override suspend fun execute(arguments: Map<String, Any?>) = ToolResult(
                callId = "call_danger",
                toolName = "dangerous_action",
                isSuccess = true,
                output = "Done"
            )
        }
        registry.register(dummyTool)

        val policy = object : CapabilityPolicy {
            override fun isToolEnabled(toolName: String) = true
            override fun canExecuteWithoutPrompt(toolName: String) = false // Requires prompt
            override fun setToolEnabled(toolName: String, enabled: Boolean) {}
        }

        val executor = ToolExecutor(registry, policy)
        // Execute without user confirmation
        val unconfirmedResult = executor.execute("dangerous_action", emptyMap(), confirmed = false)
        assertTrue("Must flag requiresConfirmation", unconfirmedResult.requiresConfirmation)
        assertFalse("Must not succeed without confirmation", unconfirmedResult.isSuccess)

        // Execute with user confirmation
        val confirmedResult = executor.execute("dangerous_action", emptyMap(), confirmed = true)
        assertFalse("Must not flag requiresConfirmation when confirmed", confirmedResult.requiresConfirmation)
        assertTrue("Must succeed when confirmed", confirmedResult.isSuccess)
    }

    @Test
    fun `settings repository enables linux exec tools and permits zero prompt execution`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepo = com.example.angi.data.settings.SettingsRepository(context)

        // Linux execution tools must be enabled by default
        assertTrue(settingsRepo.isToolEnabled("linux_exec"))
        assertTrue(settingsRepo.isToolEnabled("linux_process_status"))
        assertTrue(settingsRepo.isToolEnabled("linux_process_output"))
        assertTrue(settingsRepo.isToolEnabled("linux_process_kill"))

        // Linux execution tools run without prompt friction in sandbox
        assertTrue(settingsRepo.canExecuteWithoutPrompt("linux_exec"))
        assertTrue(settingsRepo.canExecuteWithoutPrompt("linux_process_status"))
        assertTrue(settingsRepo.canExecuteWithoutPrompt("linux_process_output"))
        assertTrue(settingsRepo.canExecuteWithoutPrompt("linux_process_kill"))

        // Disabling linux_exec takes immediate effect
        settingsRepo.setToolEnabled("linux_exec", false)
        assertFalse(settingsRepo.isToolEnabled("linux_exec"))

        // Restore for test cleanliness
        settingsRepo.setToolEnabled("linux_exec", true)
        assertTrue(settingsRepo.isToolEnabled("linux_exec"))
    }

    // A10: Tool argument validation
    @Test
    fun `tool argument validation enforces types and required fields`() {
        val definition = ToolDefinition(
            name = "create_note",
            description = "Creates a note",
            parameters = mapOf(
                "title" to ToolParameter(type = "string", description = "Title"),
                "count" to ToolParameter(type = "integer", description = "Count")
            ),
            requiredParameters = listOf("title"),
            category = ToolCategory.FILESYSTEM
        )

        // Missing required field
        val missingResult = validateToolArguments(definition, emptyMap())
        assertFalse(missingResult.isValid)
        assertTrue(missingResult.error?.contains("Missing required parameter: 'title'") == true)

        // Wrong type for count
        val wrongTypeResult = validateToolArguments(
            definition,
            mapOf("title" to "My Note", "count" to "not_an_int")
        )
        assertFalse(wrongTypeResult.isValid)
        assertTrue(wrongTypeResult.error?.contains("must be a valid number") == true)

        // Valid arguments
        val validResult = validateToolArguments(
            definition,
            mapOf("title" to "My Note", "count" to 5)
        )
        assertTrue(validResult.isValid)

        // Exceeding maximum argument length
        val oversizedArgs = mapOf("title" to "a".repeat(500_001))
        val oversizedResult = validateToolArguments(definition, oversizedArgs)
        assertFalse(oversizedResult.isValid)
        assertTrue(oversizedResult.error?.contains("exceeds maximum character limit") == true)
    }

    // A5: Model-specific chat templates
    @Test
    fun `prompt builder uses model specific templates for Qwen, Llama, and Phi`() {
        val builder = StandardPromptBuilder()
        val messages = listOf(
            Message.User(id = "1", conversationId = "c1", text = "Hello AI")
        )

        // Qwen template check (ChatML)
        val qwenModel = ModelDescriptor(
            id = "qwen_test",
            name = "Qwen 2.5",
            family = "Qwen",
            format = ModelFormat.GGUF,
            runtime = RuntimeType.LLAMA_CPP,
            preferredCompute = ComputeUnit.GPU,
            modelPath = "/dummy"
        )
        val qwenPrompt = builder.build(messages, emptyList(), qwenModel)
        assertTrue(qwenPrompt.contains("<|im_start|>system"))
        assertTrue(qwenPrompt.contains("<|im_start|>user\nHello AI<|im_end|>"))
        assertTrue(qwenPrompt.endsWith("<|im_start|>assistant\n"))

        // Llama template check
        val llamaModel = ModelDescriptor(
            id = "llama_test",
            name = "Llama 3.2",
            family = "Llama",
            format = ModelFormat.GGUF,
            runtime = RuntimeType.LLAMA_CPP,
            preferredCompute = ComputeUnit.GPU,
            modelPath = "/dummy"
        )
        val llamaPrompt = builder.build(messages, emptyList(), llamaModel)
        assertTrue(llamaPrompt.startsWith("<|begin_of_text|>"))
        assertTrue(llamaPrompt.contains("<|start_header_id|>system<|end_header_id|>"))
        assertTrue(llamaPrompt.contains("<|start_header_id|>user<|end_header_id|>\n\nHello AI<|eot_id|>"))
        assertTrue(llamaPrompt.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))

        // Phi template check
        val phiModel = ModelDescriptor(
            id = "phi_test",
            name = "Phi-3.5",
            family = "Phi",
            format = ModelFormat.GGUF,
            runtime = RuntimeType.LLAMA_CPP,
            preferredCompute = ComputeUnit.CPU,
            modelPath = "/dummy"
        )
        val phiPrompt = builder.build(messages, emptyList(), phiModel)
        assertTrue(phiPrompt.contains("<|system|>"))
        assertTrue(phiPrompt.contains("<|user|>\nHello AI<|end|>"))
        assertTrue(phiPrompt.endsWith("<|assistant|>\n"))
    }

    // A1 & A2: Truthful runtime state & error propagation without fake inference
    @Test
    fun `geniex inference engine fails truthfully without fake fallback when model not loaded`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = GenieXInferenceEngine(context)

        // Engine starts with no model loaded
        assertFalse("Model must not be loaded initially", engine.runtimeInfo().isModelLoaded)

        // Attempt generation without loading a model
        val request = GenerationRequest(
            prompt = "Test prompt",
            maxTokens = 64
        )
        val events = engine.generate(request).toList()

        // Must NOT emit tokens; must emit Error event
        val hasTokens = events.any { it is GenerationEvent.Token }
        val errorEvent = events.filterIsInstance<GenerationEvent.Error>().firstOrNull()

        assertFalse("Must never fabricate tokens without loaded model", hasTokens)
        assertTrue("Must emit Error event when generating without loaded model", errorEvent != null)
        assertTrue("Must state model is not loaded", errorEvent?.userMessage?.contains("Model is not loaded") == true)
    }

    // A11: Bounded multi-tool loop
    @Test
    fun `conversation service enforces maximum 8 tool steps limit`() {
        assertEquals("Maximum tool steps must be bounded at 8", 8, ConversationService.MAX_TOOL_STEPS)
    }

    // Unified Linux filesystem ownership: guest /workspace and host tools address the same file
    @Test
    fun `guest workspace and linux filesystem tools address the identical physical file in linux sandbox`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sandboxManager = com.example.angi.runtime.proot.LinuxSandboxManager(context)

        // Write to guest workspace path
        val workspaceFile = java.io.File(sandboxManager.paths.workspaceDir, "unified_test.txt")
        workspaceFile.parentFile?.mkdirs()
        workspaceFile.writeText("unified-test-content", Charsets.UTF_8)

        // Read through LinuxReadFileTool with unified resolver
        val readTool = com.example.angi.tools.linux.LinuxReadFileTool { sandboxManager.getPathResolver() }
        val readResult = readTool.execute(mapOf("path" to "/workspace/unified_test.txt"))

        assertTrue("Read tool must succeed", readResult.isSuccess)
        assertEquals("unified-test-content", readResult.output)

        // Write through LinuxWriteFileTool
        val writeTool = com.example.angi.tools.linux.LinuxWriteFileTool { sandboxManager.getPathResolver() }
        val writeResult = writeTool.execute(mapOf("path" to "/workspace/host_written.txt", "content" to "host-content"))
        assertTrue("Write tool must succeed", writeResult.isSuccess)

        val guestHostWritten = java.io.File(sandboxManager.paths.workspaceDir, "host_written.txt")
        assertTrue("Physical file must exist at workspace mount point", guestHostWritten.exists())
        assertEquals("host-content", guestHostWritten.readText())
    }

    // ToolExecutionContext propagation without exposing to model input
    @Test
    fun `tool executor passes conversationId execution context to tools`() = runBlocking {
        var capturedContext: com.example.angi.domain.tools.ToolExecutionContext? = null
        val testTool = object : AngiTool {
            override val definition = ToolDefinition("context_spy", "desc", emptyMap())
            override suspend fun execute(arguments: Map<String, Any?>) = ToolResult("id", "context_spy", true, "ok")
            override suspend fun execute(
                arguments: Map<String, Any?>,
                context: com.example.angi.domain.tools.ToolExecutionContext
            ): ToolResult {
                capturedContext = context
                return ToolResult("id", "context_spy", true, "ok")
            }
        }
        val registry = ToolRegistry().apply { register(testTool) }
        val policy = object : CapabilityPolicy {
            override fun isToolEnabled(toolName: String) = true
            override fun canExecuteWithoutPrompt(toolName: String) = true
            override fun setToolEnabled(toolName: String, enabled: Boolean) {}
        }
        val executor = ToolExecutor(registry, policy)
        executor.execute(
            toolName = "context_spy",
            arguments = emptyMap(),
            context = com.example.angi.domain.tools.ToolExecutionContext("conv_session_999")
        )
        assertEquals("conv_session_999", capturedContext?.conversationId)
    }
}
