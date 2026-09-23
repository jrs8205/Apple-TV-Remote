package com.jrs8205.appletvremote

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
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

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.remoteController.onAppForeground()

            override fun onStop(owner: LifecycleOwner) {
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
