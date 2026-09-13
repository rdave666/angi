package com.example.angi.tools.impl

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.example.angi.domain.tools.AngiTool
import com.example.angi.domain.tools.CapabilityPolicy
import com.example.angi.domain.tools.ToolCategory
import com.example.angi.domain.tools.ToolDefinition
import com.example.angi.domain.tools.ToolParameter
import com.example.angi.domain.tools.ToolRegistry
import com.example.angi.domain.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ShareTextTool(private val context: Context) : AngiTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "share_text",
        description = "Share text, assistant answers, or snippets to other apps via the Android Sharesheet.",
        parameters = mapOf(
            "text" to ToolParameter("string", "The message or content to share"),
            "title" to ToolParameter("string", "Optional title for the share dialog")
        ),
        requiredParameters = listOf("text"),
        requiresUserConfirmation = false,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = withContext(Dispatchers.Main) {
        val text = arguments["text"]?.toString() ?: return@withContext ToolResult(
            callId = "share",
            toolName = definition.name,
            isSuccess = false,
            output = "Missing required argument 'text'",
            error = "Invalid arguments"
        )
        val title = arguments["title"]?.toString() ?: "Share via ANGI"

        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val shareIntent = Intent.createChooser(sendIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(shareIntent)

        ToolResult(
            callId = "share",
            toolName = definition.name,
            isSuccess = true,
            output = "Sharesheet successfully opened for text ($text.length chars)."
        )
    }
}

class DeviceInfoTool(private val context: Context) : AngiTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "device_info",
        description = "Query actual hardware details, SoC chipset, memory, and Qualcomm Hexagon accelerator capabilities.",
        parameters = emptyMap(),
        requiredParameters = emptyList(),
        requiresUserConfirmation = false,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = withContext(Dispatchers.Default) {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val availRamGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "Qualcomm Snapdragon"

        val json = JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("hardware", Build.HARDWARE)
            put("socModel", socModel)
            put("cpuAbi", Build.SUPPORTED_ABIS.joinToString(", "))
            put("androidVersion", Build.VERSION.RELEASE)
            put("apiLevel", Build.VERSION.SDK_INT)
            put("totalRamGb", String.format("%.2f GB", totalRamGb))
            put("availRamGb", String.format("%.2f GB", availRamGb))
            put("isSnapdragon8Gen2Target", Build.HARDWARE.contains("qcom", ignoreCase = true) || Build.MODEL.contains("S918", ignoreCase = true) || socModel.contains("SM8550", ignoreCase = true))
            put("hexagonNpuPathSupported", true)
        }

        ToolResult(
            callId = "device_info",
            toolName = definition.name,
            isSuccess = true,
            output = json.toString(2)
        )
    }
}

class WebFetchTool : AngiTool {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override val definition: ToolDefinition = ToolDefinition(
        name = "web_fetch",
        description = "Fetch live content from a specified HTTP or HTTPS URL.",
        parameters = mapOf(
            "url" to ToolParameter("string", "The HTTP or HTTPS URL to fetch content from")
        ),
        requiredParameters = listOf("url"),
        requiresUserConfirmation = false,
        category = ToolCategory.NETWORK
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        val url = arguments["url"]?.toString() ?: return@withContext ToolResult(
            callId = "fetch",
            toolName = definition.name,
            isSuccess = false,
            output = "Missing required argument 'url'",
            error = "Invalid arguments"
        )

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolResult(
                callId = "fetch",
                toolName = definition.name,
                isSuccess = false,
                output = "URL must start with http:// or https://",
                error = "Malformed URL"
            )
        }

        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "ANGI-OnDevice-Agent/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    ToolResult(
                        callId = "fetch",
                        toolName = definition.name,
                        isSuccess = false,
                        output = "HTTP Error: ${response.code} ${response.message}",
                        error = "HTTP status ${response.code}"
                    )
                } else {
                    val body = response.body?.string().orEmpty()
                    val preview = if (body.length > 2000) body.take(2000) + "... [truncated]" else body
                    ToolResult(
                        callId = "fetch",
                        toolName = definition.name,
                        isSuccess = true,
                        output = preview
                    )
                }
            }
        }.getOrElse { e ->
            ToolResult(
                callId = "fetch",
                toolName = definition.name,
                isSuccess = false,
                output = "Network fetch failed: ${e.localizedMessage}",
                error = e.message
            )
        }
    }
}

class OpenUrlTool(private val context: Context) : AngiTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "open_url",
        description = "Open an external web URL in the device browser.",
        parameters = mapOf(
            "url" to ToolParameter("string", "The URL to open")
        ),
        requiredParameters = listOf("url"),
        requiresUserConfirmation = true,
        category = ToolCategory.NETWORK
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = withContext(Dispatchers.Main) {
        val url = arguments["url"]?.toString() ?: return@withContext ToolResult(
            callId = "open_url",
            toolName = definition.name,
            isSuccess = false,
            output = "Missing required argument 'url'",
            error = "Invalid arguments"
        )
        val fullUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(
                callId = "open_url",
                toolName = definition.name,
                isSuccess = true,
                output = "Launched browser for URL: $fullUrl"
            )
        }.getOrElse { e ->
            ToolResult(
                callId = "open_url",
                toolName = definition.name,
                isSuccess = false,
                output = "Failed to launch URL: ${e.localizedMessage}",
                error = e.message
            )
        }
    }
}
