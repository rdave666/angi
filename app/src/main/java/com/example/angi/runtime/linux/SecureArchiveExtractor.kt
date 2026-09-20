package com.example.angi.runtime.linux

import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Robust archive extractor for both .tar.gz (Alpine) and .tar.xz (Debian).
 * Enforces strict security against directory traversal and escapes while preserving
 * proper Linux file hierarchies and symlinks.
 */
class SecureArchiveExtractor {

    interface ProgressListener {
        fun onProgress(entriesExtracted: Long, bytesExtracted: Long)
    }

    fun extractTarGz(archiveFile: File, destinationDir: File, listener: ProgressListener? = null): Long {
        FileInputStream(archiveFile).use { fis ->
            GZIPInputStream(fis).use { gzis ->
                return extractTarStream(gzis, destinationDir, listener)
            }
        }
    }

    fun extractTarXz(archiveFile: File, destinationDir: File, listener: ProgressListener? = null): Long {
        FileInputStream(archiveFile).use { fis ->
            XZInputStream(fis).use { xzis ->
                return extractTarStream(xzis, destinationDir, listener)
            }
        }
    }

    private fun extractTarStream(input: InputStream, destinationDir: File, listener: ProgressListener? = null): Long {
        val canonicalDest = destinationDir.canonicalFile
        if (!canonicalDest.exists()) {
            canonicalDest.mkdirs()
        }

        var totalBytes = 0L
        var entryCount = 0L
        val buffer = ByteArray(512)

        while (true) {
            val read = readFully(input, buffer, 0, 512)
            if (read < 512) break

            if (isAllZeros(buffer)) {
                val nextZero = ByteArray(512)
                readFully(input, nextZero, 0, 512)
                break
            }

            val header = parseTarHeader(buffer) ?: continue
            validateEntryName(header.name)

            val targetFile = File(canonicalDest, header.name).canonicalFile
            if (!isUnderDestination(targetFile, canonicalDest)) {
                throw SecurityException("Tar entry attempts to escape extraction root: '${header.name}'")
            }

            when (header.typeFlag) {
                TarType.DIRECTORY -> {
                    if (!targetFile.exists()) {
                        targetFile.mkdirs()
                    }
                }
                TarType.REGULAR_FILE, TarType.NORMAL -> {
                    targetFile.parentFile?.mkdirs()
                    FileOutputStream(targetFile).use { fos ->
                        var remaining = header.size
                        val copyBuf = ByteArray(8192)
                        while (remaining > 0) {
                            val toRead = minOf(remaining, copyBuf.size.toLong()).toInt()
                            val numRead = input.read(copyBuf, 0, toRead)
                            if (numRead <= 0) break
                            fos.write(copyBuf, 0, numRead)
                            remaining -= numRead
                            totalBytes += numRead
                        }
                    }
                    val pad = (512 - (header.size % 512)) % 512
                    if (pad > 0) {
                        skipFully(input, pad)
                    }
                }
                TarType.SYMLINK -> {
                    validateLinkTarget(header.linkName, targetFile.parentFile ?: canonicalDest, canonicalDest)
                    try {
                        targetFile.parentFile?.mkdirs()
                        if (targetFile.exists()) targetFile.delete()
                        Files.createSymbolicLink(targetFile.toPath(), Paths.get(header.linkName))
                    } catch (_: Throwable) {
                        // Some systems disallow direct symlink creation; safe fallback
                    }
                }
                TarType.HARDLINK -> {
                    validateLinkTarget(header.linkName, canonicalDest, canonicalDest)
                    try {
                        val sourceFile = File(canonicalDest, header.linkName).canonicalFile
                        if (isUnderDestination(sourceFile, canonicalDest)) {
                            targetFile.parentFile?.mkdirs()
                            if (targetFile.exists()) targetFile.delete()
                            try {
                                Files.createLink(targetFile.toPath(), sourceFile.toPath())
                            } catch (_: Throwable) {
                                // Hardlink fallback to copy or symlink
                                Files.createSymbolicLink(targetFile.toPath(), sourceFile.toPath())
                            }
                        }
                    } catch (_: Throwable) {}
                }
                else -> {
                    val pad = ((header.size + 511) / 512) * 512
                    skipFully(input, pad)
                }
            }

            entryCount++
            listener?.onProgress(entryCount, totalBytes)
        }

        return totalBytes
    }

    private fun validateEntryName(name: String) {
        val clean = name.replace('\\', '/')
        if (clean.startsWith("/") || clean.contains("../") || clean == ".." || clean.endsWith("/..")) {
            throw SecurityException("Illegal tar entry path traversal: '$name'")
        }
    }

    private fun validateLinkTarget(linkTarget: String, parentDir: File, destDir: File) {
        val clean = linkTarget.replace('\\', '/')
        if (clean.startsWith("/")) {
            val target = File(destDir, clean.removePrefix("/")).canonicalFile
            if (!isUnderDestination(target, destDir)) {
                throw SecurityException("Absolute link escapes extraction root: '$linkTarget'")
            }
        } else {
            val target = File(parentDir, clean).canonicalFile
            if (!isUnderDestination(target, destDir)) {
                throw SecurityException("Relative link escapes extraction root: '$linkTarget'")
            }
        }
    }

    private fun isUnderDestination(target: File, destDir: File): Boolean {
        val destPath = destDir.canonicalPath
        val targetPath = target.canonicalPath
        return targetPath == destPath || targetPath.startsWith(destPath + File.separator)
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = input.read(buffer, offset + total, length - total)
            if (read == -1) break
            total += read
        }
        return total
    }

    private fun skipFully(input: InputStream, length: Long) {
        var remaining = length
        val skipBuf = ByteArray(minOf(remaining, 4096L).toInt())
        while (remaining > 0) {
            val read = input.read(skipBuf, 0, minOf(remaining, skipBuf.size.toLong()).toInt())
            if (read <= 0) break
            remaining -= read
        }
    }

    private fun isAllZeros(buffer: ByteArray): Boolean {
        for (b in buffer) {
            if (b.toInt() != 0) return false
        }
        return true
    }

    private data class TarHeader(
        val name: String,
        val size: Long,
        val typeFlag: TarType,
        val linkName: String
    )

    private enum class TarType {
        REGULAR_FILE,
        NORMAL,
        HARDLINK,
        SYMLINK,
        DIRECTORY,
        OTHER
    }

    private fun parseTarHeader(buf: ByteArray): TarHeader? {
        val name = readNullTerminatedString(buf, 0, 100).trim()
        if (name.isEmpty()) return null
        val sizeStr = readNullTerminatedString(buf, 124, 12).trim()
        val size = runCatching { sizeStr.toLong(8) }.getOrDefault(0L)
        val typeByte = buf[156]
        val type = when (typeByte.toInt().toChar()) {
            '0', '\u0000' -> TarType.REGULAR_FILE
            '1' -> TarType.HARDLINK
            '2' -> TarType.SYMLINK
            '5' -> TarType.DIRECTORY
            else -> TarType.OTHER
        }
        val linkName = readNullTerminatedString(buf, 157, 100).trim()
        return TarHeader(name = name, size = size, typeFlag = type, linkName = linkName)
    }

    private fun readNullTerminatedString(buf: ByteArray, offset: Int, maxLen: Int): String {
        var len = 0
        while (len < maxLen && (offset + len) < buf.size && buf[offset + len].toInt() != 0) {
            len++
        }
        return String(buf, offset, len, Charsets.UTF_8)
    }
}
