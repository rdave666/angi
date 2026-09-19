package com.example.angi.runtime.linux

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Secure Tar/GZ Archive Extractor.
 *
 * Implements strict security defenses:
 * - Rejects archive traversal (Zip Slip / Tar Slip)
 * - Rejects entries escaping extraction root through `../` or absolute leading slashes
 * - Rejects symlinks or hardlinks pointing outside the destination directory
 * - Canonicalizes all paths before extracting
 */
class SecureArchiveExtractor {

    interface ProgressListener {
        fun onProgress(extractedEntries: Long, extractedBytes: Long)
    }

    /**
     * Extracts a .tar.gz archive safely into [destDir].
     *
     * @param tarGzFile Source archive file
     * @param destDir Target directory for extraction
     * @param listener Optional progress listener
     * @return Total bytes extracted
     */
    fun extractTarGz(
        tarGzFile: File,
        destDir: File,
        listener: ProgressListener? = null
    ): Long {
        if (!destDir.exists()) destDir.mkdirs()
        val canonicalDest = destDir.canonicalFile

        var totalBytes = 0L
        var entryCount = 0L

        FileInputStream(tarGzFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                GZIPInputStream(bis).use { gzis ->
                    val buffer = ByteArray(512)

                    while (true) {
                        // Read 512-byte tar header
                        val read = readFully(gzis, buffer, 0, 512)
                        if (read < 512) break

                        // All zeros indicates end of tar archive
                        if (isAllZeros(buffer)) {
                            // Check if next 512 is also zero
                            val nextZero = ByteArray(512)
                            readFully(gzis, nextZero, 0, 512)
                            break
                        }

                        val header = parseTarHeader(buffer) ?: continue

                        // Security Check 1: Traversal in entry name
                        validateEntryName(header.name)

                        // Security Check 2: Resolve canonical target
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
                                        val numRead = gzis.read(copyBuf, 0, toRead)
                                        if (numRead <= 0) break
                                        fos.write(copyBuf, 0, numRead)
                                        remaining -= numRead
                                        totalBytes += numRead
                                    }
                                }
                                // Tar entries are padded to 512-byte boundary
                                val pad = (512 - (header.size % 512)) % 512
                                if (pad > 0) {
                                    skipFully(gzis, pad)
                                }
                            }
                            TarType.SYMLINK -> {
                                // Security Check 3: Validate symlink target does not escape canonicalDest
                                validateLinkTarget(header.linkName, targetFile.parentFile ?: canonicalDest, canonicalDest)
                                try {
                                    targetFile.parentFile?.mkdirs()
                                    // Remove if exists
                                    if (targetFile.exists()) targetFile.delete()
                                    java.nio.file.Files.createSymbolicLink(
                                        targetFile.toPath(),
                                        java.nio.file.Paths.get(header.linkName)
                                    )
                                } catch (_: Throwable) {
                                    // Some Android storage setups don't support symlinks; write a stub or skip cleanly
                                }
                            }
                            TarType.HARDLINK -> {
                                validateLinkTarget(header.linkName, canonicalDest, canonicalDest)
                                try {
                                    val sourceFile = File(canonicalDest, header.linkName).canonicalFile
                                    if (isUnderDestination(sourceFile, canonicalDest)) {
                                        targetFile.parentFile?.mkdirs()
                                        java.nio.file.Files.createLink(targetFile.toPath(), sourceFile.toPath())
                                    }
                                } catch (_: Throwable) {
                                    // Hardlink fallback
                                }
                            }
                            else -> {
                                // Skip unhandled types (pax headers, device nodes, etc.)
                                val pad = ((header.size + 511) / 512) * 512
                                skipFully(gzis, pad)
                            }
                        }

                        entryCount++
                        listener?.onProgress(entryCount, totalBytes)
                    }
                }
            }
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
            // Absolute link must still be under destDir if interpreted within rootfs
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
