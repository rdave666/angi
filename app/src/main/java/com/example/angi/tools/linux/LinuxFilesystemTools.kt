package com.example.angi.tools.linux

import com.example.angi.domain.tools.AngiTool
import com.example.angi.domain.tools.ToolCategory
import com.example.angi.domain.tools.ToolDefinition
import com.example.angi.domain.tools.ToolParameter
import com.example.angi.domain.tools.ToolResult
import com.example.angi.runtime.linux.LinuxPathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LinuxReadFileTool(
    private val resolverProvider: () -> LinuxPathResolver
) : AngiTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "linux_read_file",
        description = "Read file contents from the isolated Linux environment filesystem. The path must be a virtual Linux path (e.g. '/workspace/notes.txt' or '/etc/os-release'). Physical Android paths are forbidden.",
        parameters = mapOf(
            "path" to ToolParameter("string", "Virtual Linux path to read (e.g. '/workspace/file.txt')")
        ),
        requiredParameters = listOf("path"),
        requiresUserConfirmation = false,
        category = ToolCategory.FILESYSTEM
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        val path = arguments["path"]?.toString() ?: return@withContext ToolResult(
            callId = "linux_read",
            toolName = definition.name,
            isSuccess = false,
            output = "Missing required argument 'path'",
            error = "INVALID_ARGUMENTS"
        )

        runCatching {
            val resolver = resolverProvider()
            val resolved = resolver.resolve(path, requireWritable = false)
            val file = when (resolved) {
                is LinuxPathResolver.ResolvedTarget.Workspace -> resolved.file
                is LinuxPathResolver.ResolvedTarget.Rootfs -> resolved.file
            }

            if (!file.exists()) {
                return@withContext ToolResult(
                    callId = "linux_read",
                    toolName = definition.name,
                    isSuccess = false,
                    output = "File does not exist: $path",
                    error = "FILE_NOT_FOUND"
                )
            }

            if (file.isDirectory) {
                return@withContext ToolResult(
                    callId = "linux_read",
                    toolName = definition.name,
                    isSuccess = false,
                    output = "Path is a directory, not a file: $path. Use linux_list_directory instead.",
                    error = "IS_DIRECTORY"
                )
            }

            val maxBytes = 256 * 1024 // 256 KB safety limit
            val content = if (file.length() > maxBytes) {
                val partial = ByteArray(maxBytes)
                file.inputStream().use { it.read(partial) }
                String(partial, Charsets.UTF_8) + "\n\n[... Truncated: file size ${file.length()} bytes exceeds 256 KB preview limit]"
            } else {
                file.readText(Charsets.UTF_8)
            }

            ToolResult(
                callId = "linux_read",
                toolName = definition.name,
                isSuccess = true,
                output = content
            )
        }.getOrElse { e ->
            ToolResult(
                callId = "linux_read",
                toolName = definition.name,
                isSuccess = false,
                output = "Failed to read Linux file '$path': ${e.localizedMessage}",
                error = e.message
            )
        }
    }
}

class LinuxWriteFileTool(
    private val resolverProvider: () -> LinuxPathResolver
) : AngiTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "linux_write_file",
        description = "Write content to a file in the model's isolated Linux workspace. Virtual path must reside within '/workspace/**' (e.g. '/workspace/output.txt'). Physical Android paths and modifying rootfs are forbidden.",
        parameters = mapOf(
            "path" to ToolParameter("string", "Virtual Linux path inside /workspace (e.g. '/workspace/result.txt')"),
            "content" to ToolParameter("string", "Text content to write")
        ),
        requiredParameters = listOf("path", "content"),
        requiresUserConfirmation = false,
        category = ToolCategory.FILESYSTEM
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        val path = arguments["path"]?.toString() ?: return@withContext ToolResult(
            callId = "linux_write",
            toolName = definition.name,
            isSuccess = false,
            output = "Missing required argument 'path'",
            error = "INVALID_ARGUMENTS"
        )
        val content = arguments["content"]?.toString() ?: ""

        runCatching {
            val resolver = resolverProvider()
            val resolved = resolver.resolve(path, requireWritable = true)
            val file = when (resolved) {
                is LinuxPathResolver.ResolvedTarget.Workspace -> resolved.file
                is LinuxPathResolver.ResolvedTarget.Rootfs -> throw SecurityException("Writes to rootfs are forbidden")
            }

            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)

            ToolResult(
                callId = "linux_write",
                toolName = definition.name,
                isSuccess = true,
                output = "Successfully wrote ${content.length} characters to Linux workspace path: $path"
            )
        }.getOrElse { e ->
            ToolResult(
                callId = "linux_write",
                toolName = definition.name,
                isSuccess = false,
                output = "Failed to write Linux file '$path': ${e.localizedMessage}",
                error = e.message
            )
        }
    }
}

class LinuxListDirectoryTool(
    private val resolverProvider: () -> LinuxPathResolver
) : AngiTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "linux_list_directory",
        description = "List files and subdirectories at a virtual Linux path (e.g. '/workspace' or '/etc'). Physical Android paths are forbidden.",
        parameters = mapOf(
            "path" to ToolParameter("string", "Virtual Linux path to inspect (e.g. '/workspace')")
        ),
        requiredParameters = listOf("path"),
        requiresUserConfirmation = false,
        category = ToolCategory.FILESYSTEM
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        val path = arguments["path"]?.toString() ?: "/workspace"

        runCatching {
            val resolver = resolverProvider()
            val resolved = resolver.resolve(path, requireWritable = false)
            val dir = when (resolved) {
                is LinuxPathResolver.ResolvedTarget.Workspace -> resolved.file
                is LinuxPathResolver.ResolvedTarget.Rootfs -> resolved.file
            }

            if (!dir.exists()) {
                return@withContext ToolResult(
                    callId = "linux_list",
                    toolName = definition.name,
                    isSuccess = false,
                    output = "Directory does not exist: $path",
                    error = "DIRECTORY_NOT_FOUND"
                )
            }

            if (!dir.isDirectory) {
                return@withContext ToolResult(
                    callId = "linux_list",
                    toolName = definition.name,
                    isSuccess = false,
                    output = "Path is a regular file, not a directory: $path",
                    error = "NOT_A_DIRECTORY"
                )
            }

            val files = dir.listFiles() ?: emptyArray()
            if (files.isEmpty()) {
                return@withContext ToolResult(
                    callId = "linux_list",
                    toolName = definition.name,
                    isSuccess = true,
                    output = "Directory is empty: $path"
                )
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val listing = files.sortedWith(compareBy({ !it.isDirectory }, { it.name })).joinToString("\n") { file ->
                val type = if (file.isDirectory) "DIR " else "FILE"
                val size = if (file.isDirectory) "-" else "${file.length()}B"
                val time = dateFormat.format(Date(file.lastModified()))
                "[$type] $size\t$time\t${file.name}"
            }

            ToolResult(
                callId = "linux_list",
                toolName = definition.name,
                isSuccess = true,
                output = "Listing of virtual path $path (${files.size} items):\n$listing"
            )
        }.getOrElse { e ->
            ToolResult(
                callId = "linux_list",
                toolName = definition.name,
                isSuccess = false,
                output = "Failed to list Linux directory '$path': ${e.localizedMessage}",
                error = e.message
            )
        }
    }
}
