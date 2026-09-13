package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.angi.domain.conversation.StandardPromptBuilder
import com.example.angi.domain.conversation.Message
import com.example.angi.domain.models.ComputeUnit
import com.example.angi.domain.models.Modality
import com.example.angi.domain.models.ModelDescriptor
import com.example.angi.domain.models.ModelFormat
import com.example.angi.domain.models.RuntimeType
import com.example.angi.domain.tools.CapabilityPolicy
import com.example.angi.domain.tools.ToolCategory
import com.example.angi.domain.tools.ToolDefinition
import com.example.angi.domain.tools.ToolExecutor
import com.example.angi.domain.tools.ToolRegistry
import com.example.angi.tools.impl.DeviceInfoTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context matches ANGI`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("ANGI", appName)
    }

    @Test
    fun `prompt builder correctly formats chatml with tools`() {
        val builder = StandardPromptBuilder()
        val dummyModel = ModelDescriptor(
            id = "test_model",
            name = "Test Model",
            family = "Qwen",
            format = ModelFormat.QAIRT_BUNDLE,
            runtime = RuntimeType.QAIRT,
            preferredCompute = ComputeUnit.NPU,
            modelPath = "/dummy"
        )
        val toolDef = ToolDefinition(
            name = "device_info",
            description = "Get device info",
            parameters = emptyMap(),
            category = ToolCategory.DEVICE
        )
        val messages = listOf(
            Message.User(id = "1", conversationId = "c1", text = "Hello ANGI")
        )

        val prompt = builder.build(
            messages = messages,
            availableTools = listOf(toolDef),
            model = dummyModel,
            systemInstruction = "Custom instruction"
        )

        assertTrue(prompt.contains("<|im_start|>system"))
        assertTrue(prompt.contains("device_info: Get device info"))
        assertTrue(prompt.contains("<|im_start|>user\nHello ANGI<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `tool executor runs device info tool successfully`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = ToolRegistry()
        val deviceInfoTool = DeviceInfoTool(context)
        registry.register(deviceInfoTool)

        val policy = object : CapabilityPolicy {
            override fun isToolEnabled(toolName: String): Boolean = true
            override fun canExecuteWithoutPrompt(toolName: String): Boolean = true
            override fun setToolEnabled(toolName: String, enabled: Boolean) {}
        }

        val executor = ToolExecutor(registry, policy)
        val result = executor.execute("device_info", emptyMap())

        assertTrue(result.isSuccess)
        assertNotNull(result.output)
        assertTrue(result.output.contains("socModel") || result.output.contains("manufacturer"))
    }
}
