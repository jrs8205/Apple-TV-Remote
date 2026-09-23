package com.jrs8205.appletvremote.remote

import com.jrs8205.appletvremote.data.DeviceRepository
import com.jrs8205.appletvremote.data.IdentityRepository
import com.jrs8205.appletvremote.data.PairedDevice
import com.jrs8205.appletvremote.discovery.DiscoveredDevice
import com.jrs8205.appletvremote.protocol.companion.ClientInfo
import com.jrs8205.appletvremote.protocol.companion.CompanionClient
import com.jrs8205.appletvremote.protocol.companion.CompanionConnection
import com.jrs8205.appletvremote.protocol.companion.CompanionEvent
import com.jrs8205.appletvremote.protocol.companion.ConnectionState
import com.jrs8205.appletvremote.protocol.companion.HidButton
import com.jrs8205.appletvremote.protocol.companion.MediaCapabilities
import com.jrs8205.appletvremote.protocol.companion.MediaCommand
import com.jrs8205.appletvremote.protocol.companion.PairingSession
import com.jrs8205.appletvremote.protocol.companion.SocketConnector
import com.jrs8205.appletvremote.protocol.companion.SystemStatus
import com.jrs8205.appletvremote.protocol.companion.TouchPhase
import com.jrs8205.appletvremote.protocol.companion.TouchPump
import com.jrs8205.appletvremote.protocol.companion.TouchSample
import com.jrs8205.appletvremote.protocol.crypto.Ed25519KeyPair
import com.jrs8205.appletvremote.protocol.crypto.SecureRandomSource
import com.jrs8205.appletvremote.protocol.pairing.ControllerIdentity
import com.jrs8205.appletvremote.protocol.pairing.Credentials
import com.jrs8205.appletvremote.protocol.pairing.PairingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class RemoteState(
    val device: PairedDevice? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val systemStatus: SystemStatus = SystemStatus.UNKNOWN,
    val media: MediaCapabilities = MediaCapabilities(0),
    val lastError: Throwable? = null,
)

/**
 * The app's single owner of the Apple TV session. UI actions become ordered commands on one
 * queue; events from the TV fold into [state].
 */
class RemoteController(
    private val scope: CoroutineScope,
    private val deviceRepository: DeviceRepository,
    private val identityRepository: IdentityRepository,
    private val connector: SocketConnector,
    private val clientName: String,
    private val clientModel: String,
    val log: ConnectionLog,
) {
    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    private val clientMutex = Mutex()
    private var client: CompanionClient? = null
    private var clientDevice: PairedDevice? = null
    private var eventJob: Job? = null
    private var backgroundDisconnect: Job? = null
    private var pairing: PendingPairing? = null

    private val commands = Channel<suspend CompanionClient.() -> Unit>(Channel.UNLIMITED)
    private val touchPump = TouchPump(scope) { sample -> currentClient()?.touch(sample.phase, sample.x, sample.y) }

    private class PendingPairing(val device: DiscoveredDevice, val identity: ControllerIdentity, val connection: CompanionConnection, val session: PairingSession)

    init {
        scope.launch {
            deviceRepository.device.collect { device ->
                _state.update { it.copy(device = device) }
                if (device == null) dropClient()
            }
        }
        scope.launch {
            for (command in commands) {
                val active = currentClient() ?: continue
                try {
                    active.command()
                    _state.update { it.copy(lastError = null) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.log { "command failed: $e" }
                    _state.update { it.copy(lastError = e) }
                }
            }
        }
    }

    fun press(button: HidButton, holdMs: Long = 0) = enqueue { pressButton(button, holdMs) }

    fun media(command: MediaCommand) = enqueue { media(command) }

    fun skip(seconds: Double) = enqueue { skip(seconds) }

    fun togglePower() = enqueue {
        val status = fetchAttentionState()
        val button = if (status == SystemStatus.ASLEEP || status == SystemStatus.UNKNOWN) HidButton.WAKE else HidButton.SLEEP
        pressButton(button)
        _state.update { it.copy(systemStatus = if (button == HidButton.WAKE) SystemStatus.AWAKE else SystemStatus.ASLEEP) }
    }

    fun touch(phase: TouchPhase, x: Int, y: Int) = touchPump.submit(TouchSample(phase, x, y))

    fun connect() = enqueue { ensureConnected() }

    fun onAppForeground() {
        backgroundDisconnect?.cancel()
        backgroundDisconnect = null
        if (_state.value.device != null) connect()
    }

    fun onAppBackground(keepAlive: Boolean) {
        if (keepAlive) return
        backgroundDisconnect?.cancel()
        backgroundDisconnect = scope.launch {
            delay(BACKGROUND_DISCONNECT_MS)
            clientMutex.withLock { client?.disconnect() }
        }
    }

    suspend fun forget() {
        dropClient()
        deviceRepository.forget()
    }

    /** Opens a connection to [device] and asks it to show its PIN. */
    suspend fun startPairing(device: DiscoveredDevice) {
        cancelPairing()
        val identity = ControllerIdentity(
            pairingId = UUID.randomUUID().toString(),
            signingKey = Ed25519KeyPair.generate(SecureRandomSource),
            displayName = clientName,
        )
        val connection = CompanionConnection(connector, device.host, device.port, log)
        connection.open()
        val session = PairingSession(connection, identity, SecureRandomSource)
        try {
            session.start()
        } catch (e: Exception) {
            connection.close()
            throw e
        }
        pairing = PendingPairing(device, identity, connection, session)
    }

    /** Completes pairing with the PIN; on a wrong PIN the TV is asked for a fresh PIN so the user can retry. */
    suspend fun finishPairing(pin: String): PairedDevice {
        val pending = checkNotNull(pairing) { "startPairing must run first" }
        val credentials: Credentials = try {
            pending.session.finish(pin)
        } catch (e: PairingException.WrongPin) {
            pending.connection.close()
            pairing = null
            runCatching { startPairing(pending.device) }
            throw e
        } catch (e: Exception) {
            pending.connection.close()
            pairing = null
            throw e
        }
        pending.connection.close()
        pairing = null
        val device = PairedDevice(pending.device.serviceName, pending.device.host, pending.device.port, credentials)
        dropClient()
        deviceRepository.save(device)
        _state.update { it.copy(device = device) }
        connect()
        return device
    }

    suspend fun cancelPairing() {
        pairing?.connection?.close()
        pairing = null
    }

    private fun enqueue(command: suspend CompanionClient.() -> Unit) {
        commands.trySend(command)
    }

    private suspend fun currentClient(): CompanionClient? = clientMutex.withLock {
        val device = _state.value.device ?: deviceRepository.device.first() ?: return null
        client?.takeIf { device.sameAs(clientDevice) }?.let { return it }
        client?.let { stale -> scope.launch { stale.disconnect() } }
        eventJob?.cancel()
        val created = CompanionClient(
            connector = connector,
            host = device.host,
            port = device.port,
            credentials = device.credentials,
            clientInfo = ClientInfo(name = clientName, model = clientModel, publicId = identityRepository.publicId()),
            log = log,
        )
        client = created
        clientDevice = device
        eventJob = scope.launch {
            launch { created.state.collect { connection -> _state.update { it.copy(connection = connection) } } }
            created.events.collect { event ->
                when (event) {
                    is CompanionEvent.SystemStatusChanged -> _state.update { it.copy(systemStatus = event.status) }
                    is CompanionEvent.MediaCapabilitiesChanged -> _state.update { it.copy(media = event.capabilities) }
                    else -> Unit
                }
            }
        }
        created
    }

    private suspend fun dropClient() {
        clientMutex.withLock {
            val stale = client ?: return
            client = null
            clientDevice = null
            eventJob?.cancel()
            eventJob = null
            stale.disconnect()
            _state.update { it.copy(connection = ConnectionState.Disconnected, systemStatus = SystemStatus.UNKNOWN, media = MediaCapabilities(0)) }
        }
    }

    private companion object {
        const val BACKGROUND_DISCONNECT_MS = 30_000L
    }
}
