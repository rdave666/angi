package com.example.angi.domain.tools

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParameter>,
    val requiredParameters: List<String> = emptyList(),
    val requiresUserConfirmation: Boolean = false,
    val category: ToolCategory = ToolCategory.DEVICE
)

enum class ToolCategory {
    DEVICE,
    SYSTEM,
    NETWORK,
    FILESYSTEM
}

data class ToolParameter(
    val type: String,
    val description: String
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>
)

data class ToolResult(
    val callId: String,
    val toolName: String,
    val isSuccess: Boolean,
    val output: String,
    val error: String? = null
)

interface AngiTool {
    val definition: ToolDefinition
    suspend fun execute(arguments: Map<String, Any?>): ToolResult
}

interface CapabilityPolicy {
    fun isToolEnabled(toolName: String): Boolean
    fun canExecuteWithoutPrompt(toolName: String): Boolean
    fun setToolEnabled(toolName: String, enabled: Boolean)
}

class ToolRegistry {
    private val tools = mutableMapOf<String, AngiTool>()

    fun register(tool: AngiTool) {
        tools[tool.definition.name] = tool
    }

    fun getTool(name: String): AngiTool? = tools[name]

    fun getAllDefinitions(): List<ToolDefinition> = tools.values.map { it.definition }

    fun getAllTools(): List<AngiTool> = tools.values.toList()
}

class ToolExecutor(
    private val registry: ToolRegistry,
    private val policy: CapabilityPolicy
) {
    suspend fun execute(toolName: String, arguments: Map<String, Any?>): ToolResult {
        if (!policy.isToolEnabled(toolName)) {
            return ToolResult(
                callId = toolName,
                toolName = toolName,
                isSuccess = false,
                output = "Tool '$toolName' is disabled by ANGI capability policy.",
                error = "Policy violation"
            )
        }

        val tool = registry.getTool(toolName) ?: return ToolResult(
            callId = toolName,
            toolName = toolName,
            isSuccess = false,
            output = "Tool '$toolName' not registered in ANGI.",
            error = "Tool not found"
        )

        return runCatching {
            tool.execute(arguments)
        }.getOrElse { e ->
            ToolResult(
                callId = toolName,
                toolName = toolName,
                isSuccess = false,
                output = "Error executing tool: ${e.localizedMessage}",
                error = e.message
            )
        }
    }
}
