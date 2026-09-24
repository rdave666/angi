package com.example.angi.runtime.proot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.angi.ui.diagnostics.DiagnosticsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinuxInstallProgressTest {

    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var paths: LinuxPaths

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        testDir = File(context.filesDir, "test_install_progress")
        if (testDir.exists()) {
            testDir.deleteRecursively()
        }
        testDir.mkdirs()
        paths = LinuxPaths(context, dirName = "test_install_progress")
        // Fake proot binary so existence check passes
        val prootBin = File(paths.prootPath)
        prootBin.parentFile?.mkdirs()
        prootBin.writeText("#!/bin/sh\n")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 1. Install-step ordering
    @Test
    fun `install steps are emitted in exact required order`() = runBlocking {
        val stepsEmitted = mutableListOf<InstallStep>()
        val installer = LinuxInstaller(
            paths = paths,
            urlProvider = { listOf("https://example.com/rootfs.tar.xz") },
            fileDownloader = { _, target, onProgress ->
                target.writeBytes(ByteArray(100))
                onProgress(0.5f, 50L, 100L)
                onProgress(1.0f, 100L, 100L)
                Pair(100L, "fake_sha256")
            },
            archiveExtractor = { _, targetDir -> targetDir.mkdirs() },
            commandExecutor = { _, command, _ ->
                if (command.contains("ANGI_PROOT_OK")) {
                    ProotResult(success = true, stdout = "ANGI_PROOT_OK\n", exitCode = 0)
                } else {
                    ProotResult(success = true, stdout = "Success", exitCode = 0)
                }
            }
        )

        val result = installer.installDebian { step ->
            stepsEmitted.add(step)
        }

        assertTrue("installDebian should succeed: ${result.exceptionOrNull()}", result.isSuccess)

        val stepClasses = stepsEmitted.map { it::class.java.simpleName }
        assertEquals("ResolveImage", stepClasses.first())

        // Check relative ordering
        val resolveIdx = stepClasses.indexOf("ResolveImage")
        val downloadIdx = stepClasses.indexOf("Download")
        val extractIdx = stepClasses.indexOf("Extract")
        val configureIdx = stepClasses.indexOf("Configure")
        val prootTestIdx = stepClasses.indexOf("ProotTest")
        val aptUpdateIdx = stepClasses.indexOf("AptUpdate")
        val packagesIdx = stepClasses.indexOf("Packages")
        val finalizeIdx = stepClasses.indexOf("Finalize")
        val completeIdx = stepClasses.indexOf("Complete")

        assertTrue("ResolveImage < Download", resolveIdx in 0..<downloadIdx)
        assertTrue("Download < Extract", downloadIdx in 0..<extractIdx)
        assertTrue("Extract < Configure", extractIdx in 0..<configureIdx)
        assertTrue("Configure < ProotTest", configureIdx in 0..<prootTestIdx)
        assertTrue("ProotTest < AptUpdate", prootTestIdx in 0..<aptUpdateIdx)
        assertTrue("AptUpdate < Packages", aptUpdateIdx in 0..<packagesIdx)
        assertTrue("Packages < Finalize", packagesIdx in 0..<finalizeIdx)
        assertTrue("Finalize < Complete", finalizeIdx in 0..<completeIdx)
    }

    // 1b. ProotTest failure immediately aborts install before AptUpdate
    @Test
    fun `proot test failure aborts install immediately before apt update`() = runBlocking {
        val stepsEmitted = mutableListOf<InstallStep>()
        var aptUpdateCalled = false

        val installer = LinuxInstaller(
            paths = paths,
            urlProvider = { listOf("https://example.com/rootfs.tar.xz") },
            fileDownloader = { _, target, _ ->
                target.writeBytes(ByteArray(100))
                Pair(100L, "fake_sha256")
            },
            archiveExtractor = { _, targetDir -> targetDir.mkdirs() },
            commandExecutor = { _, command, _ ->
                if (command.contains("ANGI_PROOT_OK")) {
                    ProotResult(success = false, stderr = "PRoot execution failed", exitCode = 1)
                } else if (command.contains("apt-get update")) {
                    aptUpdateCalled = true
                    ProotResult(success = true)
                } else {
                    ProotResult(success = true)
                }
            }
        )

        val result = installer.installDebian { step ->
            stepsEmitted.add(step)
        }

        assertTrue("installDebian must fail when PRoot smoke test fails", result.isFailure)
        assertFalse("apt-get update must NEVER run if ProotTest fails", aptUpdateCalled)
        assertTrue("Last emitted step should be ProotTest", stepsEmitted.last() is InstallStep.ProotTest)
    }

    // 2. Download progress mapping
    @Test
    fun `download progress mapping produces exact specified messages`() {
        assertEquals(
            "Resolving Debian ARM64 image…",
            DiagnosticsViewModel.mapInstallStepToMessage(InstallStep.ResolveImage)
        )
        assertEquals(
            "Downloading rootfs — 42%",
            DiagnosticsViewModel.mapInstallStepToMessage(InstallStep.Download(fraction = 0.42f, downloadedBytes = 42L, totalBytes = 100L))
        )
        assertEquals(
            "Extracting Debian filesystem…",
            DiagnosticsViewModel.mapInstallStepToMessage(InstallStep.Extract)
        )
        assertEquals(
            "Configuring DNS / dpkg…",
            DiagnosticsViewModel.mapInstallStepToMessage(InstallStep.Configure)
        )
        assertEquals(
            "Starting PRoot…",
            DiagnosticsViewModel.mapInstallStepToMessage(InstallStep.ProotTest)
        )
        assertEquals(
            "Updating apt package index…",
            DiagnosticsViewModel.mapInstallStepToMessage(InstallStep.AptUpdate)
        )
        assertEquals(
            "Installing base packages…",
            DiagnosticsViewModel.mapInstallStepToMessage(InstallStep.Packages(listOf("git", "curl")))
        )
        assertEquals(
            "Finalizing installation…",
            DiagnosticsViewModel.mapInstallStepToMessage(InstallStep.Finalize)
        )
        assertEquals(
            "Debian ready.",
            DiagnosticsViewModel.mapInstallStepToMessage(InstallStep.Complete)
        )
    }

    // 3. Failure message propagation and 4. Button/state behavior
    @Test
    fun `diagnostics view model propagates failure message and maintains proper states`() = runBlocking {
        val failingInstaller = LinuxInstaller(
            paths = paths,
            urlProvider = { throw IllegalStateException("Network unreachable for Debian image") }
        )
        val sandboxManager = LinuxSandboxManager(
            context = context,
            paths = paths,
            installer = failingInstaller
        )

        val viewModel = DiagnosticsViewModel(
            linuxSandboxManager = sandboxManager
        )

        assertFalse("Initial state should not be running", viewModel.uiState.value.installRunning)

        // Trigger install and wait for completion
        viewModel.installDebian().join()

        val finalState = viewModel.uiState.value
        assertFalse("installRunning must be false after failure", finalState.installRunning)
        assertEquals("Failed", finalState.currentInstallStage)
        assertNotNull(finalState.currentInstallMessage)
        assertTrue(
            "Failure message must propagate actual error",
            finalState.currentInstallMessage!!.contains("Installation failed: Network unreachable")
        )
        assertNull("downloadProgress must be null after failure", finalState.downloadProgress)
    }

    @Test
    fun `successful install updates UI state to complete and debian ready`() = runBlocking {
        val successfulInstaller = LinuxInstaller(
            paths = paths,
            urlProvider = { listOf("https://example.com/rootfs.tar.xz") },
            fileDownloader = { _, target, onProgress ->
                target.writeBytes(ByteArray(50))
                onProgress(1.0f, 50L, 50L)
                Pair(50L, "mock_sha")
            },
            archiveExtractor = { _, targetDir -> targetDir.mkdirs() },
            commandExecutor = { _, command, _ ->
                if (command.contains("ANGI_PROOT_OK")) {
                    ProotResult(success = true, stdout = "ANGI_PROOT_OK\n")
                } else {
                    ProotResult(success = true, stdout = "OK")
                }
            }
        )
        val sandboxManager = LinuxSandboxManager(
            context = context,
            paths = paths,
            installer = successfulInstaller
        )

        val viewModel = DiagnosticsViewModel(
            linuxSandboxManager = sandboxManager
        )

        viewModel.installDebian().join()

        val finalState = viewModel.uiState.value
        assertFalse("installRunning must be false on complete", finalState.installRunning)
        assertEquals("Complete", finalState.currentInstallStage)
        assertEquals("Debian ready.", finalState.currentInstallMessage)
        assertNull(finalState.downloadProgress)
    }
}
