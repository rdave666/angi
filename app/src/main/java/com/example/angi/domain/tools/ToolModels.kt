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
    val error: String? = null,
    val requiresConfirmation: Boolean = false
)

data class ToolValidationResult(
    val isValid: Boolean,
    val error: String? = null,
    val sanitizedArguments: Map<String, Any?> = emptyMap()
)

fun validateToolArguments(definition: ToolDefinition, arguments: Map<String, Any?>): ToolValidationResult {
    // 1. Check required parameters
    for (required in definition.requiredParameters) {
        val value = arguments[required]
        if (value == null || (value is String && value.isBlank())) {
            return ToolValidationResult(
                isValid = false,
                error = "Missing required parameter: '$required'"
            )
        }
    }

    // 2. Check for unknown parameters
    for (key in arguments.keys) {
        if (!definition.parameters.containsKey(key)) {
            return ToolValidationResult(
                isValid = false,
                error = "Unknown parameter '$key' for tool '${definition.name}'"
            )
        }
    }

    // 3. Validate parameter types and limits
    val sanitized = mutableMapOf<String, Any?>()
    for ((key, value) in arguments) {
        val paramDef = definition.parameters[key] ?: continue
        if (value == null) {
            sanitized[key] = null
            continue
        }

        when (paramDef.type.lowercase()) {
            "string" -> {
                val str = value.toString()
                if (str.length > 500_000) {
                    return ToolValidationResult(
                        isValid = false,
                        error = "Parameter '$key' exceeds maximum character limit (500,000)"
                    )
                }
                if (key.contains("url", ignoreCase = true)) {
                    if (!str.startsWith("http://", ignoreCase = true) && !str.startsWith("https://", ignoreCase = true)) {
                        return ToolValidationResult(
                            isValid = false,
                            error = "Parameter '$key' must be a valid HTTP or HTTPS URL"
                        )
                    }
                }
                sanitized[key] = str
            }
            "number", "integer", "int", "float", "double" -> {
                if (value is Number) {
                    sanitized[key] = value
                } else {
                    val num = value.toString().toDoubleOrNull()
                    if (num == null) {
                        return ToolValidationResult(
                            isValid = false,
                            error = "Parameter '$key' must be a valid number, got '$value'"
                        )
                    }
                    sanitized[key] = num
                }
            }
            "boolean", "bool" -> {
                if (value is Boolean) {
                    sanitized[key] = value
                } else {
                    val str = value.toString().trim().lowercase()
                    if (str == "true" || str == "false") {
                        sanitized[key] = str.toBoolean()
                    } else {
                        return ToolValidationResult(
                            isValid = false,
                            error = "Parameter '$key' must be a boolean, got '$value'"
                        )
                    }
                }
            }
            else -> {
                sanitized[key] = value
            }
        }
    }

    return ToolValidationResult(isValid = true, sanitizedArguments = sanitized)
}

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
    suspend fun execute(
        toolName: String,
        arguments: Map<String, Any?>,
        confirmed: Boolean = false
    ): ToolResult {
        // Step 1: Does tool exist?
        val tool = registry.getTool(toolName) ?: return ToolResult(
            callId = toolName,
            toolName = toolName,
            isSuccess = false,
            output = "Tool '$toolName' not registered in ANGI.",
            error = "TOOL_NOT_FOUND"
        )

        // Step 2: Is tool enabled by policy?
        if (!policy.isToolEnabled(toolName)) {
            return ToolResult(
                callId = toolName,
                toolName = toolName,
                isSuccess = false,
                output = "Tool '$toolName' is disabled by ANGI capability policy.",
                error = "TOOL_DISABLED"
            )
        }

        // Step 3: Validate arguments
        val validation = validateToolArguments(tool.definition, arguments)
        if (!validation.isValid) {
            return ToolResult(
                callId = toolName,
                toolName = toolName,
                isSuccess = false,
                output = "Argument validation failed for '$toolName': ${validation.error}",
                error = "INVALID_ARGUMENTS"
            )
        }

        // Step 4: Is confirmation required?
        val confirmationRequired = tool.definition.requiresUserConfirmation || !policy.canExecuteWithoutPrompt(toolName)
        if (confirmationRequired && !confirmed) {
            return ToolResult(
                callId = toolName,
                toolName = toolName,
                isSuccess = false,
                output = "Tool '$toolName' requires user confirmation before execution.",
                error = "CONFIRMATION_REQUIRED",
                requiresConfirmation = true
            )
        }

        // Step 5: Execute tool
        return runCatching {
            tool.execute(validation.sanitizedArguments)
        }.getOrElse { e ->
            ToolResult(
                callId = toolName,
                toolName = toolName,
                isSuccess = false,
                output = "Error executing tool '$toolName': ${e.localizedMessage}",
                error = e.message
            )
        }
    }
}
