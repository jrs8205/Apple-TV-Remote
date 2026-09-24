package com.jrs8205.appletvremote.ui.pairing

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jrs8205.appletvremote.R
import com.jrs8205.appletvremote.discovery.DiscoveredDevice
import com.jrs8205.appletvremote.ui.onboarding.LOCAL_NETWORK_PERMISSION
import com.jrs8205.appletvremote.ui.onboarding.needsLocalNetworkPermission

@Composable
fun PairingScreen(viewModel: PairingViewModel, onPaired: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionMissing by remember { mutableStateOf(context.needsLocalNetworkPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionMissing = context.needsLocalNetworkPermission()
    }
    // Granting the permission on the system settings page returns here without any launcher callback.
    LifecycleResumeEffect(Unit) {
        permissionMissing = context.needsLocalNetworkPermission()
        onPauseOrDispose { }
    }

    DisposableEffect(permissionMissing) {
        if (!permissionMissing) viewModel.startScanning()
        onDispose { viewModel.stopScanning() }
    }
    LaunchedEffect(state) {
        if (state is PairingUiState.Done) {
            viewModel.acknowledgeDone()
            onPaired()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        when (val current = state) {
            is PairingUiState.Scanning -> when {
                permissionMissing -> PermissionMissing(
                    onGrant = { launcher.launch(LOCAL_NETWORK_PERMISSION) },
                    onOpenSettings = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    },
                )
                else -> DeviceList(current, onSelect = viewModel::select)
            }
            is PairingUiState.Connecting -> Progress(
                title = stringResource(R.string.pairing_connecting),
                message = stringResource(R.string.pairing_connecting_message, current.device.serviceName),
                onCancel = viewModel::cancel,
            )
            is PairingUiState.EnterPin -> PinEntry(current, onSubmit = viewModel::submitPin, onCancel = viewModel::cancel)
            is PairingUiState.Verifying -> Progress(
                title = stringResource(R.string.pairing_verifying),
                message = current.device.serviceName,
                onCancel = null,
            )
            is PairingUiState.Failed -> FailedView(current, onRetry = viewModel::retry, onCancel = viewModel::cancel)
            PairingUiState.Done -> Unit
        }
    }
}

@Composable
private fun DeviceList(state: PairingUiState.Scanning, onSelect: (DiscoveredDevice) -> Unit) {
    Text(stringResource(R.string.pairing_choose_tv), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(16.dp))
    if (state.discoveryFailed) {
        Text(stringResource(R.string.pairing_discovery_failed), color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
    }
    if (state.devices.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Spacer(Modifier.padding(8.dp))
            Text(stringResource(R.string.pairing_searching))
        }
    }
    LazyColumn {
        items(state.devices, key = { it.serviceName }) { device ->
            ListItem(
                headlineContent = { Text(device.serviceName) },
                supportingContent = { Text("${device.model} · ${device.host}") },
                leadingContent = { Icon(Icons.Default.Tv, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                trailingContent = { TextButton(onClick = { onSelect(device) }) { Text(stringResource(R.string.pairing_pair)) } },
            )
        }
    }
}

@Composable
private fun PermissionMissing(onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Text(stringResource(R.string.permission_local_network_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.permission_local_network_body))
    Spacer(Modifier.height(24.dp))
    Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.permission_grant)) }
    TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.permission_open_settings)) }
}

@Composable
private fun Progress(title: String, message: String, onCancel: (() -> Unit)?) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(message, textAlign = TextAlign.Center)
        if (onCancel != null) {
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun PinEntry(state: PairingUiState.EnterPin, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember(state.device, state.error) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(pin) { if (pin.length == 4) onSubmit(pin) }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.pairing_enter_pin), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.pairing_enter_pin_message, state.device.serviceName), textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { value -> pin = value.filter(Char::isDigit).take(4) },
            singleLine = true,
            isError = state.error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center),
            modifier = Modifier.focusRequester(focus),
        )
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun FailedView(state: PairingUiState.Failed, onRetry: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.pairing_failed_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(state.error.messageRes()), textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.pairing_choose_another)) }
    }
}

fun PairingError.messageRes(): Int = when (this) {
    PairingError.WRONG_PIN -> R.string.pairing_error_wrong_pin
    PairingError.BACKOFF -> R.string.pairing_error_backoff
    PairingError.MAX_PEERS -> R.string.pairing_error_max_peers
    PairingError.MAX_TRIES -> R.string.pairing_error_max_tries
    PairingError.UNAVAILABLE -> R.string.pairing_error_unavailable
    PairingError.BUSY -> R.string.pairing_error_busy
    PairingError.CONNECT_FAILED -> R.string.pairing_error_connect
    PairingError.UNKNOWN -> R.string.pairing_error_unknown
}
