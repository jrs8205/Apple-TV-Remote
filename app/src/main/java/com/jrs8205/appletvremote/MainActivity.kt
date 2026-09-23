package com.jrs8205.appletvremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import com.jrs8205.appletvremote.ui.RootViewModel
import com.jrs8205.appletvremote.ui.Screen
import com.jrs8205.appletvremote.ui.onboarding.OnboardingScreen
import com.jrs8205.appletvremote.ui.pairing.PairingScreen
import com.jrs8205.appletvremote.ui.pairing.PairingViewModel
import com.jrs8205.appletvremote.ui.remote.RemoteScreen
import com.jrs8205.appletvremote.ui.remote.RemoteViewModel
import com.jrs8205.appletvremote.ui.settings.LogScreen
import com.jrs8205.appletvremote.ui.settings.SettingsScreen
import com.jrs8205.appletvremote.ui.settings.SettingsViewModel
import com.jrs8205.appletvremote.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val container = appContainer
            return when (modelClass) {
                RootViewModel::class.java -> RootViewModel(container)
                PairingViewModel::class.java -> PairingViewModel(container)
                RemoteViewModel::class.java -> RemoteViewModel(container)
                SettingsViewModel::class.java -> SettingsViewModel(container)
                else -> throw IllegalArgumentException("unknown view model $modelClass")
            } as T
        }
    }

    private val rootViewModel: RootViewModel by viewModels { factory }
    private val pairingViewModel: PairingViewModel by viewModels { factory }
    private val remoteViewModel: RemoteViewModel by viewModels { factory }
    private val settingsViewModel: SettingsViewModel by viewModels { factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val screen by rootViewModel.screen.collectAsStateWithLifecycle()
                BackHandler(enabled = screen == Screen.SETTINGS || screen == Screen.LOGS) { rootViewModel.back() }
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        Screen.ONBOARDING -> OnboardingScreen(settingsRepository = appContainer.settingsRepository)
                        Screen.PAIRING -> PairingScreen(viewModel = pairingViewModel, onPaired = { rootViewModel.clearOverlays() })
                        Screen.REMOTE -> RemoteScreen(viewModel = remoteViewModel, onOpenSettings = rootViewModel::openSettings)
                        Screen.SETTINGS -> SettingsScreen(
                            viewModel = settingsViewModel,
                            onBack = { rootViewModel.back() },
                            onOpenLogs = rootViewModel::openLogs,
                            onPairAnother = rootViewModel::openPairing,
                        )
                        Screen.LOGS -> LogScreen(log = appContainer.connectionLog, onBack = { rootViewModel.back() })
                    }
                }
            }
        }
    }
}
