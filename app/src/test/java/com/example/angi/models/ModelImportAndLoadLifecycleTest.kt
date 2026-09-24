package com.example.angi.models

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.angi.data.db.AngiDatabase
import com.example.angi.data.models.LocalModelRepository
import com.example.angi.domain.models.ComputeUnit
import com.example.angi.domain.models.ModelDescriptor
import com.example.angi.domain.models.ModelFormat
import com.example.angi.domain.models.ModelLifecycleState
import com.example.angi.domain.models.RuntimeType
import com.example.angi.runtime.geniex.GenieXInferenceEngine
import kotlinx.coroutines.flow.first
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
class ModelImportAndLoadLifecycleTest {

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
        tempDir = File(context.cacheDir, "model_test_${System.currentTimeMillis()}").apply { mkdirs() }
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
            // GGUF magic: 'G', 'G', 'U', 'F'
            buf.put(0x47.toByte())
            buf.put(0x47.toByte())
            buf.put(0x55.toByte())
            buf.put(0x46.toByte())
            buf.putInt(3) // version
            buf.putLong(10L) // tensor_count
            buf.putLong(0L) // kv_count
            fos.write(buf.array())
        }
        return file
    }

    @Test
    fun `import never sets active model and defaults to CPU and AVAILABLE`() = runBlocking {
        val dummyGguf = createValidDummyGgufFile("qwen3_test.gguf")
        val uri = Uri.fromFile(dummyGguf)

        // Pre-condition: No active model initially
        assertNull("Precondition: Active model must be null", repository.getActiveModel())

        // Action: Import model from URI
        val result = repository.importModelFromUri(uri)
        assertTrue("Import must succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val imported = result.getOrThrow()

        // Requirement 1 & 8: Import never sets active model
        assertNull("Importing model must NEVER set active model", repository.getActiveModel())

        // Requirement 1 & 8: Imported valid GGUF state = AVAILABLE
        assertEquals("Imported GGUF state must be AVAILABLE", ModelLifecycleState.AVAILABLE, imported.lifecycleState)

        // Requirement 2 & 8: Imported GGUF defaults to CPU
        assertEquals("Imported GGUF preferred compute must default to CPU", ComputeUnit.CPU, imported.preferredCompute)

        // Verify state persisted in database
        val modelInDb = repository.getModels().first().firstOrNull { it.id == imported.id }
        assertNotNull("Imported model must be found in repository", modelInDb)
        assertEquals(ModelLifecycleState.AVAILABLE, modelInDb?.lifecycleState)
        assertEquals(ComputeUnit.CPU, modelInDb?.preferredCompute)
    }

    @Test
    fun `failed load never sets active model and retains imported file`() = runBlocking {
        val dummyGguf = createValidDummyGgufFile("fail_test.gguf")
        val imported = repository.importModelFromUri(Uri.fromFile(dummyGguf)).getOrThrow()

        // Transition to LOADING then FAILED
        repository.updateModelLifecycleState(imported.id, ModelLifecycleState.LOADING)
        repository.updateModelLifecycleState(imported.id, ModelLifecycleState.FAILED)

        // Active model must remain null
        assertNull("Failed load must NEVER set active model", repository.getActiveModel())

        // State must reflect FAILED
        val modelInDb = repository.getModels().first().first { it.id == imported.id }
        assertEquals(ModelLifecycleState.FAILED, modelInDb.lifecycleState)

        // File must be retained on disk
        assertTrue("Imported model file must be retained on failure", File(imported.modelPath).exists())
    }

    @Test
    fun `successful load sets active model and updates lifecycle state`() = runBlocking {
        val dummyGguf = createValidDummyGgufFile("success_test.gguf")
        val imported = repository.importModelFromUri(Uri.fromFile(dummyGguf)).getOrThrow()

        // Transition to LOADING then LOADED with active model activation
        repository.updateModelLifecycleState(imported.id, ModelLifecycleState.LOADING)
        repository.setActiveModel(imported.id)
        repository.updateModelLifecycleState(imported.id, ModelLifecycleState.LOADED)

        // Active model must now be this model
        val active = repository.getActiveModel()
        assertNotNull("Active model must be set after successful load", active)
        assertEquals("Active model ID must match loaded model", imported.id, active?.id)
        assertEquals(ModelLifecycleState.LOADED, active?.lifecycleState)
    }

    @Test
    fun `user selectable compute updates model preferred compute`() = runBlocking {
        val dummyGguf = createValidDummyGgufFile("compute_switch_test.gguf")
        val imported = repository.importModelFromUri(Uri.fromFile(dummyGguf)).getOrThrow()
        assertEquals(ComputeUnit.CPU, imported.preferredCompute)

        // Switch to GPU
        repository.updateModelCompute(imported.id, ComputeUnit.GPU)
        val afterGpu = repository.getModels().first().first { it.id == imported.id }
        assertEquals(ComputeUnit.GPU, afterGpu.preferredCompute)

        // Switch to NPU
        repository.updateModelCompute(imported.id, ComputeUnit.NPU)
        val afterNpu = repository.getModels().first().first { it.id == imported.id }
        assertEquals(ComputeUnit.NPU, afterNpu.preferredCompute)

        // Switch to HYBRID
        repository.updateModelCompute(imported.id, ComputeUnit.HYBRID)
        val afterHybrid = repository.getModels().first().first { it.id == imported.id }
        assertEquals(ComputeUnit.HYBRID, afterHybrid.preferredCompute)
    }

    @Test
    fun `CPU compute sends nGpuLayers 0 and compute_unit cpu`() {
        val modelCpu = ModelDescriptor(
            id = "test_cpu",
            name = "Test Model CPU",
            family = "Qwen",
            parameterCount = "0.6B",
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

        val createInput = GenieXInferenceEngine.buildLlmCreateInput(modelCpu)
        assertEquals("llama_cpp", createInput.runtime_id)
        assertEquals("cpu", createInput.compute_unit)
        assertEquals("nGpuLayers must be 0 for CPU", 0, createInput.config.nGpuLayers)
        assertEquals(2048, createInput.config.nCtx)
    }

    @Test
    fun `accelerated paths GPU HYBRID NPU send nGpuLayers minus 1`() {
        val baseModel = ModelDescriptor(
            id = "test_accel",
            name = "Test Model Accel",
            family = "Qwen",
            parameterCount = "0.6B",
            format = ModelFormat.GGUF,
            runtime = RuntimeType.LLAMA_CPP,
            preferredCompute = ComputeUnit.GPU,
            contextLength = 4096,
            modelPath = "/tmp/dummy.gguf",
            tokenizerPath = "",
            fileSizeBytes = 1000L,
            isBundled = false,
            description = "Test"
        )

        // GPU
        val gpuInput = GenieXInferenceEngine.buildLlmCreateInput(baseModel.copy(preferredCompute = ComputeUnit.GPU))
        assertEquals("gpu", gpuInput.compute_unit)
        assertEquals("GPU must send nGpuLayers = -1", -1, gpuInput.config.nGpuLayers)

        // HYBRID
        val hybridInput = GenieXInferenceEngine.buildLlmCreateInput(baseModel.copy(preferredCompute = ComputeUnit.HYBRID))
        assertEquals("hybrid", hybridInput.compute_unit)
        assertEquals("HYBRID must send nGpuLayers = -1", -1, hybridInput.config.nGpuLayers)

        // NPU
        val npuInput = GenieXInferenceEngine.buildLlmCreateInput(baseModel.copy(preferredCompute = ComputeUnit.NPU))
        assertEquals("npu", npuInput.compute_unit)
        assertEquals("NPU must send nGpuLayers = -1", -1, npuInput.config.nGpuLayers)
    }

    @Test
    fun `no determineTotalModelLayers or readGgufBlockCount remains in GenieXInferenceEngine`() {
        val engineClass = GenieXInferenceEngine::class.java
        val methodNames = engineClass.declaredMethods.map { it.name }.toSet()

        assertFalse(
            "determineTotalModelLayers must be completely removed from GenieXInferenceEngine",
            methodNames.contains("determineTotalModelLayers")
        )
        assertFalse(
            "readGgufBlockCount must be completely removed from GenieXInferenceEngine",
            methodNames.contains("readGgufBlockCount")
        )
    }
}
