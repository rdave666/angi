package com.example

import android.app.Application
import androidx.room.Room
import com.example.angi.data.db.AngiDatabase
import com.example.angi.data.db.ConversationRepository
import com.example.angi.data.db.RoomConversationRepository
import com.example.angi.data.models.LocalModelRepository
import com.example.angi.data.models.ModelRepository
import com.example.angi.data.saf.DefaultAndroidSharedResourceRegistry
import com.example.angi.data.settings.SettingsRepository
import com.example.angi.domain.conversation.ConversationService
import com.example.angi.domain.environment.LinuxEnvironmentManager
import com.example.angi.domain.inference.InferenceEngine
import com.example.angi.domain.saf.AndroidSharedResourceRegistry
import com.example.angi.domain.tools.ToolExecutor
import com.example.angi.domain.tools.ToolRegistry
import com.example.angi.runtime.geniex.GenieXInferenceEngine
import com.example.angi.runtime.proot.LinuxSandboxManager
import com.example.angi.tools.android.AndroidListDirectoryTool
import com.example.angi.tools.android.AndroidReadFileTool
import com.example.angi.tools.android.AndroidWriteFileTool
import com.example.angi.tools.impl.DeviceInfoTool
import com.example.angi.tools.impl.OpenUrlTool
import com.example.angi.tools.impl.ShareTextTool
import com.example.angi.tools.impl.WebFetchTool
import com.example.angi.tools.linux.LinuxExecTool
import com.example.angi.tools.linux.LinuxListDirectoryTool
import com.example.angi.tools.linux.LinuxProcessKillTool
import com.example.angi.tools.linux.LinuxProcessOutputTool
import com.example.angi.tools.linux.LinuxProcessStatusTool
import com.example.angi.tools.linux.LinuxReadFileTool
import com.example.angi.tools.linux.LinuxWriteFileTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AngiApp : Application() {

    lateinit var database: AngiDatabase
        private set

    lateinit var conversationRepository: ConversationRepository
        private set

    lateinit var modelRepository: ModelRepository
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var linuxSandboxManager: LinuxSandboxManager
        private set

    val linuxEnvironmentManager: LinuxEnvironmentManager
        get() = linuxSandboxManager

    lateinit var androidSharedResourceRegistry: AndroidSharedResourceRegistry
        private set

    lateinit var toolRegistry: ToolRegistry
        private set

    lateinit var toolExecutor: ToolExecutor
        private set

    lateinit var inferenceEngine: InferenceEngine
        private set

    lateinit var conversationService: ConversationService
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Requirement A3: Native logging from JNI, llama.cpp, and QAIRT remains observable,
        // while emulator-specific OpenGL ES attribute binding messages are cleanly filtered.
        startNativeLogForwarder()

        database = Room.databaseBuilder(
            applicationContext,
            AngiDatabase::class.java,
            "angi_local.db"
        ).fallbackToDestructiveMigration().build()

        conversationRepository = RoomConversationRepository(database.conversationDao())
        modelRepository = LocalModelRepository(applicationContext, database.modelDao())
        settingsRepository = SettingsRepository(applicationContext)

        // Checkpoint B & Linux Userspace Sandbox: Unified PRoot + Debian ARM64 manager
        linuxSandboxManager = LinuxSandboxManager(applicationContext)
        androidSharedResourceRegistry = DefaultAndroidSharedResourceRegistry(applicationContext)

        toolRegistry = ToolRegistry().apply {
            // General / Hardware tools
            register(ShareTextTool(applicationContext))
            register(DeviceInfoTool(applicationContext))
            register(WebFetchTool())
            register(OpenUrlTool(applicationContext))

            // Real Linux Userspace Execution Tools (PRoot + Debian ARM64)
            register(LinuxExecTool(linuxSandboxManager))
            register(LinuxProcessStatusTool(linuxSandboxManager))
            register(LinuxProcessOutputTool(linuxSandboxManager))
            register(LinuxProcessKillTool(linuxSandboxManager))

            // Linux Filesystem Tools
            val pathResolverProvider = { linuxSandboxManager.getPathResolver() }
            register(LinuxReadFileTool(pathResolverProvider))
            register(LinuxWriteFileTool(pathResolverProvider))
            register(LinuxListDirectoryTool(pathResolverProvider))

            // Checkpoint B Android Shared Storage SAF Tools
            register(AndroidReadFileTool(applicationContext, androidSharedResourceRegistry))
            register(AndroidWriteFileTool(applicationContext, androidSharedResourceRegistry))
            register(AndroidListDirectoryTool(applicationContext, androidSharedResourceRegistry))
        }

        toolExecutor = ToolExecutor(toolRegistry, settingsRepository)
        inferenceEngine = GenieXInferenceEngine(applicationContext)

        conversationService = ConversationService(
            conversationRepository = conversationRepository,
            modelRepository = modelRepository,
            settingsRepository = settingsRepository,
            inferenceEngine = inferenceEngine,
            toolRegistry = toolRegistry,
            toolExecutor = toolExecutor
        )

        CoroutineScope(Dispatchers.IO).launch {
            modelRepository.initializeBundledOrPresetModels()
            val active = modelRepository.getActiveModel()
            if (active != null) {
                inferenceEngine.loadModel(active)
            }
        }
    }

    private fun startNativeLogForwarder() {
        try {
            val pipe = android.system.Os.pipe()
            android.system.Os.dup2(pipe[1], android.system.OsConstants.STDERR_FILENO)
            android.system.Os.close(pipe[1])

            Thread({
                try {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(pipe[0])))
                    while (true) {
                        val line = reader.readLine() ?: break
                        // Filter out benign emulator OpenGL ES / host driver attribute binding chatter
                        if (line.contains("s_glBindAttribLocation") || line.contains("bind attrib")) {
                            continue
                        }
                        // Keep all native runtime, JNI, llama.cpp, and QAIRT diagnostics observable at INFO level
                        android.util.Log.i("NativeStderr", line)
                    }
                } catch (_: Throwable) {}
            }, "angi-native-stderr-forwarder").apply {
                isDaemon = true
                start()
            }
        } catch (e: Throwable) {
            android.util.Log.w("AngiApp", "Could not initialize native stderr forwarder: ${e.message}")
        }
    }

    companion object {
        lateinit var instance: AngiApp
            private set
    }
}
