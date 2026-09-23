package com.example.angi.runtime.linux

import com.example.angi.runtime.proot.DebianDistroSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPOutputStream

class SecureArchiveExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testExtractionOfValidTarGz() {
        val destDir = tempFolder.newFolder("dest")
        val archiveFile = tempFolder.newFile("test.tar.gz")

        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            val dirHeader = createTarHeader("workspace/", 0, '5')
            gzos.write(dirHeader)

            val content = "Hello World\n".toByteArray(Charsets.UTF_8)
            val fileHeader = createTarHeader("workspace/hello.txt", content.size.toLong(), '0')
            gzos.write(fileHeader)
            gzos.write(content)
            val pad = (512 - (content.size % 512)) % 512
            if (pad > 0) gzos.write(ByteArray(pad))

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

    // 1. absolute guest symlink test
    @Test
    fun testAbsoluteGuestSymlinkRewrittenToRelative() {
        val destDir = tempFolder.newFolder("dest_abs_symlink")
        val archiveFile = tempFolder.newFile("abs_symlink.tar.gz")

        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            // Entry 1: bin/bash regular file
            val bashContent = "#!/bin/bash\necho ok\n".toByteArray(Charsets.UTF_8)
            gzos.write(createTarHeader("bin/bash", bashContent.size.toLong(), '0'))
            gzos.write(bashContent)
            val pad1 = (512 - (bashContent.size % 512)) % 512
            if (pad1 > 0) gzos.write(ByteArray(pad1))

            // Entry 2: usr/bin/sh symlink -> /bin/bash (absolute guest link)
            gzos.write(createTarHeader("usr/bin/sh", 0, '2', linkName = "/bin/bash"))

            gzos.write(ByteArray(1024))
        }
        archiveFile.writeBytes(baos.toByteArray())

        val extractor = SecureArchiveExtractor()
        extractor.extractTarGz(archiveFile, destDir)

        val symlinkFile = File(destDir, "usr/bin/sh")
        assertTrue("Symlink file must exist", symlinkFile.exists())
        assertTrue("Must be symbolic link", Files.isSymbolicLink(symlinkFile.toPath()))

        val linkTarget = Files.readSymbolicLink(symlinkFile.toPath())
        // Rewritten to relative from usr/bin to /bin/bash -> ../../bin/bash
        assertEquals(Paths.get("../../bin/bash"), linkTarget)
        assertEquals("#!/bin/bash\necho ok\n", symlinkFile.readText())
    }

    // 2. /etc/alternatives/pager test (the exact device failure case)
    @Test
    fun testEtcAlternativesPagerGuestAbsoluteSymlink() {
        val destDir = tempFolder.newFolder("dest_pager")
        val archiveFile = tempFolder.newFile("pager.tar.gz")

        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            // Entry 1: etc/alternatives/pager regular file
            val pagerContent = "less pager mock binary".toByteArray(Charsets.UTF_8)
            gzos.write(createTarHeader("etc/alternatives/pager", pagerContent.size.toLong(), '0'))
            gzos.write(pagerContent)
            val pad = (512 - (pagerContent.size % 512)) % 512
            if (pad > 0) gzos.write(ByteArray(pad))

            // Entry 2: usr/bin/pager symlink -> /etc/alternatives/pager
            gzos.write(createTarHeader("usr/bin/pager", 0, '2', linkName = "/etc/alternatives/pager"))

            gzos.write(ByteArray(1024))
        }
        archiveFile.writeBytes(baos.toByteArray())

        val extractor = SecureArchiveExtractor()
        extractor.extractTarGz(archiveFile, destDir)

        val pagerSymlink = File(destDir, "usr/bin/pager")
        assertTrue("usr/bin/pager must exist", pagerSymlink.exists())
        assertTrue("usr/bin/pager must be symbolic link", Files.isSymbolicLink(pagerSymlink.toPath()))

        val linkTarget = Files.readSymbolicLink(pagerSymlink.toPath())
        // From usr/bin to /etc/alternatives/pager -> ../../etc/alternatives/pager
        assertEquals(Paths.get("../../etc/alternatives/pager"), linkTarget)
        assertEquals("less pager mock binary", pagerSymlink.readText())
    }

    // 3. relative symlink test
    @Test
    fun testRelativeSymlink() {
        val destDir = tempFolder.newFolder("dest_rel_symlink")
        val archiveFile = tempFolder.newFile("rel_symlink.tar.gz")

        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            val content = "content of original".toByteArray(Charsets.UTF_8)
            gzos.write(createTarHeader("usr/bin/original", content.size.toLong(), '0'))
            gzos.write(content)
            val pad = (512 - (content.size % 512)) % 512
            if (pad > 0) gzos.write(ByteArray(pad))

            // Symlink in same directory pointing to original
            gzos.write(createTarHeader("usr/bin/alias", 0, '2', linkName = "original"))

            gzos.write(ByteArray(1024))
        }
        archiveFile.writeBytes(baos.toByteArray())

        val extractor = SecureArchiveExtractor()
        extractor.extractTarGz(archiveFile, destDir)

        val aliasFile = File(destDir, "usr/bin/alias")
        assertTrue(aliasFile.exists())
        assertTrue(Files.isSymbolicLink(aliasFile.toPath()))
        assertEquals(Paths.get("original"), Files.readSymbolicLink(aliasFile.toPath()))
        assertEquals("content of original", aliasFile.readText())
    }

    // 4. traversal rejection test
    @Test(expected = SecurityException::class)
    fun testDirectoryTraversalRejected() {
        val destDir = tempFolder.newFolder("dest_traversal")
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

    @Test(expected = SecurityException::class)
    fun testRelativeSymlinkEscapingRootRejected() {
        val destDir = tempFolder.newFolder("dest_escape_link")
        val archiveFile = tempFolder.newFile("escape_link.tar.gz")

        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            // Symlink trying to escape rootfs via excessive relative levels
            gzos.write(createTarHeader("usr/bin/escape", 0, '2', linkName = "../../../../../../../../etc/shadow"))
            gzos.write(ByteArray(1024))
        }
        archiveFile.writeBytes(baos.toByteArray())

        val extractor = SecureArchiveExtractor()
        extractor.extractTarGz(archiveFile, destDir)
    }

    // 5. executable bit test
    @Test
    fun testExecutableBitPreserved() {
        val destDir = tempFolder.newFolder("dest_exec")
        val archiveFile = tempFolder.newFile("exec.tar.gz")

        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            // Executable file: mode 0755
            val scriptContent = "#!/bin/sh\necho hi\n".toByteArray(Charsets.UTF_8)
            gzos.write(createTarHeader("usr/bin/script.sh", scriptContent.size.toLong(), '0', mode = "0000755\u0000"))
            gzos.write(scriptContent)
            val pad1 = (512 - (scriptContent.size % 512)) % 512
            if (pad1 > 0) gzos.write(ByteArray(pad1))

            // Non-executable file: mode 0644
            val dataContent = "data".toByteArray(Charsets.UTF_8)
            gzos.write(createTarHeader("usr/share/data.txt", dataContent.size.toLong(), '0', mode = "0000644\u0000"))
            gzos.write(dataContent)
            val pad2 = (512 - (dataContent.size % 512)) % 512
            if (pad2 > 0) gzos.write(ByteArray(pad2))

            gzos.write(ByteArray(1024))
        }
        archiveFile.writeBytes(baos.toByteArray())

        val extractor = SecureArchiveExtractor()
        extractor.extractTarGz(archiveFile, destDir)

        val execFile = File(destDir, "usr/bin/script.sh")
        val dataFile = File(destDir, "usr/share/data.txt")

        assertTrue("Script file must have execute permission", execFile.canExecute())
        assertFalse("Data file must not have execute permission", dataFile.canExecute())
    }

    // 6. USTAR prefix test
    @Test
    fun testUstarPrefixFieldOffset345Supported() {
        val destDir = tempFolder.newFolder("dest_ustar")
        val archiveFile = tempFolder.newFile("ustar.tar.gz")

        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            val content = "nested ustar content\n".toByteArray(Charsets.UTF_8)
            val prefix = "nested/deep/directory/structure"
            val name = "deep_file.txt"
            gzos.write(createTarHeader(name = name, size = content.size.toLong(), typeFlag = '0', prefix = prefix))
            gzos.write(content)
            val pad = (512 - (content.size % 512)) % 512
            if (pad > 0) gzos.write(ByteArray(pad))

            gzos.write(ByteArray(1024))
        }
        archiveFile.writeBytes(baos.toByteArray())

        val extractor = SecureArchiveExtractor()
        extractor.extractTarGz(archiveFile, destDir)

        val extracted = File(destDir, "nested/deep/directory/structure/deep_file.txt")
        assertTrue("File extracted using USTAR prefix must exist", extracted.exists())
        assertEquals("nested ustar content\n", extracted.readText())
    }

    // 7. hardlink containment test
    @Test
    fun testHardlinkContainmentAndSafety() {
        val destDir = tempFolder.newFolder("dest_hardlink")
        val archiveFile = tempFolder.newFile("hardlink.tar.gz")

        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            val content = "hardlinked shared data".toByteArray(Charsets.UTF_8)
            gzos.write(createTarHeader("usr/bin/original_bin", content.size.toLong(), '0'))
            gzos.write(content)
            val pad = (512 - (content.size % 512)) % 512
            if (pad > 0) gzos.write(ByteArray(pad))

            // Hardlink: type '1', linkName points to usr/bin/original_bin
            gzos.write(createTarHeader("usr/bin/hardlinked_bin", 0, '1', linkName = "usr/bin/original_bin"))

            gzos.write(ByteArray(1024))
        }
        archiveFile.writeBytes(baos.toByteArray())

        val extractor = SecureArchiveExtractor()
        extractor.extractTarGz(archiveFile, destDir)

        val hardlinkedFile = File(destDir, "usr/bin/hardlinked_bin")
        assertTrue("Hardlinked file must exist", hardlinkedFile.exists())
        assertEquals("hardlinked shared data", hardlinkedFile.readText())

        // If fallback symlink was created on systems without hardlink support, ensure it never leaks Android host path
        if (Files.isSymbolicLink(hardlinkedFile.toPath())) {
            val linkTarget = Files.readSymbolicLink(hardlinkedFile.toPath()).toString()
            assertFalse("Link target must not contain host absolute path", linkTarget.startsWith("/"))
        }
    }

    @Test(expected = SecurityException::class)
    fun testHardlinkEscapingRootRejected() {
        val destDir = tempFolder.newFolder("dest_escape_hardlink")
        val archiveFile = tempFolder.newFile("escape_hardlink.tar.gz")

        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            gzos.write(createTarHeader("usr/bin/escaped_hl", 0, '1', linkName = "../../../../../etc/passwd"))
            gzos.write(ByteArray(1024))
        }
        archiveFile.writeBytes(baos.toByteArray())

        val extractor = SecureArchiveExtractor()
        extractor.extractTarGz(archiveFile, destDir)
    }

    // 8. resolv.conf symlink replacement test
    @Test
    fun testResolvConfSymlinkReplacementWithNoFollowSemantics() {
        val rootfsDir = tempFolder.newFolder("rootfs_resolv")
        val etc = File(rootfsDir, "etc").apply { mkdirs() }
        val resolvConf = File(etc, "resolv.conf")

        // Simulate Debian rootfs where /etc/resolv.conf is initially a dangling symlink to systemd stub resolver
        val stubTarget = Paths.get("../run/systemd/resolve/stub-resolv.conf")
        Files.createSymbolicLink(resolvConf.toPath(), stubTarget)
        assertTrue("Precondition: resolv.conf is a symlink", Files.isSymbolicLink(resolvConf.toPath()))

        // Run writeResolvConf
        DebianDistroSpec.writeResolvConf(rootfsDir)

        assertFalse("resolv.conf must no longer be a symlink", Files.isSymbolicLink(resolvConf.toPath()))
        assertTrue("resolv.conf regular file must exist", resolvConf.exists())
        val content = resolvConf.readText()
        assertTrue("resolv.conf must contain nameserver 8.8.8.8", content.contains("nameserver 8.8.8.8"))
        assertTrue("resolv.conf must contain nameserver 1.1.1.1", content.contains("nameserver 1.1.1.1"))
    }

    private fun createTarHeader(
        name: String,
        size: Long = 0L,
        typeFlag: Char = '0',
        linkName: String = "",
        mode: String = "0000755\u0000",
        prefix: String = ""
    ): ByteArray {
        val header = ByteArray(512)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))

        val modeBytes = mode.toByteArray(Charsets.UTF_8)
        System.arraycopy(modeBytes, 0, header, 100, minOf(modeBytes.size, 8))

        val uid = "0000000\u0000".toByteArray(Charsets.UTF_8)
        System.arraycopy(uid, 0, header, 108, uid.size)
        System.arraycopy(uid, 0, header, 116, uid.size)

        val sizeStr = String.format("%011o ", size)
        System.arraycopy(sizeStr.toByteArray(Charsets.UTF_8), 0, header, 124, 12)

        val mtime = "00000000000 ".toByteArray(Charsets.UTF_8)
        System.arraycopy(mtime, 0, header, 136, mtime.size)

        header[156] = typeFlag.code.toByte()

        if (linkName.isNotEmpty()) {
            val linkBytes = linkName.toByteArray(Charsets.UTF_8)
            System.arraycopy(linkBytes, 0, header, 157, minOf(linkBytes.size, 100))
        }

        val magic = "ustar\u0000".toByteArray(Charsets.UTF_8)
        System.arraycopy(magic, 0, header, 257, magic.size)

        val version = "00".toByteArray(Charsets.UTF_8)
        System.arraycopy(version, 0, header, 263, version.size)

        if (prefix.isNotEmpty()) {
            val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
            System.arraycopy(prefixBytes, 0, header, 345, minOf(prefixBytes.size, 155))
        }

        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var sum = 0L
        for (b in header) sum += (b.toInt() and 0xFF)
        val chkStr = String.format("%06o\u0000 ", sum)
        System.arraycopy(chkStr.toByteArray(Charsets.UTF_8), 0, header, 148, 8)

        return header
    }
}
