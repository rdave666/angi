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
        val effectiveSystem = buildEffectiveSystem(availableTools, systemInstruction)

        return when (model.family.lowercase().trim()) {
            "llama", "llama3", "llama-3", "llama-3.2", "meta-llama" -> {
                buildLlamaPrompt(messages, effectiveSystem)
            }
            "phi", "phi3", "phi-3", "phi-3.5" -> {
                buildPhiPrompt(messages, effectiveSystem)
            }
            "qwen", "qwen2", "qwen2.5" -> {
                buildQwenChatMlPrompt(messages, effectiveSystem)
            }
            else -> {
                // Default to standard ChatML format
                buildQwenChatMlPrompt(messages, effectiveSystem)
            }
        }
    }

    private fun buildEffectiveSystem(availableTools: List<ToolDefinition>, systemInstruction: String?): String {
        return buildString {
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
    }

    private fun buildQwenChatMlPrompt(messages: List<Message>, systemInstruction: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(systemInstruction).append("<|im_end|>\n")

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

    private fun buildLlamaPrompt(messages: List<Message>, systemInstruction: String): String {
        val sb = StringBuilder()
        sb.append("<|begin_of_text|>")
        sb.append("<|start_header_id|>system<|end_header_id|>\n\n").append(systemInstruction).append("<|eot_id|>")

        for (msg in messages) {
            when (msg) {
                is Message.User -> {
                    sb.append("<|start_header_id|>user<|end_header_id|>\n\n").append(msg.text).append("<|eot_id|>")
                }
                is Message.Assistant -> {
                    sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
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
                    sb.append("<|eot_id|>")
                }
                is Message.Tool -> {
                    sb.append("<|start_header_id|>ipython<|end_header_id|>\n\n")
                    sb.append("Tool result for ").append(msg.result.toolName).append(":\n")
                    sb.append(msg.result.output)
                    if (msg.result.error != null) {
                        sb.append("\nError: ").append(msg.result.error)
                    }
                    sb.append("<|eot_id|>")
                }
            }
        }
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }

    private fun buildPhiPrompt(messages: List<Message>, systemInstruction: String): String {
        val sb = StringBuilder()
        sb.append("<|system|>\n").append(systemInstruction).append("<|end|>\n")

        for (msg in messages) {
            when (msg) {
                is Message.User -> {
                    sb.append("<|user|>\n").append(msg.text).append("<|end|>\n")
                }
                is Message.Assistant -> {
                    sb.append("<|assistant|>\n")
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
                    sb.append("<|end|>\n")
                }
                is Message.Tool -> {
                    sb.append("<|user|>\n")
                    sb.append("[Tool Result: ").append(msg.result.toolName).append("]\n")
                    sb.append(msg.result.output)
                    if (msg.result.error != null) {
                        sb.append("\nError: ").append(msg.result.error)
                    }
                    sb.append("<|end|>\n")
                }
            }
        }
        sb.append("<|assistant|>\n")
        return sb.toString()
    }
}
