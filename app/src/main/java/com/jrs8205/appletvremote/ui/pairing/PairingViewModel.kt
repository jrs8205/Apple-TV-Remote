package com.jrs8205.appletvremote.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jrs8205.appletvremote.AppContainer
import com.jrs8205.appletvremote.discovery.DiscoveredDevice
import com.jrs8205.appletvremote.protocol.pairing.PairingException
import com.jrs8205.appletvremote.remote.PairingAttempt
import kotlinx.coroutines.CancellationException
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
    private var pairingJob: Job? = null
    private var attempt: PairingAttempt? = null

    /** Starts the search unless a pairing is under way: a rotation re-enters here and must not throw away the PIN entry. */
    fun startScanning() {
        if (scanJob?.isActive == true) return
        if (_state.value !is PairingUiState.Scanning) return
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
        val previous = abandonPairing()
        _state.value = PairingUiState.Connecting(device)
        pairingJob = viewModelScope.launch {
            previous.join()
            try {
                attempt = container.remoteController.startPairing(device)
                _state.value = PairingUiState.EnterPin(device)
            } catch (e: CancellationException) {
                throw e
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
                // The controller asked the TV for a fresh PIN; that new attempt is now ours to cancel.
                attempt = container.remoteController.currentPairing
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

    /** The screen has moved on to the remote; the next visit starts with a fresh search. */
    fun acknowledgeDone() {
        if (_state.value is PairingUiState.Done) _state.value = PairingUiState.Scanning(emptyList())
    }

    fun cancel() {
        abandonPairing()
        _state.value = PairingUiState.Scanning(emptyList())
        startScanning()
    }

    override fun onCleared() {
        abandonPairing()
    }

    /**
     * Stops an in-flight pairing and closes its connection. The cleanup runs in the application
     * scope because [viewModelScope] is already cancelled by the time [onCleared] runs, and it
     * waits for the pairing job so a late result cannot outlive the cancellation. Only this view
     * model's own attempt is cancelled: a newer one started elsewhere must survive.
     */
    private fun abandonPairing(): Job {
        val job = pairingJob
        pairingJob = null
        job?.cancel()
        return container.appScope.launch {
            job?.join()
            val own = attempt ?: return@launch
            attempt = null
            container.remoteController.cancelPairing(own)
        }
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
