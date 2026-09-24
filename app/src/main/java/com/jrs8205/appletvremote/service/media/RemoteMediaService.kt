package com.jrs8205.appletvremote.service.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.jrs8205.appletvremote.MainActivity
import com.jrs8205.appletvremote.R
import com.jrs8205.appletvremote.appContainer
import com.jrs8205.appletvremote.protocol.companion.ConnectionState
import com.jrs8205.appletvremote.protocol.companion.PlayState
import com.jrs8205.appletvremote.remote.RemoteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shows the Apple TV's playback in the notification shade and on the lock screen. The session's
 * player is [CompanionPlayer]; skip buttons come and go with what the TV allows.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class RemoteMediaService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null
    private var player: CompanionPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val container = appContainer
        val companionPlayer = CompanionPlayer(container.remoteController, ::statusText, mainLooper)
        player = companionPlayer
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The service is started with a plain intent, so nothing else would register the session with it.
        session = MediaSession.Builder(this, companionPlayer)
            .setSessionActivity(launch)
            .setCallback(SkipCallback())
            .build()
            .also(::addSession)
        scope.launch {
            combine(container.remoteController.state, container.settingsRepository.settings) { state, settings -> state to settings }
                .collect { (state, settings) ->
                    companionPlayer.update(state)
                    session?.setMediaButtonPreferences(skipButtons(state, settings.skipBackwardSeconds, settings.skipForwardSeconds))
                    // Stays alive while the TV stays connected, so playback that starts while the app is in the
                    // background gets its notification back; without media the player empties its playlist and
                    // Media3 withdraws the notification on its own.
                    if (!settings.mediaNotificationEnabled || state.connection != ConnectionState.Ready) stopSelf()
                }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun statusText(state: RemoteState): String = getString(
        when (state.media.playState) {
            PlayState.PLAYING -> R.string.media_playing
            PlayState.PAUSED -> R.string.media_paused
            else -> R.string.media_idle
        },
    )

    private fun skipButtons(state: RemoteState, backSeconds: Int, forwardSeconds: Int): ImmutableList<CommandButton> {
        val buttons = ArrayList<CommandButton>()
        if (state.media.canSkipBackward) {
            buttons += CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
                .setDisplayName(getString(R.string.cd_skip_backward, backSeconds))
                .setSessionCommand(SessionCommand(COMMAND_SKIP_BACK, Bundle.EMPTY))
                .build()
        }
        if (state.media.canSkipForward) {
            buttons += CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
                .setDisplayName(getString(R.string.cd_skip_forward, forwardSeconds))
                .setSessionCommand(SessionCommand(COMMAND_SKIP_FORWARD, Bundle.EMPTY))
                .build()
        }
        return ImmutableList.copyOf(buttons)
    }

    private inner class SkipCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_SKIP_BACK, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SKIP_FORWARD, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(sessionCommands).build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val container = appContainer
            scope.launch {
                val settings = container.settingsRepository.settings.first()
                when (customCommand.customAction) {
                    COMMAND_SKIP_BACK -> container.remoteController.skip(-settings.skipBackwardSeconds.toDouble())
                    COMMAND_SKIP_FORWARD -> container.remoteController.skip(settings.skipForwardSeconds.toDouble())
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private companion object {
        const val COMMAND_SKIP_BACK = "com.jrs8205.appletvremote.SKIP_BACK"
        const val COMMAND_SKIP_FORWARD = "com.jrs8205.appletvremote.SKIP_FORWARD"
    }
}
