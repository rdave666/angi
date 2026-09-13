package com.example

import android.app.Application
import androidx.room.Room
import com.example.angi.data.db.AngiDatabase
import com.example.angi.data.db.ConversationRepository
import com.example.angi.data.db.RoomConversationRepository
import com.example.angi.data.models.LocalModelRepository
import com.example.angi.data.models.ModelRepository
import com.example.angi.data.settings.SettingsRepository
import com.example.angi.domain.conversation.ConversationService
import com.example.angi.domain.inference.InferenceEngine
import com.example.angi.domain.tools.ToolExecutor
import com.example.angi.domain.tools.ToolRegistry
import com.example.angi.runtime.geniex.GenieXInferenceEngine
import com.example.angi.tools.impl.DeviceInfoTool
import com.example.angi.tools.impl.OpenUrlTool
import com.example.angi.tools.impl.ShareTextTool
import com.example.angi.tools.impl.WebFetchTool
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

        runCatching {
            val devNull = android.system.Os.open("/dev/null", android.system.OsConstants.O_WRONLY, 0)
            android.system.Os.dup2(devNull, 2)
            android.system.Os.close(devNull)
        }

        database = Room.databaseBuilder(
            applicationContext,
            AngiDatabase::class.java,
            "angi_local.db"
        ).fallbackToDestructiveMigration().build()

        conversationRepository = RoomConversationRepository(database.conversationDao())
        modelRepository = LocalModelRepository(applicationContext, database.modelDao())
        settingsRepository = SettingsRepository(applicationContext)

        toolRegistry = ToolRegistry().apply {
            register(ShareTextTool(applicationContext))
            register(DeviceInfoTool(applicationContext))
            register(WebFetchTool())
            register(OpenUrlTool(applicationContext))
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

    companion object {
        lateinit var instance: AngiApp
            private set
    }
}
