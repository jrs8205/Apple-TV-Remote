package com.jrs8205.appletvremote.ui.onboarding

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jrs8205.appletvremote.R
import com.jrs8205.appletvremote.data.SettingsRepository
import kotlinx.coroutines.launch

/** Android 17 gates LAN access behind this runtime permission; older versions grant it implicitly. */
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

fun Context.needsLocalNetworkPermission(): Boolean =
    Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED

@Composable
fun OnboardingScreen(settingsRepository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val finish: () -> Unit = { scope.launch { settingsRepository.setOnboardingSeen() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.onboarding_body), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.onboarding_local_network_notice), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { if (context.needsLocalNetworkPermission()) launcher.launch(LOCAL_NETWORK_PERMISSION) else finish() },
        ) {
            Text(stringResource(R.string.onboarding_start))
        }
    }
}
