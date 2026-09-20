package com.example.angi.runtime.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class SecureArchiveExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testExtractionOfValidTarGz() {
        val destDir = tempFolder.newFolder("dest")
        val archiveFile = tempFolder.newFile("test.tar.gz")

        // Construct a simple valid tar.gz archive in memory with a file and a directory
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            // Entry 1: Directory "workspace/"
            val dirHeader = createTarHeader("workspace/", 0, '5')
            gzos.write(dirHeader)

            // Entry 2: Regular file "workspace/hello.txt" with content "Hello World\n"
            val content = "Hello World\n".toByteArray(Charsets.UTF_8)
            val fileHeader = createTarHeader("workspace/hello.txt", content.size.toLong(), '0')
            gzos.write(fileHeader)
            gzos.write(content)
            val pad = (512 - (content.size % 512)) % 512
            if (pad > 0) gzos.write(ByteArray(pad))

            // Two 512-byte zero blocks for tar EOF
            gzos.write(ByteArray(1024))
        }
        archiveFile.writeBytes(baos.toByteArray())

        val extractor = SecureArchiveExtractor()
        val extractedBytes = extractor.extractTarGz(archiveFile, destDir)

        assertTrue(extractedBytes > 0)
        val extractedFile = File(destDir, "workspace/hello.txt")
        assertTrue(extractedFile.exists())
        assertEquals("Hello World\n", extractedFile.readText())
    }

    @Test(expected = SecurityException::class)
    fun testDirectoryTraversalRejected() {
        val destDir = tempFolder.newFolder("dest")
        val archiveFile = tempFolder.newFile("malicious.tar.gz")

        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            val content = "malicious".toByteArray(Charsets.UTF_8)
            val fileHeader = createTarHeader("../escaped.txt", content.size.toLong(), '0')
            gzos.write(fileHeader)
            gzos.write(content)
            gzos.write(ByteArray(1024))
        }
        archiveFile.writeBytes(baos.toByteArray())

        val extractor = SecureArchiveExtractor()
        extractor.extractTarGz(archiveFile, destDir)
    }

    private fun createTarHeader(name: String, size: Long, typeFlag: Char): ByteArray {
        val header = ByteArray(512)
        // Name: bytes 0..99
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))

        // Mode: bytes 100..107
        val mode = "0000755\u0000".toByteArray(Charsets.UTF_8)
        System.arraycopy(mode, 0, header, 100, mode.size)

        // UID: 108..115, GID: 116..123
        val uid = "0000000\u0000".toByteArray(Charsets.UTF_8)
        System.arraycopy(uid, 0, header, 108, uid.size)
        System.arraycopy(uid, 0, header, 116, uid.size)

        // Size: bytes 124..135 (octal, 11 chars + space/null)
        val sizeStr = String.format("%011o ", size)
        System.arraycopy(sizeStr.toByteArray(Charsets.UTF_8), 0, header, 124, 12)

        // MTime: 136..147
        val mtime = "00000000000 ".toByteArray(Charsets.UTF_8)
        System.arraycopy(mtime, 0, header, 136, mtime.size)

        // TypeFlag: byte 156
        header[156] = typeFlag.code.toByte()

        // Magic: "ustar\u0000" at 257..262
        val magic = "ustar\u0000".toByteArray(Charsets.UTF_8)
        System.arraycopy(magic, 0, header, 257, magic.size)

        // Checksum at 148..155: calculate sum of all bytes treating 148..155 as spaces (ASCII 32)
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var sum = 0L
        for (b in header) sum += (b.toInt() and 0xFF)
        val chkStr = String.format("%06o\u0000 ", sum)
        System.arraycopy(chkStr.toByteArray(Charsets.UTF_8), 0, header, 148, 8)

        return header
    }
}
