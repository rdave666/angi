package com.example.angi.runtime.proot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinuxPersistenceTest {
    private lateinit var paths: LinuxPaths

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        paths = LinuxPaths(context, "persist-${UUID.randomUUID()}")
        paths.ensureLayout()
        File(paths.prootPath).apply {
            parentFile?.mkdirs()
            if (!exists()) writeText("test binary")
        }
    }

    @After fun tearDown() {
        paths.root.deleteRecursively()
    }

    private fun destructiveInstaller(): LinuxInstaller = LinuxInstaller(
        paths = paths,
        urlProvider = { throw IllegalStateException("Network unavailable") }
    )

    @Test fun reinstallNeverDeletesAnExistingInstallation() = runBlocking {
        paths.rootfsDir.mkdirs()
        val modified = File(paths.rootfsDir, "root/my-custom-config")
        modified.parentFile!!.mkdirs()
        modified.writeText("DO_NOT_LOSE")
        paths.writeMarker(DebianDistroSpec.ID)
        val result = destructiveInstaller().installDebian {}
        assertTrue("A complete installed Debian must not be re-downloaded", result.isSuccess)
        assertEquals("DO_NOT_LOSE", modified.readText())
        assertTrue(paths.isInstalled())
    }

    @Test fun failedInstallNeverDeletesPartialOrRecoverableRootfs() = runBlocking {
        paths.rootfsDir.mkdirs()
        File(paths.rootfsDir, "etc").mkdirs()
        val important = File(paths.rootfsDir, "root/.ssh/id_ed25519")
        important.parentFile!!.mkdirs()
        important.writeText("private-user-data")
        val result = destructiveInstaller().installDebian {}
        assertTrue("Cannot silently replace an unknown preexisting Linux tree", result.isFailure)
        assertEquals("private-user-data", important.readText())
    }

    @Test fun missingMarkerDoesNotHideAnOtherwiseCompletedDebian() {
        paths.rootfsDir.mkdirs()
        fun touch(path: String) {
            File(paths.rootfsDir, path).apply { parentFile!!.mkdirs(); writeText("installed") }
        }
        touch("etc/debian_version")
        touch("usr/bin/bash")
        touch("usr/bin/python3")
        touch("var/lib/dpkg/status")
        assertFalse(paths.markerFile.exists())
        assertTrue("Installed Debian must be recoverable without a marker", paths.isInstalled())
    }

    @Test fun misleadingMarkerCannotTurnAnEmptyRootfsIntoInstalledDebian() {
        paths.rootfsDir.mkdirs()
        paths.markerFile.writeText("STATUS=UNINSTALLED\nDISTRO=debian-bookworm\n")
        assertFalse(paths.isInstalled())
    }

    @Test fun interruptedInstallDoesNotDestroySharedWorkspace() = runBlocking {
        val project = File(paths.workspaceDir, "project/src/main.py")
        project.parentFile!!.mkdirs()
        project.writeText("print('persistent')")
        val installer = LinuxInstaller(
            paths = paths,
            urlProvider = { listOf("https://example.invalid/debian.tar.xz") },
            fileDownloader = { _, archive, _ ->
                archive.writeText("incomplete archive")
                18L to "checksum"
            },
            archiveExtractor = { _, rootfs ->
                rootfs.mkdirs()
                File(rootfs, "etc/installed-tmp").apply {
                    parentFile!!.mkdirs()
                    writeText("partial")
                }
                error("Interrupted extraction")
            }
        )
        assertTrue(installer.installDebian {}.isFailure)
        assertEquals("print('persistent')", project.readText())
        assertFalse("Failed staged installs must not create the active rootfs", paths.rootfsDir.exists())
    }
}
