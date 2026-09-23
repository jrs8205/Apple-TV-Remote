package com.jrs8205.appletvremote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jrs8205.appletvremote.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class Screen { LOADING, ONBOARDING, PAIRING, REMOTE, SETTINGS, LOGS, HID_PROBE }

/** Chooses the visible screen from stored state plus a small stack of overlays (settings, logs, re-pair). */
class RootViewModel(container: AppContainer) : ViewModel() {

    private val overlays = MutableStateFlow<List<Screen>>(emptyList())

    private val base = combine(container.settingsRepository.settings, container.deviceRepository.device) { settings, device ->
        when {
            !settings.onboardingSeen -> Screen.ONBOARDING
            device == null -> Screen.PAIRING
            else -> Screen.REMOTE
        }
    }

    val screen: StateFlow<Screen> = combine(base, overlays) { base, overlays -> overlays.lastOrNull() ?: base }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Screen.LOADING)

    fun openSettings() = push(Screen.SETTINGS)
    fun openLogs() = push(Screen.LOGS)
    fun openPairing() = push(Screen.PAIRING)
    fun openHidProbe() = push(Screen.HID_PROBE)

    /** Returns false when there was nothing to pop, so the activity can finish. */
    fun back(): Boolean {
        if (overlays.value.isEmpty()) return false
        overlays.update { it.dropLast(1) }
        return true
    }

    fun clearOverlays() = overlays.update { emptyList() }

    private fun push(screen: Screen) = overlays.update { if (it.lastOrNull() == screen) it else it + screen }
}
