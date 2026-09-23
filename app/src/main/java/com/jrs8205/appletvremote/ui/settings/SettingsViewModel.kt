package com.jrs8205.appletvremote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jrs8205.appletvremote.AppContainer
import com.jrs8205.appletvremote.data.LgTvSettings
import com.jrs8205.appletvremote.data.NavigationMode
import com.jrs8205.appletvremote.data.PairedDevice
import com.jrs8205.appletvremote.data.Settings
import com.jrs8205.appletvremote.discovery.WakeOnLan
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun setMacAddress(text: String) = viewModelScope.launch {
        container.deviceRepository.setMacAddress(if (text.isBlank()) null else WakeOnLan.normalizeMac(text))
    }
    fun wake(): Boolean = container.remoteController.wake()

    val lgTv: StateFlow<LgTvSettings> = container.lgTvRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LgTvSettings())
    val lgStatus = MutableStateFlow<LgStatus>(LgStatus.Idle)

    fun setLgEnabled(enabled: Boolean) = viewModelScope.launch { container.lgTvRepository.setEnabled(enabled) }
    fun setLgHost(host: String) = viewModelScope.launch { container.lgTvRepository.setHost(host) }
    fun setLgInput(inputId: String) = viewModelScope.launch { container.lgTvRepository.setInputId(inputId) }
    fun setLgMac(text: String) = viewModelScope.launch { container.lgTvRepository.setMacAddress(if (text.isBlank()) null else WakeOnLan.normalizeMac(text)) }

    fun pairLgTv(host: String) = viewModelScope.launch {
        lgStatus.value = LgStatus.Connecting
        val result = container.remoteController.pairLgTv(host) { lgStatus.value = LgStatus.Prompted }
        lgStatus.value = result.fold({ LgStatus.Paired }, { LgStatus.Failed(it.message ?: "error") })
    }

    fun turnOffLgTv() = viewModelScope.launch {
        lgStatus.value = LgStatus.Connecting
        val result = container.remoteController.turnOffLgTv()
        lgStatus.value = result.fold({ LgStatus.Idle }, { LgStatus.Failed(it.message ?: "error") })
    }

    sealed interface LgStatus {
        data object Idle : LgStatus
        data object Connecting : LgStatus
        data object Prompted : LgStatus
        data object Paired : LgStatus
        data class Failed(val message: String) : LgStatus
    }
}
