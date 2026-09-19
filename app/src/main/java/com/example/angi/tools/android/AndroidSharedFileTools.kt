package com.example.angi.tools.android

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.angi.domain.saf.AndroidSharedResourceRegistry
import com.example.angi.domain.saf.SafCapability
import com.example.angi.domain.tools.AngiTool
import com.example.angi.domain.tools.ToolCategory
import com.example.angi.domain.tools.ToolDefinition
import com.example.angi.domain.tools.ToolParameter
import com.example.angi.domain.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AndroidReadFileTool(
    private val context: Context,
    private val registry: AndroidSharedResourceRegistry
) : AngiTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "android_read_file",
        description = "Read a file from an explicitly user-granted Android shared folder via Storage Access Framework. The user must have granted access to the folder via SAF. Requires the registered 'resourceId' and a 'relativePath' inside that folder. Raw physical Android paths (/sdcard, /storage) are forbidden.",
        parameters = mapOf(
            "resourceId" to ToolParameter("string", "Opaque ID of the user-granted resource (e.g. 'user_documents')"),
            "relativePath" to ToolParameter("string", "Relative path inside the granted folder (e.g. 'angi/notes.txt')")
        ),
        requiredParameters = listOf("resourceId", "relativePath"),
        requiresUserConfirmation = false,
        category = ToolCategory.FILESYSTEM
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        val resourceId = arguments["resourceId"]?.toString() ?: return@withContext ToolResult(
            callId = "android_read",
            toolName = definition.name,
            isSuccess = false,
            output = "Missing required argument 'resourceId'",
            error = "INVALID_ARGUMENTS"
        )
        val relativePath = arguments["relativePath"]?.toString() ?: return@withContext ToolResult(
            callId = "android_read",
            toolName = definition.name,
            isSuccess = false,
            output = "Missing required argument 'relativePath'",
            error = "INVALID_ARGUMENTS"
        )

        // Validate relative path does not attempt path traversal or absolute host access
        val sanitized = sanitizeRelativePath(relativePath)
        if (sanitized == null) {
            return@withContext ToolResult(
                callId = "android_read",
                toolName = definition.name,
                isSuccess = false,
                output = "Invalid or malicious relative path '$relativePath'. Traversal (../) and absolute host paths (/sdcard, /data) are strictly forbidden.",
                error = "PATH_TRAVERSAL"
            )
        }

        val resource = registry.getResource(resourceId) ?: return@withContext ToolResult(
            callId = "android_read",
            toolName = definition.name,
            isSuccess = false,
            output = "Resource '$resourceId' is not registered. The user must explicitly grant a folder first through the ANGI Diagnostics / SAF settings.",
            error = "RESOURCE_NOT_FOUND"
        )

        val treeUri = Uri.parse(resource.treeUriString)
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext ToolResult(
            callId = "android_read",
            toolName = definition.name,
            isSuccess = false,
            output = "Cannot access SAF tree URI for resource '$resourceId'. Permission may have been revoked by the system or user.",
            error = "PERMISSION_DENIED"
        )

        // Traverse hierarchy to target document
        val targetDoc = navigateToDocument(rootDoc, sanitized, createIfMissing = false)
        if (targetDoc == null || !targetDoc.exists()) {
            return@withContext ToolResult(
                callId = "android_read",
                toolName = definition.name,
                isSuccess = false,
                output = "File not found at relative path '$sanitized' inside resource '$resourceId'",
                error = "FILE_NOT_FOUND"
            )
        }

        if (targetDoc.isDirectory) {
            return@withContext ToolResult(
                callId = "android_read",
                toolName = definition.name,
                isSuccess = false,
                output = "Path '$sanitized' is a folder, not a file. Use android_list_directory instead.",
                error = "IS_DIRECTORY"
            )
        }

        runCatching {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(targetDoc.uri)
                ?: throw IllegalStateException("Could not open input stream for document URI")

            val maxBytes = 256 * 1024
            val bytes = inputStream.use { it.readBytes() }
            val text = if (bytes.size > maxBytes) {
                String(bytes.copyOf(maxBytes), Charsets.UTF_8) + "\n\n[... Truncated: file size ${bytes.size} bytes exceeds 256 KB limit]"
            } else {
                String(bytes, Charsets.UTF_8)
            }

            ToolResult(
                callId = "android_read",
                toolName = definition.name,
                isSuccess = true,
                output = text
            )
        }.getOrElse { e ->
            ToolResult(
                callId = "android_read",
                toolName = definition.name,
                isSuccess = false,
                output = "Error reading shared file '$sanitized': ${e.localizedMessage}",
                error = e.message
            )
        }
    }
}

class AndroidWriteFileTool(
    private val context: Context,
    private val registry: AndroidSharedResourceRegistry
) : AngiTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "android_write_file",
        description = "Write text content to a file in an explicitly user-granted Android shared folder via SAF. Requires the registered 'resourceId', a 'relativePath', and 'content'. Requires WRITE permission on the SAF resource.",
        parameters = mapOf(
            "resourceId" to ToolParameter("string", "Opaque ID of the user-granted resource (e.g. 'user_documents')"),
            "relativePath" to ToolParameter("string", "Relative path inside granted folder (e.g. 'angi/report.txt')"),
            "content" to ToolParameter("string", "Text content to write")
        ),
        requiredParameters = listOf("resourceId", "relativePath", "content"),
        requiresUserConfirmation = false, // Governed by CapabilityPolicy
        category = ToolCategory.FILESYSTEM
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        val resourceId = arguments["resourceId"]?.toString() ?: return@withContext ToolResult(
            callId = "android_write",
            toolName = definition.name,
            isSuccess = false,
            output = "Missing required argument 'resourceId'",
            error = "INVALID_ARGUMENTS"
        )
        val relativePath = arguments["relativePath"]?.toString() ?: return@withContext ToolResult(
            callId = "android_write",
            toolName = definition.name,
            isSuccess = false,
            output = "Missing required argument 'relativePath'",
            error = "INVALID_ARGUMENTS"
        )
        val content = arguments["content"]?.toString() ?: ""

        val sanitized = sanitizeRelativePath(relativePath)
        if (sanitized == null) {
            return@withContext ToolResult(
                callId = "android_write",
                toolName = definition.name,
                isSuccess = false,
                output = "Invalid relative path '$relativePath'. Traversal (../) and absolute host paths are strictly forbidden.",
                error = "PATH_TRAVERSAL"
            )
        }

        val resource = registry.getResource(resourceId) ?: return@withContext ToolResult(
            callId = "android_write",
            toolName = definition.name,
            isSuccess = false,
            output = "Resource '$resourceId' is not registered. Access denied.",
            error = "RESOURCE_NOT_FOUND"
        )

        // Capability check: Resource must permit writes
        if (resource.capability == SafCapability.READ) {
            return@withContext ToolResult(
                callId = "android_write",
                toolName = definition.name,
                isSuccess = false,
                output = "Resource '$resourceId' was granted with READ-ONLY capability. Write access is strictly denied.",
                error = "PERMISSION_DENIED"
            )
        }

        val treeUri = Uri.parse(resource.treeUriString)
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext ToolResult(
            callId = "android_write",
            toolName = definition.name,
            isSuccess = false,
            output = "Cannot access SAF tree URI for resource '$resourceId'. Permission may have been revoked.",
            error = "PERMISSION_DENIED"
        )

        runCatching {
            val targetDoc = navigateToDocument(rootDoc, sanitized, createIfMissing = true)
                ?: throw IllegalStateException("Failed to locate or create file at '$sanitized'")

            val outputStream = context.contentResolver.openOutputStream(targetDoc.uri, "wt")
                ?: throw IllegalStateException("Failed to open output stream for document URI")

            outputStream.use { it.write(content.toByteArray(Charsets.UTF_8)) }

            ToolResult(
                callId = "android_write",
                toolName = definition.name,
                isSuccess = true,
                output = "Successfully wrote ${content.length} characters to Android shared file '$sanitized' (Resource: $resourceId)."
            )
        }.getOrElse { e ->
            ToolResult(
                callId = "android_write",
                toolName = definition.name,
                isSuccess = false,
                output = "Failed to write Android shared file '$sanitized': ${e.localizedMessage}",
                error = e.message
            )
        }
    }
}

class AndroidListDirectoryTool(
    private val context: Context,
    private val registry: AndroidSharedResourceRegistry
) : AngiTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "android_list_directory",
        description = "List files and subfolders in an explicitly user-granted Android shared folder via SAF. Requires 'resourceId' and optional 'relativePath' inside that folder.",
        parameters = mapOf(
            "resourceId" to ToolParameter("string", "Opaque ID of the user-granted resource (e.g. 'user_documents')"),
            "relativePath" to ToolParameter("string", "Optional subfolder relative path (default empty for root of granted folder)")
        ),
        requiredParameters = listOf("resourceId"),
        requiresUserConfirmation = false,
        category = ToolCategory.FILESYSTEM
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        val resourceId = arguments["resourceId"]?.toString() ?: return@withContext ToolResult(
            callId = "android_list",
            toolName = definition.name,
            isSuccess = false,
            output = "Missing required argument 'resourceId'",
            error = "INVALID_ARGUMENTS"
        )
        val rawRelPath = arguments["relativePath"]?.toString() ?: ""
        val relativePath = if (rawRelPath.isBlank()) "" else {
            sanitizeRelativePath(rawRelPath) ?: return@withContext ToolResult(
                callId = "android_list",
                toolName = definition.name,
                isSuccess = false,
                output = "Invalid relative path '$rawRelPath'. Traversal (../) and absolute host paths are strictly forbidden.",
                error = "PATH_TRAVERSAL"
            )
        }

        val resource = registry.getResource(resourceId) ?: return@withContext ToolResult(
            callId = "android_list",
            toolName = definition.name,
            isSuccess = false,
            output = "Resource '$resourceId' is not registered. Access denied.",
            error = "RESOURCE_NOT_FOUND"
        )

        val treeUri = Uri.parse(resource.treeUriString)
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext ToolResult(
            callId = "android_list",
            toolName = definition.name,
            isSuccess = false,
            output = "Cannot access SAF tree URI for resource '$resourceId'. Permission may have been revoked.",
            error = "PERMISSION_DENIED"
        )

        val targetDir = if (relativePath.isEmpty()) {
            rootDoc
        } else {
            navigateToDocument(rootDoc, relativePath, createIfMissing = false)
        }

        if (targetDir == null || !targetDir.exists()) {
            return@withContext ToolResult(
                callId = "android_list",
                toolName = definition.name,
                isSuccess = false,
                output = "Folder not found at '$relativePath' inside resource '$resourceId'",
                error = "DIRECTORY_NOT_FOUND"
            )
        }

        if (!targetDir.isDirectory) {
            return@withContext ToolResult(
                callId = "android_list",
                toolName = definition.name,
                isSuccess = false,
                output = "Path '$relativePath' is a file, not a folder.",
                error = "NOT_A_DIRECTORY"
            )
        }

        val files = targetDir.listFiles()
        if (files.isEmpty()) {
            return@withContext ToolResult(
                callId = "android_list",
                toolName = definition.name,
                isSuccess = true,
                output = "Folder is empty (Resource '$resourceId', path: '$relativePath')"
            )
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val listing = files.sortedWith(compareBy({ !it.isDirectory }, { it.name ?: "" })).joinToString("\n") { doc ->
            val type = if (doc.isDirectory) "DIR " else "FILE"
            val size = if (doc.isDirectory) "-" else "${doc.length()}B"
            val time = dateFormat.format(Date(doc.lastModified()))
            "[$type] $size\t$time\t${doc.name}"
        }

        ToolResult(
            callId = "android_list",
            toolName = definition.name,
            isSuccess = true,
            output = "Listing of resource '$resourceId' (path: '${if (relativePath.isEmpty()) "/" else relativePath}', ${files.size} items):\n$listing"
        )
    }
}

// Helpers for SAF traversal and path hygiene
private fun sanitizeRelativePath(raw: String): String? {
    val clean = raw.trim().replace('\\', '/')
    if (clean.startsWith("/sdcard") || clean.startsWith("/storage") || clean.startsWith("/data")) {
        return null
    }
    val segments = clean.split('/').filter { it.isNotEmpty() && it != "." }
    val stack = mutableListOf<String>()
    for (seg in segments) {
        if (seg == "..") {
            if (stack.isNotEmpty()) {
                stack.removeAt(stack.size - 1)
            } else {
                return null // Attempt to traverse above root
            }
        } else {
            stack.add(seg)
        }
    }
    return stack.joinToString("/")
}

private fun navigateToDocument(root: DocumentFile, relativePath: String, createIfMissing: Boolean): DocumentFile? {
    val segments = relativePath.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return root

    var current = root
    for (i in 0 until segments.size - 1) {
        val dirName = segments[i]
        var child = current.findFile(dirName)
        if (child == null) {
            if (!createIfMissing) return null
            child = current.createDirectory(dirName) ?: return null
        }
        current = child
    }

    val finalName = segments.last()
    var file = current.findFile(finalName)
    if (file == null && createIfMissing) {
        file = current.createFile("application/octet-stream", finalName)
    }
    return file
}
