package com.jrs8205.appletvremote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jrs8205.appletvremote.AppContainer
import com.jrs8205.appletvremote.data.NavigationMode
import com.jrs8205.appletvremote.data.PairedDevice
import com.jrs8205.appletvremote.data.Settings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<Settings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Settings())
    val device: StateFlow<PairedDevice?> = container.deviceRepository.device
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setNavigationMode(mode: NavigationMode) = viewModelScope.launch { container.settingsRepository.setNavigationMode(mode) }
    fun setHardwareVolume(enabled: Boolean) = viewModelScope.launch { container.settingsRepository.setUseHardwareVolumeButtons(enabled) }
    fun setSkipForward(seconds: Int) = viewModelScope.launch { container.settingsRepository.setSkipForwardSeconds(seconds) }
    fun setSkipBackward(seconds: Int) = viewModelScope.launch { container.settingsRepository.setSkipBackwardSeconds(seconds) }
    fun setHaptics(enabled: Boolean) = viewModelScope.launch { container.settingsRepository.setHapticsEnabled(enabled) }
    fun setMediaNotification(enabled: Boolean) = viewModelScope.launch { container.settingsRepository.setMediaNotificationEnabled(enabled) }
    fun forgetDevice() = viewModelScope.launch { container.remoteController.forget() }
}
