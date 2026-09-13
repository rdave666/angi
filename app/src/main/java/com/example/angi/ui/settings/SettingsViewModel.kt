package com.example.angi.ui.settings

import androidx.lifecycle.ViewModel
import com.example.AngiApp
import com.example.angi.data.settings.AngiSettings
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel : ViewModel() {

    private val settingsRepo = AngiApp.instance.settingsRepository
    val settings: StateFlow<AngiSettings> = settingsRepo.settings

    fun updateSettings(newSettings: AngiSettings) {
        settingsRepo.updateSettings(newSettings)
    }
}
