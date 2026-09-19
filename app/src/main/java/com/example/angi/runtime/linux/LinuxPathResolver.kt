package com.example.angi.runtime.linux

import java.io.File
import java.io.IOException

/**
 * Single centralized virtual path resolver for isolated Linux environments.
 *
 * Requirements:
 * - Maps virtual paths like `/workspace/test.txt` or `/etc/os-release` to real isolated paths
 * - Rootfs (/): read-only to model tools
 * - Workspace (/workspace): model-owned read/write
 * - Rejects any traversal attempt (../, symlink escapes, absolute Android physical paths like /data or /sdcard)
 * - Never leaks physical host paths (e.g. /data/user/0/...) to the model
 */
class LinuxPathResolver(
    private val environmentDir: File
) {
    val rootfsDir: File = File(environmentDir, "rootfs")
    val workspaceDir: File = File(environmentDir, "workspace")
    val downloadsDir: File = File(environmentDir, "downloads")

    init {
        if (!environmentDir.exists()) environmentDir.mkdirs()
        if (!rootfsDir.exists()) rootfsDir.mkdirs()
        if (!workspaceDir.exists()) workspaceDir.mkdirs()
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
    }

    sealed class ResolvedTarget {
        data class Workspace(val file: File, val virtualPath: String) : ResolvedTarget()
        data class Rootfs(val file: File, val virtualPath: String) : ResolvedTarget()
    }

    /**
     * Resolves a model virtual path and strictly verifies containment within the isolated environment.
     *
     * @param virtualPath Virtual Linux path supplied by the model (e.g., "/workspace/notes.txt")
     * @param requireWritable If true, the path MUST reside within `/workspace`
     */
    fun resolve(virtualPath: String, requireWritable: Boolean = false): ResolvedTarget {
        val trimmed = virtualPath.trim()
        if (trimmed.isEmpty()) {
            throw SecurityException("Path cannot be empty")
        }

        // Reject explicit Android host filesystem paths
        if (trimmed.startsWith("/data") ||
            trimmed.startsWith("/sdcard") ||
            trimmed.startsWith("/storage") ||
            trimmed.startsWith("/system") ||
            trimmed.startsWith("/proc") ||
            trimmed.startsWith("/sys") ||
            trimmed.startsWith("/dev")
        ) {
            throw SecurityException("Access to Android host physical filesystem paths is strictly forbidden: '$trimmed'")
        }

        // Virtual path must be absolute relative to Linux root (must start with /)
        val normalizedVirtual = normalizeVirtualPath(trimmed)

        val envCanonical = environmentDir.canonicalFile
        val wsCanonical = workspaceDir.canonicalFile
        val rootfsCanonical = rootfsDir.canonicalFile

        if (normalizedVirtual == "/workspace" || normalizedVirtual.startsWith("/workspace/")) {
            val relPath = normalizedVirtual.removePrefix("/workspace").removePrefix("/")
            val targetFile = if (relPath.isEmpty()) wsCanonical else File(wsCanonical, relPath)
            val targetCanonical = targetFile.canonicalFile

            // Containment check
            if (!isChildOf(targetCanonical, wsCanonical) && targetCanonical != wsCanonical) {
                throw SecurityException("Path traversal attempt outside workspace: '$virtualPath'")
            }

            // Verify target does not escape outside the isolated environment dir
            if (!isChildOf(targetCanonical, envCanonical) && targetCanonical != envCanonical) {
                throw SecurityException("Path escape attempt outside isolated environment: '$virtualPath'")
            }

            return ResolvedTarget.Workspace(targetCanonical, normalizedVirtual)
        } else {
            // Path inside rootfs
            if (requireWritable) {
                throw SecurityException("Linux root filesystem ($normalizedVirtual) is read-only. Writes are restricted to /workspace/**")
            }

            val relPath = normalizedVirtual.removePrefix("/")
            val targetFile = if (relPath.isEmpty()) rootfsCanonical else File(rootfsCanonical, relPath)
            val targetCanonical = targetFile.canonicalFile

            // Containment check
            if (!isChildOf(targetCanonical, rootfsCanonical) && targetCanonical != rootfsCanonical) {
                throw SecurityException("Path traversal attempt outside rootfs: '$virtualPath'")
            }

            if (!isChildOf(targetCanonical, envCanonical) && targetCanonical != envCanonical) {
                throw SecurityException("Path escape attempt outside isolated environment: '$virtualPath'")
            }

            return ResolvedTarget.Rootfs(targetCanonical, normalizedVirtual)
        }
    }

    companion object {
        fun normalizeVirtualPath(rawPath: String): String {
            val clean = rawPath.replace('\\', '/')
            val segments = clean.split('/').filter { it.isNotEmpty() && it != "." }
            val stack = mutableListOf<String>()

            for (seg in segments) {
                if (seg == "..") {
                    if (stack.isNotEmpty()) {
                        stack.removeAt(stack.size - 1)
                    } else {
                        throw SecurityException("Path traverses above virtual root: '$rawPath'")
                    }
                } else {
                    stack.add(seg)
                }
            }

            return "/" + stack.joinToString("/")
        }

        fun isChildOf(child: File, parent: File): Boolean {
            val parentPath = parent.canonicalPath
            val childPath = child.canonicalPath
            return childPath.startsWith(parentPath + File.separator)
        }
    }
}
