package com.jrs8205.appletvremote.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jrs8205.appletvremote.AppContainer
import com.jrs8205.appletvremote.discovery.DiscoveredDevice
import com.jrs8205.appletvremote.protocol.pairing.PairingException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PairingUiState {
    data class Scanning(val devices: List<DiscoveredDevice>, val discoveryFailed: Boolean = false) : PairingUiState
    data class Connecting(val device: DiscoveredDevice) : PairingUiState
    data class EnterPin(val device: DiscoveredDevice, val error: PairingError? = null) : PairingUiState
    data class Verifying(val device: DiscoveredDevice) : PairingUiState
    data class Failed(val device: DiscoveredDevice, val error: PairingError) : PairingUiState
    data object Done : PairingUiState
}

enum class PairingError { WRONG_PIN, BACKOFF, MAX_PEERS, MAX_TRIES, UNAVAILABLE, BUSY, CONNECT_FAILED, UNKNOWN }

class PairingViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Scanning(emptyList()))
    val state: StateFlow<PairingUiState> = _state.asStateFlow()
    private var scanJob: Job? = null

    fun startScanning() {
        if (scanJob?.isActive == true) return
        _state.value = PairingUiState.Scanning(emptyList())
        scanJob = viewModelScope.launch {
            container.discovery.devices()
                .catch { _state.update { current -> if (current is PairingUiState.Scanning) current.copy(discoveryFailed = true) else current } }
                .collect { devices -> _state.update { current -> if (current is PairingUiState.Scanning) PairingUiState.Scanning(devices) else current } }
        }
    }

    fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
    }

    fun select(device: DiscoveredDevice) {
        stopScanning()
        _state.value = PairingUiState.Connecting(device)
        viewModelScope.launch {
            try {
                container.remoteController.startPairing(device)
                _state.value = PairingUiState.EnterPin(device)
            } catch (e: Exception) {
                container.connectionLog.log { "pairing start failed: $e" }
                _state.value = PairingUiState.Failed(device, e.toPairingError())
            }
        }
    }

    fun submitPin(pin: String) {
        val current = _state.value as? PairingUiState.EnterPin ?: return
        _state.value = PairingUiState.Verifying(current.device)
        viewModelScope.launch {
            try {
                container.remoteController.finishPairing(pin)
                _state.value = PairingUiState.Done
            } catch (e: PairingException.WrongPin) {
                _state.value = PairingUiState.EnterPin(current.device, PairingError.WRONG_PIN)
            } catch (e: Exception) {
                container.connectionLog.log { "pairing failed: $e" }
                _state.value = PairingUiState.Failed(current.device, e.toPairingError())
            }
        }
    }

    fun retry() {
        val device = when (val current = _state.value) {
            is PairingUiState.Failed -> current.device
            is PairingUiState.EnterPin -> current.device
            else -> return
        }
        select(device)
    }

    fun cancel() {
        viewModelScope.launch { container.remoteController.cancelPairing() }
        startScanning()
    }

    override fun onCleared() {
        viewModelScope.launch { container.remoteController.cancelPairing() }
    }

    private fun Throwable.toPairingError(): PairingError = when (this) {
        is PairingException.WrongPin -> PairingError.WRONG_PIN
        is PairingException.Backoff -> PairingError.BACKOFF
        is PairingException.MaxPeers -> PairingError.MAX_PEERS
        is PairingException.MaxTries -> PairingError.MAX_TRIES
        is PairingException.Unavailable -> PairingError.UNAVAILABLE
        is PairingException.Busy -> PairingError.BUSY
        is PairingException -> PairingError.UNKNOWN
        else -> PairingError.CONNECT_FAILED
    }
}
