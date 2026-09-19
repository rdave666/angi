package com.example.angi

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.angi.data.environment.DefaultLinuxEnvironmentManager
import com.example.angi.data.saf.DefaultAndroidSharedResourceRegistry
import com.example.angi.domain.environment.LinuxEnvironmentDefinition
import com.example.angi.domain.environment.PinnedLinuxEnvironments
import com.example.angi.domain.saf.AndroidSharedResource
import com.example.angi.domain.saf.SafCapability
import com.example.angi.runtime.linux.LinuxPathResolver
import com.example.angi.runtime.linux.SecureArchiveExtractor
import com.example.angi.tools.android.AndroidListDirectoryTool
import com.example.angi.tools.android.AndroidReadFileTool
import com.example.angi.tools.android.AndroidWriteFileTool
import com.example.angi.tools.linux.LinuxListDirectoryTool
import com.example.angi.tools.linux.LinuxReadFileTool
import com.example.angi.tools.linux.LinuxWriteFileTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckpointBFileSystemTest {

    private lateinit var context: Context
    private lateinit var testEnvDir: File
    private lateinit var pathResolver: LinuxPathResolver

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testEnvDir = File(context.filesDir, "test_checkpoint_b_env")
        if (testEnvDir.exists()) {
            testEnvDir.deleteRecursively()
        }
        testEnvDir.mkdirs()
        pathResolver = LinuxPathResolver(testEnvDir)
    }

    // B1: LinuxPathResolver path containment and isolation
    @Test
    fun `linux path resolver allows paths inside workspace`() {
        val resolved = pathResolver.resolve("/workspace/code/main.c", requireWritable = true)
        assertTrue(resolved is LinuxPathResolver.ResolvedTarget.Workspace)
        val file = (resolved as LinuxPathResolver.ResolvedTarget.Workspace).file
        assertTrue("Resolved file must be child of workspace", file.canonicalPath.startsWith(pathResolver.workspaceDir.canonicalPath))
    }

    @Test
    fun `linux path resolver rejects traversal attempts escaping workspace`() {
        try {
            pathResolver.resolve("/workspace/../../escaped.txt", requireWritable = true)
            fail("Expected SecurityException on path traversal above virtual root")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("traverses above virtual root") == true)
        }
    }

    @Test
    fun `linux path resolver rejects Android physical host paths`() {
        val dangerousPaths = listOf(
            "/data/data/com.example/databases",
            "/sdcard/DCIM/photos",
            "/storage/emulated/0",
            "/system/bin/sh",
            "/proc/version"
        )
        for (dp in dangerousPaths) {
            try {
                pathResolver.resolve(dp, requireWritable = false)
                fail("Expected SecurityException on physical host path: $dp")
            } catch (e: SecurityException) {
                assertTrue(e.message?.contains("forbidden") == true)
            }
        }
    }

    @Test
    fun `linux path resolver enforces read only on rootfs`() {
        // Can resolve for read
        val resolved = pathResolver.resolve("/etc/os-release", requireWritable = false)
        assertTrue(resolved is LinuxPathResolver.ResolvedTarget.Rootfs)

        // Cannot resolve for write
        try {
            pathResolver.resolve("/etc/os-release", requireWritable = true)
            fail("Expected SecurityException when requesting writable on rootfs path")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("read-only") == true)
        }
    }

    // B2: Secure Tar extractor blocks Zip Slip / Tar Slip traversal attacks
    @Test
    fun `secure tar extractor blocks zip slip path traversal`() {
        val maliciousTarGz = File(testEnvDir, "malicious.tar.gz")
        val destDir = File(testEnvDir, "extract_target")
        destDir.mkdirs()

        createTarGzWithEntry(maliciousTarGz, "../outside.txt", "MALICIOUS PAYLOAD")

        val extractor = SecureArchiveExtractor()
        try {
            extractor.extractTarGz(maliciousTarGz, destDir)
            fail("Expected SecurityException when extracting tar with '../' traversal")
        } catch (e: SecurityException) {
            assertTrue("SecurityException should catch traversal", e.message?.contains("traversal") == true || e.message?.contains("escape") == true)
        }

        val outsideFile = File(testEnvDir, "outside.txt")
        assertFalse("Outside file must never be written", outsideFile.exists())
    }

    // B3: Direct Filesystem R/W Self Test
    @Test
    fun `linux environment manager executes complete write read modify self test`() = runBlocking {
        val manager = DefaultLinuxEnvironmentManager(context)
        val result = manager.runSelfTest(PinnedLinuxEnvironments.ALPINE_3_21_AARCH64.id)

        assertTrue("Write test must succeed", result.writeSuccess)
        assertTrue("Read test must succeed", result.readSuccess)
        assertTrue("Modify test must succeed", result.modifySuccess)
        assertNotNull("Nonce1 must be generated", result.nonce1)
        assertNotNull("Nonce2 must be generated", result.nonce2)
        assertTrue("Details must indicate PASS", result.details.contains("PASS"))
    }

    // B4: Linux tool execution (linux_write_file, linux_read_file, linux_list_directory)
    @Test
    fun `linux tools read write and list in isolated workspace`() = runBlocking {
        val readTool = LinuxReadFileTool { pathResolver }
        val writeTool = LinuxWriteFileTool { pathResolver }
        val listTool = LinuxListDirectoryTool { pathResolver }

        // Write
        val writeRes = writeTool.execute(mapOf("path" to "/workspace/hello.txt", "content" to "Hello Alpine World!"))
        assertTrue("Write must succeed", writeRes.isSuccess)

        // Read
        val readRes = readTool.execute(mapOf("path" to "/workspace/hello.txt"))
        assertTrue("Read must succeed", readRes.isSuccess)
        assertEquals("Hello Alpine World!", readRes.output)

        // List
        val listRes = listTool.execute(mapOf("path" to "/workspace"))
        assertTrue("List must succeed", listRes.isSuccess)
        assertTrue(listRes.output.contains("hello.txt"))

        // Security check: Writing to rootfs fails
        val writeRootfs = writeTool.execute(mapOf("path" to "/bin/sh", "content" to "echo 1"))
        assertFalse("Writing to rootfs must fail", writeRootfs.isSuccess)
    }

    // B5: SAF Shared File Access Registry & Capability checking
    @Test
    fun `saf shared resource registry stores and enforces read only vs read write`() = runBlocking {
        val safRegistry = DefaultAndroidSharedResourceRegistry(context)
        val testResource = AndroidSharedResource(
            resourceId = "test_docs",
            displayName = "My Documents",
            treeUriString = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
            capability = SafCapability.READ
        )

        safRegistry.register(testResource)
        val loaded = safRegistry.getResource("test_docs")
        assertNotNull(loaded)
        assertEquals(SafCapability.READ, loaded?.capability)

        // Tool enforcing READ-ONLY
        val writeTool = AndroidWriteFileTool(context, safRegistry)
        val writeRes = writeTool.execute(mapOf(
            "resourceId" to "test_docs",
            "relativePath" to "test.txt",
            "content" to "Data"
        ))

        assertFalse("Write must fail on READ-ONLY resource", writeRes.isSuccess)
        assertTrue("Error must specify read-only capability", writeRes.output.contains("READ-ONLY"))
    }

    @Test
    fun `android saf tools reject relative path traversal and physical paths`() = runBlocking {
        val safRegistry = DefaultAndroidSharedResourceRegistry(context)
        val readTool = AndroidReadFileTool(context, safRegistry)

        val res1 = readTool.execute(mapOf("resourceId" to "docs", "relativePath" to "../../secret.txt"))
        assertFalse("Must reject relative path traversal", res1.isSuccess)
        assertEquals("PATH_TRAVERSAL", res1.error)

        val res2 = readTool.execute(mapOf("resourceId" to "docs", "relativePath" to "/sdcard/photos.jpg"))
        assertFalse("Must reject physical host paths", res2.isSuccess)
        assertEquals("PATH_TRAVERSAL", res2.error)
    }

    private fun createTarGzWithEntry(targetFile: File, entryName: String, content: String) {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            // Tar header 512 bytes
            val header = ByteArray(512)
            // Name at offset 0 (max 100 bytes)
            val nameBytes = entryName.toByteArray(Charsets.UTF_8)
            System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
            // Size in octal at offset 124 (12 bytes)
            val sizeOctal = "%011o ".format(content.length).toByteArray(Charsets.UTF_8)
            System.arraycopy(sizeOctal, 0, header, 124, sizeOctal.size)
            // Typeflag '0' for regular file at offset 156
            header[156] = '0'.code.toByte()

            gzos.write(header)
            gzos.write(content.toByteArray(Charsets.UTF_8))
            val pad = (512 - (content.length % 512)) % 512
            if (pad > 0) {
                gzos.write(ByteArray(pad))
            }
            // 1024 zero bytes marking end of archive
            gzos.write(ByteArray(1024))
        }
        FileOutputStream(targetFile).use { it.write(baos.toByteArray()) }
    }
}
