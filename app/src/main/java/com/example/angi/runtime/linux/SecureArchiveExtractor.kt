package com.example.angi.runtime.linux

import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Robust, secure archive extractor for both .tar.gz (Alpine) and .tar.xz (Debian).
 * Enforces strict security against directory traversal while correctly handling
 * guest-absolute symlinks (e.g. /etc/alternatives/pager), relative symlinks,
 * hardlink fallbacks without host leakage, executable mode bits, and USTAR prefixes.
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
        val destPath = destinationDir.toPath().toAbsolutePath().normalize()
        if (!Files.exists(destPath)) {
            Files.createDirectories(destPath)
        }

        var totalBytes = 0L
        var entryCount = 0L
        val buffer = ByteArray(512)

        var nextLongName: String? = null
        var nextLongLinkName: String? = null

        while (true) {
            val read = readFully(input, buffer, 0, 512)
            if (read < 512) break

            if (isAllZeros(buffer)) {
                val nextZero = ByteArray(512)
                readFully(input, nextZero, 0, 512)
                break
            }

            val header = parseTarHeader(buffer) ?: continue

            // Handle GNU long pathname / long linkname entries
            if (header.typeFlag == TarType.GNU_LONG_NAME) {
                nextLongName = readGnuLongEntry(input, header.size)
                continue
            }
            if (header.typeFlag == TarType.GNU_LONG_LINK) {
                nextLongLinkName = readGnuLongEntry(input, header.size)
                continue
            }

            val rawEntryName = nextLongName ?: header.name
            nextLongName = null
            val rawLinkName = nextLongLinkName ?: header.linkName
            nextLongLinkName = null

            val cleanEntryName = rawEntryName.replace('\\', '/').trimStart('/')
            if (cleanEntryName.isEmpty() || cleanEntryName == ".") {
                // Root directory or empty entry - skip
                if (header.size > 0) {
                    val pad = ((header.size + 511) / 512) * 512
                    skipFully(input, pad)
                }
                continue
            }

            validateEntryName(cleanEntryName)

            // Lexical containment check without following links
            val targetPath = destPath.resolve(cleanEntryName).normalize()
            if (!targetPath.startsWith(destPath)) {
                throw SecurityException("Tar entry attempts to escape extraction root: '$rawEntryName'")
            }

            when (header.typeFlag) {
                TarType.DIRECTORY -> {
                    Files.createDirectories(targetPath)
                }
                TarType.REGULAR_FILE, TarType.NORMAL -> {
                    targetPath.parent?.let { Files.createDirectories(it) }
                    deleteNoFollow(targetPath)

                    FileOutputStream(targetPath.toFile()).use { fos ->
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

                    // Preserve executable mode bits (parse mode field offset 100)
                    applyExecutablePermission(targetPath, header.mode)
                }
                TarType.SYMLINK -> {
                    val targetParent = targetPath.parent ?: destPath
                    Files.createDirectories(targetParent)
                    deleteNoFollow(targetPath)

                    val cleanLink = rawLinkName.replace('\\', '/')
                    val symlinkTarget: Path = if (cleanLink.startsWith("/")) {
                        // 1. Guest-absolute symlink (e.g. "/etc/alternatives/pager")
                        // Treat as path inside the guest rootfs, NOT Android /
                        val guestTarget = Paths.get("/").resolve(cleanLink.trimStart('/')).normalize()
                        val guestParentRel = destPath.relativize(targetParent).toString()
                        val guestParent = Paths.get("/").resolve(guestParentRel).normalize()
                        // 3. Rewrite guest-absolute symlink to an equivalent relative link
                        // so Android host access cannot escape rootfs.
                        val relTarget = guestParent.relativize(guestTarget)
                        if (relTarget.toString().isEmpty()) Paths.get(".") else relTarget
                    } else {
                        // 2. Relative symlink
                        // Lexical containment check without following links:
                        val lexicallyResolved = targetParent.resolve(cleanLink).normalize()
                        if (!lexicallyResolved.startsWith(destPath)) {
                            throw SecurityException("Relative link escapes extraction root: '$rawLinkName'")
                        }
                        Paths.get(cleanLink)
                    }

                    try {
                        Files.createSymbolicLink(targetPath, symlinkTarget)
                    } catch (_: Throwable) {
                        // Safe fallback on systems disallowing symlinks
                    }
                }
                TarType.HARDLINK -> {
                    val cleanSourceRel = rawLinkName.replace('\\', '/').trimStart('/')
                    validateEntryName(cleanSourceRel)
                    val sourcePath = destPath.resolve(cleanSourceRel).normalize()
                    if (!sourcePath.startsWith(destPath)) {
                        throw SecurityException("Hardlink target escapes extraction root: '$rawLinkName'")
                    }

                    val targetParent = targetPath.parent ?: destPath
                    Files.createDirectories(targetParent)
                    deleteNoFollow(targetPath)

                    try {
                        Files.createLink(targetPath, sourcePath)
                    } catch (_: Throwable) {
                        // Hardlinks may be rejected by Android's protected_hardlinks.
                        // Safe fallback: never create fallback links containing Android host absolute paths!
                        try {
                            val relTarget = targetParent.relativize(sourcePath)
                            val finalRelTarget = if (relTarget.toString().isEmpty()) Paths.get(".") else relTarget
                            Files.createSymbolicLink(targetPath, finalRelTarget)
                        } catch (_: Throwable) {
                            if (Files.exists(sourcePath)) {
                                Files.copy(sourcePath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
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

    private fun deleteNoFollow(path: Path) {
        try {
            if (Files.isSymbolicLink(path) || Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(path)
            }
        } catch (_: Throwable) {
            path.toFile().delete()
        }
    }

    private fun applyExecutablePermission(targetPath: Path, mode: Int) {
        // Parse mode field offset 100: execute bits in octal 0111 (user, group, other execute)
        val isExecutable = (mode and 73) != 0 // 73 decimal = 0111 octal (0b001_001_001)
        if (isExecutable) {
            val isOtherExecutable = (mode and 1) != 0
            try {
                targetPath.toFile().setExecutable(true, !isOtherExecutable)
            } catch (_: Throwable) {
                targetPath.toFile().setExecutable(true)
            }
        }
    }

    private fun validateEntryName(name: String) {
        val clean = name.replace('\\', '/')
        if (clean.split('/').any { it == ".." }) {
            throw SecurityException("Illegal tar entry path traversal: '$name'")
        }
    }

    private fun readGnuLongEntry(input: InputStream, size: Long): String {
        val buf = ByteArray(size.toInt())
        readFully(input, buf, 0, buf.size)
        val pad = (512 - (size % 512)) % 512
        if (pad > 0) skipFully(input, pad)
        var len = 0
        while (len < buf.size && buf[len].toInt() != 0) len++
        return String(buf, 0, len, Charsets.UTF_8)
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
        val mode: Int,
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
        GNU_LONG_NAME,
        GNU_LONG_LINK,
        OTHER
    }

    private fun parseTarHeader(buf: ByteArray): TarHeader? {
        val rawName = readNullTerminatedString(buf, 0, 100).trim()
        val magic = readNullTerminatedString(buf, 257, 6).trim()
        val isUstar = magic.startsWith("ustar")

        // Support USTAR prefix field offset 345
        val name = if (isUstar) {
            val prefix = readNullTerminatedString(buf, 345, 155).trim()
            if (prefix.isNotEmpty()) {
                val cleanPrefix = prefix.replace('\\', '/').trimEnd('/')
                val cleanRaw = rawName.replace('\\', '/').trimStart('/')
                if (cleanRaw.isEmpty()) cleanPrefix else "$cleanPrefix/$cleanRaw"
            } else {
                rawName
            }
        } else {
            rawName
        }

        if (name.isEmpty()) return null

        // Parse mode field offset 100 (8 bytes, octal)
        val modeStr = readNullTerminatedString(buf, 100, 8).trim()
        val mode = runCatching {
            val digits = modeStr.filter { it in '0'..'7' }
            if (digits.isNotEmpty()) digits.toInt(8) else 0
        }.getOrDefault(0)

        // Parse size offset 124 (12 bytes, octal)
        val sizeStr = readNullTerminatedString(buf, 124, 12).trim()
        val size = runCatching {
            val digits = sizeStr.filter { it in '0'..'7' }
            if (digits.isNotEmpty()) digits.toLong(8) else 0L
        }.getOrDefault(0L)

        val typeByte = buf[156].toInt().toChar()
        val type = when (typeByte) {
            '0', '\u0000' -> TarType.REGULAR_FILE
            '1' -> TarType.HARDLINK
            '2' -> TarType.SYMLINK
            '5' -> TarType.DIRECTORY
            'L' -> TarType.GNU_LONG_NAME
            'K' -> TarType.GNU_LONG_LINK
            else -> TarType.OTHER
        }

        val linkName = readNullTerminatedString(buf, 157, 100).trim()

        return TarHeader(
            name = name,
            mode = mode,
            size = size,
            typeFlag = type,
            linkName = linkName
        )
    }

    private fun readNullTerminatedString(buf: ByteArray, offset: Int, maxLen: Int): String {
        var len = 0
        while (len < maxLen && (offset + len) < buf.size && buf[offset + len].toInt() != 0) {
            len++
        }
        return String(buf, offset, len, Charsets.UTF_8)
    }
}
