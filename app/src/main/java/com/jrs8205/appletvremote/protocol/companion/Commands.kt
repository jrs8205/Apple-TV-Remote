package com.jrs8205.appletvremote.protocol.companion

/** Button codes for `_hidC`. Code 18 mutes on the reference implementation; other sources call it page up. */
enum class HidButton(val code: Int) {
    UP(1),
    DOWN(2),
    LEFT(3),
    RIGHT(4),
    MENU(5),
    SELECT(6),
    HOME(7),
    VOLUME_UP(8),
    VOLUME_DOWN(9),
    SIRI(10),
    SCREENSAVER(11),
    SLEEP(12),
    WAKE(13),
    PLAY_PAUSE(14),
    CHANNEL_UP(15),
    CHANNEL_DOWN(16),
    GUIDE(17),
    MUTE(18),
    PAGE_DOWN(19),
}

/** Media control commands for `_mcc`. */
enum class MediaCommand(val code: Int) {
    PLAY(1),
    PAUSE(2),
    NEXT(3),
    PREVIOUS(4),
    SKIP(7),
}

/** Touch phases for `_hidT`. */
enum class TouchPhase(val code: Int) {
    PRESS(1),
    HOLD(3),
    RELEASE(4),
}

data class TouchSample(val phase: TouchPhase, val x: Int, val y: Int)

enum class SystemStatus(val code: Int) {
    UNKNOWN(0),
    ASLEEP(1),
    SCREENSAVER(2),
    AWAKE(3),
    IDLE(4),
    ;

    companion object {
        fun fromCode(code: Int): SystemStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class PlayState { PLAYING, PAUSED, INACTIVE, UNKNOWN }

/** The `_mcF` bitmask carried by `_iMC` events. */
data class MediaCapabilities(val flags: Long) {
    private val play get() = flags and 0x1L != 0L
    private val pause get() = flags and 0x2L != 0L

    val playState: PlayState
        get() = when {
            pause && !play -> PlayState.PLAYING
            play && !pause -> PlayState.PAUSED
            flags and RELEVANT_BITS == 0L -> PlayState.INACTIVE
            else -> PlayState.UNKNOWN
        }
    val canNext: Boolean get() = flags and 0x4L != 0L
    val canPrevious: Boolean get() = flags and 0x8L != 0L
    val canSkipForward: Boolean get() = flags and 0x200L != 0L
    val canSkipBackward: Boolean get() = flags and 0x400L != 0L

    companion object {
        private const val RELEVANT_BITS = 0x1L or 0x2L or 0x4L or 0x8L or 0x10L or 0x20L or 0x200L or 0x400L

        fun fromEvent(content: Map<*, *>): MediaCapabilities = MediaCapabilities(content["_mcF"] as? Long ?: 0L)
    }
}

sealed interface CompanionEvent {
    data class SystemStatusChanged(val status: SystemStatus) : CompanionEvent
    data class MediaCapabilitiesChanged(val capabilities: MediaCapabilities) : CompanionEvent
    data class TextInputStarted(val content: Map<*, *>, val state: com.jrs8205.appletvremote.protocol.textinput.TextInputState?) : CompanionEvent
    data object TextInputStopped : CompanionEvent
    data class Other(val name: String, val content: Map<*, *>) : CompanionEvent
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Ready : ConnectionState
    data class Failed(val reason: Throwable) : ConnectionState
}

/** How this phone introduces itself in `_systemInfo`. */
class ClientInfo(val name: String, val model: String, val publicId: String)
