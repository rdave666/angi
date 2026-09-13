package com.example.angi.domain.conversation

import com.example.angi.domain.models.ModelDescriptor
import com.example.angi.domain.tools.ToolDefinition

interface PromptBuilder {
    fun build(
        messages: List<Message>,
        availableTools: List<ToolDefinition>,
        model: ModelDescriptor,
        systemInstruction: String? = null
    ): String
}

class StandardPromptBuilder : PromptBuilder {

    override fun build(
        messages: List<Message>,
        availableTools: List<ToolDefinition>,
        model: ModelDescriptor,
        systemInstruction: String?
    ): String {
        val sb = StringBuilder()

        // Construct system block
        val effectiveSystem = buildString {
            append("You are ANGI, an advanced AI running on-device on Qualcomm Snapdragon hardware.")
            if (!systemInstruction.isNullOrBlank()) {
                append("\n").append(systemInstruction)
            }
            if (availableTools.isNotEmpty()) {
                append("\n\nYou have access to the following tools:\n")
                availableTools.forEach { tool ->
                    append("- ").append(tool.name).append(": ").append(tool.description).append("\n")
                    if (tool.parameters.isNotEmpty()) {
                        append("  Arguments: ")
                        val paramList = tool.parameters.map { (key, param) ->
                            "$key (${param.type}): ${param.description}"
                        }.joinToString(", ")
                        append(paramList).append("\n")
                    }
                }
                append("\nTo use a tool, respond with a JSON block in this exact format:\n")
                append("```tool_code\n{\"tool\": \"tool_name\", \"arguments\": {\"param\": \"value\"}}\n```\n")
            }
        }

        // Apply ChatML format which is widely standard for modern on-device models (Qwen, Phi, Mistral, Llama GGUFs)
        sb.append("<|im_start|>system\n").append(effectiveSystem).append("<|im_end|>\n")

        for (msg in messages) {
            when (msg) {
                is Message.User -> {
                    sb.append("<|im_start|>user\n").append(msg.text).append("<|im_end|>\n")
                }
                is Message.Assistant -> {
                    sb.append("<|im_start|>assistant\n")
                    if (msg.toolCall != null) {
                        sb.append("```tool_code\n")
                        sb.append("{\"tool\": \"").append(msg.toolCall.name).append("\", \"arguments\": ")
                        val argsJson = msg.toolCall.arguments.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                            "\"$k\": \"$v\""
                        }
                        sb.append(argsJson).append("}\n```\n")
                    }
                    if (msg.text.isNotEmpty()) {
                        sb.append(msg.text)
                    }
                    sb.append("<|im_end|>\n")
                }
                is Message.Tool -> {
                    sb.append("<|im_start|>tool\n")
                    sb.append("Tool result for ").append(msg.result.toolName).append(":\n")
                    sb.append(msg.result.output)
                    if (msg.result.error != null) {
                        sb.append("\nError: ").append(msg.result.error)
                    }
                    sb.append("<|im_end|>\n")
                }
            }
        }

        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}
