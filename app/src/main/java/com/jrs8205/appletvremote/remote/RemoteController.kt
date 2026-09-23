package com.jrs8205.appletvremote.remote

import com.jrs8205.appletvremote.data.DeviceRepository
import com.jrs8205.appletvremote.data.IdentityRepository
import com.jrs8205.appletvremote.data.LgTvRepository
import com.jrs8205.appletvremote.data.LgTvSettings
import com.jrs8205.appletvremote.lgtv.LgTvClient
import com.jrs8205.appletvremote.data.PairedDevice
import com.jrs8205.appletvremote.discovery.DiscoveredDevice
import com.jrs8205.appletvremote.discovery.NetworkTargets
import com.jrs8205.appletvremote.discovery.NsdDiscovery
import com.jrs8205.appletvremote.discovery.WakeOnLan
import com.jrs8205.appletvremote.protocol.companion.CompanionException
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
import com.jrs8205.appletvremote.protocol.textinput.TextInputState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.UUID

data class RemoteState(
    val device: PairedDevice? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val systemStatus: SystemStatus = SystemStatus.UNKNOWN,
    val media: MediaCapabilities = MediaCapabilities(0),
    val keyboard: TextInputState? = null,
    val wakingTv: Boolean = false,
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
    private val networkTargets: NetworkTargets,
    private val discovery: NsdDiscovery,
    private val lgTvRepository: LgTvRepository,
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
    @Volatile private var lastWakeAt = 0L
    private var macLearning: Job? = null

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
                    if (e is CompanionException.ConnectionClosed) recover(command)
                }
            }
        }
    }

    /**
     * The Apple TV picks a new port on every boot and may get a new address, so a failed connect
     * first re-resolves it over mDNS. A TV that is not on the network at all gets a wake-up packet.
     */
    private suspend fun recover(command: suspend CompanionClient.() -> Unit) {
        val device = _state.value.device ?: return
        val retry: suspend () -> Unit = {
            val client = currentClient()
            if (client != null) {
                try {
                    client.command()
                    _state.update { it.copy(lastError = null) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.log { "command failed after recovery: $e" }
                }
            }
        }
        if (refreshAddress(device)) {
            retry()
            return
        }
        if (wakeIfPossible(throttleMs = AUTO_WAKE_THROTTLE_MS)) {
            delay(WAKE_RETRY_DELAY_MS)
            if (refreshAddress(device)) log.log { "address refreshed after wake" }
            retry()
        }
    }

    /** Looks the TV up by name for a few seconds; stores and returns true when its address or port changed. */
    private suspend fun refreshAddress(device: PairedDevice): Boolean {
        val found = withTimeoutOrNull(ADDRESS_REFRESH_MS) {
            discovery.devices().mapNotNull { list -> list.firstOrNull { it.serviceName == device.name } }.first()
        } ?: return false
        if (found.host == device.host && found.port == device.port) return false
        log.log { "address changed to ${found.host}:${found.port}" }
        deviceRepository.updateAddress(found.host, found.port)
        withTimeoutOrNull(2000) { _state.first { it.device?.host == found.host && it.device?.port == found.port } }
        return true
    }

    /** Sends Wake-on-LAN packets when the MAC address is known; returns false otherwise. */
    fun wake(): Boolean = wakeIfPossible(throttleMs = 0)

    /**
     * Wakes the chain: first the LG TV over the network (it switches to the Apple TV's HDMI input,
     * which wakes the Apple TV through HDMI-CEC), then Wake-on-LAN to the Apple TV itself, then
     * repeated connect attempts while everything boots.
     */
    fun wakeAndConnect() {
        val lg = lgTvRepository.settings
        enqueue {
            val settings = lg.first()
            if (settings.enabled && settings.host.isNotBlank()) {
                _state.update { it.copy(wakingTv = true) }
                try {
                    wakeThroughLgTv(settings)
                } finally {
                    _state.update { it.copy(wakingTv = false) }
                }
            }
            wake()
            var attempt = 0
            while (true) {
                try {
                    ensureConnected()
                    break
                } catch (e: CompanionException) {
                    if (++attempt >= WAKE_CONNECT_ATTEMPTS) throw e
                    delay(WAKE_RETRY_DELAY_MS)
                    refreshAddress(_state.value.device ?: throw e)
                }
            }
            pressButton(HidButton.WAKE)
            _state.update { it.copy(systemStatus = SystemStatus.AWAKE) }
        }
    }

    /** Turns the LG TV on (Wake-on-LAN, then waits for its socket) and selects the Apple TV's input. */
    private suspend fun wakeThroughLgTv(settings: LgTvSettings) {
        settings.macAddress?.let { mac ->
            withContext(Dispatchers.IO) {
                val targets = networkTargets.broadcastAddresses() + listOfNotNull(runCatching { InetAddress.getByName(settings.host) }.getOrNull())
                repeat(3) {
                    runCatching { WakeOnLan.send(mac, targets) }
                    delay(250)
                }
            }
            log.log { "sent wake-on-lan to the LG TV" }
        }
        val deadline = System.currentTimeMillis() + LG_WAKE_TIMEOUT_MS
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                LgTvClient(settings.host, log).use { client ->
                    client.connect(settings.clientKey, timeoutMs = 15_000)
                    client.switchInput(settings.inputId)
                }
                log.log { "LG TV switched to ${settings.inputId}" }
                delay(LG_CEC_SETTLE_MS)
                return
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e
                delay(LG_RETRY_DELAY_MS)
            }
        }
        log.log { "LG TV did not respond: $lastError" }
    }

    /** Pairs with the LG TV: the TV shows a prompt, the key it returns is stored. Also learns its MAC. */
    suspend fun pairLgTv(host: String, onPrompt: () -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            LgTvClient(host, log).use { client ->
                val key = client.connect(null, onPrompt)
                lgTvRepository.setHost(host)
                lgTvRepository.setClientKey(key)
                client.macAddresses().firstOrNull()?.let { lgTvRepository.setMacAddress(it) }
            }
            Unit
        }
    }

    /** Sends the TV to standby; HDMI-CEC puts the Apple TV to sleep with it. */
    suspend fun turnOffLgTv(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = lgTvRepository.settings.first()
            if (!settings.enabled || settings.host.isBlank()) throw IllegalStateException("LG TV not configured")
            LgTvClient(settings.host, log).use { client ->
                client.connect(settings.clientKey, timeoutMs = 15_000)
                client.turnOff()
            }
        }
    }

    /** While the TV is awake its AirPlay record carries the MAC address; grab it once so wake-up works later. */
    private fun learnMacAddress(device: PairedDevice) {
        if (macLearning?.isActive == true) return
        macLearning = scope.launch {
            val mac = withTimeoutOrNull(MAC_LEARN_TIMEOUT_MS) {
                discovery.devices().mapNotNull { list -> list.firstOrNull { it.serviceName == device.name }?.macAddress }.first()
            }
            if (mac != null) {
                log.log { "learned MAC address for ${device.name}" }
                deviceRepository.setMacAddress(mac)
            }
        }
    }

    private fun wakeIfPossible(throttleMs: Long): Boolean {
        val device = _state.value.device ?: return false
        val mac = device.macAddress ?: return false
        val now = System.currentTimeMillis()
        if (now - lastWakeAt < throttleMs) return true
        lastWakeAt = now
        scope.launch {
            withContext(Dispatchers.IO) {
                val targets = networkTargets.broadcastAddresses() + listOfNotNull(runCatching { InetAddress.getByName(device.host) }.getOrNull())
                repeat(3) {
                    runCatching { WakeOnLan.send(mac, targets) }.onFailure { log.log { "wake-on-lan failed: $it" } }
                    delay(250)
                }
                log.log { "sent wake-on-lan to $mac via ${targets.size} targets" }
            }
        }
        return true
    }

    fun press(button: HidButton, holdMs: Long = 0) = enqueue { pressButton(button, holdMs) }

    fun media(command: MediaCommand) = enqueue { media(command) }

    fun skip(seconds: Double) = enqueue { skip(seconds) }

    fun togglePower() {
        if (_state.value.connection != ConnectionState.Ready) {
            wakeAndConnect()
            return
        }
        enqueue { togglePowerConnected() }
    }

    private suspend fun CompanionClient.togglePowerConnected() {
        // A TV that answers our session is awake unless it told us otherwise, so an unknown state means sleep.
        val status = fetchAttentionState() ?: _state.value.systemStatus
        val button = if (status == SystemStatus.ASLEEP) HidButton.WAKE else HidButton.SLEEP
        pressButton(button)
        _state.update { it.copy(systemStatus = if (button == HidButton.WAKE) SystemStatus.AWAKE else SystemStatus.ASLEEP) }
    }

    fun touch(phase: TouchPhase, x: Int, y: Int) = touchPump.submit(TouchSample(phase, x, y))

    /** Replaces the text in the focused field on the TV. */
    fun sendText(text: String) = enqueue { sendText(text, replace = true) }

    /** Asks the TV whether a text field is focused and updates [RemoteState.keyboard]. */
    fun refreshKeyboard() = enqueue {
        val state = textInputState()
        _state.update { it.copy(keyboard = state) }
    }

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
        val device = PairedDevice(pending.device.serviceName, pending.device.host, pending.device.port, credentials, pending.device.macAddress)
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
            launch {
                created.state.collect { connection ->
                    _state.update { it.copy(connection = connection) }
                    if (connection == ConnectionState.Ready && device.macAddress == null) learnMacAddress(device)
                }
            }
            created.events.collect { event ->
                when (event) {
                    is CompanionEvent.SystemStatusChanged -> _state.update { it.copy(systemStatus = event.status) }
                    is CompanionEvent.MediaCapabilitiesChanged -> _state.update { it.copy(media = event.capabilities) }
                    is CompanionEvent.TextInputStarted -> _state.update { it.copy(keyboard = event.state ?: TextInputState(null, null, null)) }
                    CompanionEvent.TextInputStopped -> _state.update { it.copy(keyboard = null) }
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
            _state.update { it.copy(connection = ConnectionState.Disconnected, systemStatus = SystemStatus.UNKNOWN, media = MediaCapabilities(0), keyboard = null) }
        }
    }

    private companion object {
        const val BACKGROUND_DISCONNECT_MS = 30_000L
        const val AUTO_WAKE_THROTTLE_MS = 30_000L
        const val WAKE_RETRY_DELAY_MS = 4_000L
        const val WAKE_CONNECT_ATTEMPTS = 6
        const val MAC_LEARN_TIMEOUT_MS = 20_000L
        const val ADDRESS_REFRESH_MS = 6_000L
        const val LG_WAKE_TIMEOUT_MS = 45_000L
        const val LG_RETRY_DELAY_MS = 3_000L
        const val LG_CEC_SETTLE_MS = 5_000L
    }
}
