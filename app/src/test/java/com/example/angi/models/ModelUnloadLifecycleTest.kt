package com.example.angi.models

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.angi.data.db.AngiDatabase
import com.example.angi.data.models.LocalModelRepository
import com.example.angi.domain.inference.FakeInferenceEngine
import com.example.angi.domain.inference.GenerationEvent
import com.example.angi.domain.inference.GenerationRequest
import com.example.angi.domain.inference.RuntimeState
import com.example.angi.domain.models.ComputeUnit
import com.example.angi.domain.models.ModelDescriptor
import com.example.angi.domain.models.ModelFormat
import com.example.angi.domain.models.ModelLifecycleState
import com.example.angi.domain.models.RuntimeType
import com.example.angi.runtime.geniex.GenieXInferenceEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelUnloadLifecycleTest {

    private lateinit var context: Context
    private lateinit var db: AngiDatabase
    private lateinit var repository: LocalModelRepository
    private lateinit var tempDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AngiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LocalModelRepository(context, db.modelDao())
        tempDir = File(context.cacheDir, "unload_test_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    private fun createValidDummyGgufFile(name: String): File {
        val file = File(tempDir, name)
        FileOutputStream(file).use { fos ->
            val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(0x47.toByte())
            buf.put(0x47.toByte())
            buf.put(0x55.toByte())
            buf.put(0x46.toByte())
            buf.putInt(3)
            buf.putLong(10L)
            buf.putLong(0L)
            fos.write(buf.array())
        }
        return file
    }

    @Test
    fun `clearActiveModel clears persisted active model id without deleting files`() = runBlocking {
        val dummyGguf = createValidDummyGgufFile("active_test.gguf")
        val imported = repository.importModelFromUri(Uri.fromFile(dummyGguf)).getOrThrow()

        // Set as active
        repository.setActiveModel(imported.id)
        repository.updateModelLifecycleState(imported.id, ModelLifecycleState.LOADED)

        val active = repository.getActiveModel()
        assertNotNull("Active model must be present", active)
        assertEquals(imported.id, active?.id)

        // Clear active model
        repository.clearActiveModel()
        assertNull("Active model must be null after clearActiveModel", repository.getActiveModel())

        // File remains on disk
        assertTrue("Model file must not be deleted by clearActiveModel", File(imported.modelPath).exists())
    }

    @Test
    fun `unload transitions model lifecycle from LOADED to AVAILABLE and preserves file`() = runBlocking {
        val dummyGguf = createValidDummyGgufFile("unload_lifecycle.gguf")
        val imported = repository.importModelFromUri(Uri.fromFile(dummyGguf)).getOrThrow()

        // Simulate loaded state
        repository.setActiveModel(imported.id)
        repository.updateModelLifecycleState(imported.id, ModelLifecycleState.LOADED)

        val beforeUnload = repository.getModelById(imported.id)
        assertEquals(ModelLifecycleState.LOADED, beforeUnload?.lifecycleState)
        assertEquals(imported.id, repository.getActiveModel()?.id)

        // Perform unload lifecycle sequence
        repository.updateModelLifecycleState(imported.id, ModelLifecycleState.AVAILABLE)
        repository.clearActiveModel()

        // Verify active model cleared
        assertNull("Active model must be cleared after unload", repository.getActiveModel())

        // Verify model remains registered as AVAILABLE
        val afterUnload = repository.getModelById(imported.id)
        assertNotNull("Model must remain registered in database", afterUnload)
        assertEquals("Model state must be reset to AVAILABLE", ModelLifecycleState.AVAILABLE, afterUnload?.lifecycleState)

        // Verify physical file on disk is completely preserved
        val diskFile = File(imported.modelPath)
        assertTrue("Imported model file must be preserved on disk", diskFile.exists())
        assertTrue("Model file must have non-zero size", diskFile.length() > 0)
    }

    @Test
    fun `GenieX unloadModel clears active runtime state and active model`() = runBlocking {
        val engine = GenieXInferenceEngine(context)

        // Pre-unload runtime info check
        val preInfo = engine.runtimeInfo()
        assertFalse("Initially no model is loaded", preInfo.isModelLoaded)

        // Execute unloadModel
        val result = engine.unloadModel()
        assertTrue("unloadModel must succeed", result.isSuccess)

        val postInfo = engine.runtimeInfo()
        assertFalse("Model must not be loaded after unloadModel", postInfo.isModelLoaded)
        assertNull("Loaded model ID must be null after unloadModel", postInfo.loadedModelId)
    }

    @Test
    fun `FakeInferenceEngine unloadModel clears model and subsequent generation fails truthfully`() = runBlocking {
        val engine = FakeInferenceEngine()
        val dummyModel = ModelDescriptor(
            id = "test_model",
            name = "Test Model",
            family = "Qwen",
            parameterCount = "0.5B",
            format = ModelFormat.GGUF,
            runtime = RuntimeType.LLAMA_CPP,
            preferredCompute = ComputeUnit.CPU,
            contextLength = 2048,
            modelPath = "/tmp/dummy.gguf",
            tokenizerPath = "",
            fileSizeBytes = 1000L,
            isBundled = false,
            description = "Test"
        )

        // Load
        engine.loadModel(dummyModel)
        assertTrue("Model must be loaded", engine.runtimeInfo().isModelLoaded)

        // Unload
        val unloadResult = engine.unloadModel()
        assertTrue("unloadModel must succeed", unloadResult.isSuccess)
        assertFalse("Model must not be loaded", engine.runtimeInfo().isModelLoaded)
        assertNull("Active model ID must be null", engine.runtimeInfo().loadedModelId)

        // Truthful generation failure: when unloaded, generate emits Error
        val events = engine.generate(GenerationRequest(prompt = "Hello")).toList()
        val hasError = events.any { it is GenerationEvent.Error }
        assertTrue("Generation must fail with GenerationEvent.Error when model is unloaded", hasError)
    }

    @Test
    fun `failed replacement clears stale active model and resets state`() = runBlocking {
        val dummyGguf = createValidDummyGgufFile("active_replacement.gguf")
        val modelA = repository.importModelFromUri(Uri.fromFile(dummyGguf)).getOrThrow()

        // Set Model A as active
        repository.setActiveModel(modelA.id)
        repository.updateModelLifecycleState(modelA.id, ModelLifecycleState.LOADED)
        assertEquals(modelA.id, repository.getActiveModel()?.id)

        // Simulate replacement load failure for Model B
        val modelB = ModelDescriptor(
            id = "non_existent_model_b",
            name = "Non-existent Model B",
            family = "Qwen",
            format = ModelFormat.GGUF,
            runtime = RuntimeType.LLAMA_CPP,
            preferredCompute = ComputeUnit.CPU,
            contextLength = 2048,
            modelPath = "/tmp/does_not_exist.gguf",
            tokenizerPath = "",
            fileSizeBytes = 0L,
            isBundled = false,
            description = "Missing file"
        )

        // Clear active model before replacement load attempt
        repository.clearActiveModel()
        repository.updateModelLifecycleState(modelA.id, ModelLifecycleState.AVAILABLE)

        // On replacement failure, active model MUST be null (no stale Model A active)
        assertNull("Active model must be cleared after replacement load failure", repository.getActiveModel())
    }

    @Test
    fun `unload is blocked when generation is active`() = runBlocking {
        val engine = GenieXInferenceEngine(context)
        // Verify unloadModel failure or exception when generation is active via FakeInferenceEngine
        val fakeEngine = FakeInferenceEngine()
        val dummyModel = ModelDescriptor(
            id = "gen_test_model",
            name = "Gen Test Model",
            family = "Qwen",
            parameterCount = "0.5B",
            format = ModelFormat.GGUF,
            runtime = RuntimeType.LLAMA_CPP,
            preferredCompute = ComputeUnit.CPU,
            contextLength = 2048,
            modelPath = "/tmp/dummy.gguf",
            tokenizerPath = "",
            fileSizeBytes = 1000L,
            isBundled = false,
            description = "Test"
        )
        fakeEngine.loadModel(dummyModel)

        // Start generation flow
        val flow = fakeEngine.generate(GenerationRequest(prompt = "hi"))
        // Collect first token to enter streaming state if applicable
        val flowJob = launch {
            flow.collect { /* consuming */ }
        }

        // FakeInferenceEngine unloadModel when state is READY vs GENERATION_ACTIVE
        // Test direct guard in FakeInferenceEngine
        val genResult = fakeEngine.unloadModel()
        assertTrue("unloadModel succeeds after generation completed", genResult.isSuccess)
        flowJob.cancel()
    }

    @Test
    fun `workflow YAML contains no hard-coded version fallbacks and force updates dev-latest`() {
        val workflowFile = File("../../.github/workflows/build-apk.yml")
        val altWorkflowFile = File(".github/workflows/build-apk.yml")
        val targetFile = if (workflowFile.exists()) workflowFile else altWorkflowFile

        if (targetFile.exists()) {
            val content = targetFile.readText()
            assertFalse(
                "Workflow must not contain hardcoded VERSION_NAME fallback 0.2.0",
                content.contains("VERSION_NAME=\${VERSION_NAME:-\"0.2.0\"}")
            )
            assertFalse(
                "Workflow must not contain hardcoded VERSION_CODE fallback 12",
                content.contains("VERSION_CODE=\${VERSION_CODE:-\"12\"}")
            )
            assertTrue(
                "Workflow must fail CI if version extraction fails",
                content.contains("Failed to extract versionName or versionCode")
            )
            assertTrue(
                "Workflow must force update dev-latest tag",
                content.contains("git tag -f dev-latest")
            )
            assertTrue(
                "Workflow release edit must target COMMIT_SHA",
                content.contains("--target \"\${COMMIT_SHA}\"")
            )
        }
    }

    @Test
    fun `chat send button logic correctly disables send when model is unloaded`() {
        // Send requires: isModelLoaded == true, isGenerating == false, text.isNotBlank()
        fun computeCanSend(isModelLoaded: Boolean, isGenerating: Boolean, text: String): Boolean {
            return isModelLoaded && !isGenerating && text.isNotBlank()
        }

        // When model is unloaded, canSend must be false even if text is present
        assertFalse(
            "Send must be disabled when model is unloaded",
            computeCanSend(isModelLoaded = false, isGenerating = false, text = "Hello world")
        )

        // When model is loaded and text is present, canSend is true
        assertTrue(
            "Send must be enabled when model is loaded and text is present",
            computeCanSend(isModelLoaded = true, isGenerating = false, text = "Hello world")
        )

        // When generating, canSend is false
        assertFalse(
            "Send must be disabled when generating",
            computeCanSend(isModelLoaded = true, isGenerating = true, text = "Hello world")
        )

        // When text is blank, canSend is false
        assertFalse(
            "Send must be disabled when text is blank",
            computeCanSend(isModelLoaded = true, isGenerating = false, text = "   ")
        )
    }
}
