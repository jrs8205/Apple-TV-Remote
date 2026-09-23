package com.jrs8205.appletvremote

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.content.Intent
import androidx.core.content.ContextCompat
import com.jrs8205.appletvremote.protocol.companion.ConnectionState
import com.jrs8205.appletvremote.protocol.companion.PlayState
import com.jrs8205.appletvremote.service.media.RemoteMediaService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.jrs8205.appletvremote.data.DeviceRepository
import com.jrs8205.appletvremote.data.IdentityRepository
import com.jrs8205.appletvremote.data.KeystoreSecretCipher
import com.jrs8205.appletvremote.data.SettingsRepository
import com.jrs8205.appletvremote.data.appDataStore
import com.jrs8205.appletvremote.discovery.AndroidSocketConnector
import com.jrs8205.appletvremote.discovery.NetworkTargets
import com.jrs8205.appletvremote.discovery.NsdDiscovery
import com.jrs8205.appletvremote.remote.ConnectionLog
import com.jrs8205.appletvremote.remote.RemoteController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsRepository = SettingsRepository(context.appDataStore)
    val deviceRepository = DeviceRepository(context.appDataStore, KeystoreSecretCipher())
    val identityRepository = IdentityRepository(context.appDataStore)
    val discovery = NsdDiscovery(context)
    val connectionLog = ConnectionLog()
    val remoteController = RemoteController(
        scope = appScope,
        deviceRepository = deviceRepository,
        identityRepository = identityRepository,
        connector = AndroidSocketConnector(context.getSystemService(ConnectivityManager::class.java)),
        networkTargets = NetworkTargets(context.getSystemService(ConnectivityManager::class.java)),
        discovery = discovery,
        clientName = context.getString(R.string.app_name),
        clientModel = Build.MODEL,
        log = connectionLog,
    )
}

class RemoteApp : Application() {

    lateinit var container: AppContainer
        private set

    @Volatile private var foreground = false

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            combine(container.remoteController.state, container.settingsRepository.settings) { state, settings ->
                settings.mediaNotificationEnabled && state.connection == ConnectionState.Ready && state.media.playState != PlayState.INACTIVE
            }.distinctUntilChanged().collect { wanted ->
                // Foreground services may only start while the app is visible; the service stops itself otherwise.
                if (wanted && foreground) ContextCompat.startForegroundService(this@RemoteApp, Intent(this@RemoteApp, RemoteMediaService::class.java))
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true
                container.remoteController.onAppForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground = false
                container.appScope.launch {
                    val keepAlive = container.settingsRepository.settings.first().mediaNotificationEnabled
                    container.remoteController.onAppBackground(keepAlive)
                }
            }
        })
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as RemoteApp).container
