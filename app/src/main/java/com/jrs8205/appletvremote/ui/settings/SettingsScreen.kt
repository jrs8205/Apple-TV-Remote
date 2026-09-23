package com.jrs8205.appletvremote.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jrs8205.appletvremote.BuildConfig
import com.jrs8205.appletvremote.R
import com.jrs8205.appletvremote.data.NavigationMode
import com.jrs8205.appletvremote.discovery.WakeOnLan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onPairAnother: () -> Unit,
    onOpenHidProbe: () -> Unit,
) {
    val device by viewModel.device.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var confirmForget by remember { mutableStateOf(false) }
    var macText by remember { mutableStateOf("") }
    var wakeSent by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setMediaNotification(granted)
    }
    LaunchedEffect(device?.macAddress) { macText = device?.macAddress ?: "" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle(stringResource(R.string.settings_navigation_mode))
            ModeOption(NavigationMode.TOUCHPAD, settings.navigationMode, R.string.settings_mode_touchpad, R.string.settings_mode_touchpad_hint, viewModel::setNavigationMode)
            ModeOption(NavigationMode.SWIPE, settings.navigationMode, R.string.settings_mode_swipe, R.string.settings_mode_swipe_hint, viewModel::setNavigationMode)
            ModeOption(NavigationMode.DPAD, settings.navigationMode, R.string.settings_mode_dpad, R.string.settings_mode_dpad_hint, viewModel::setNavigationMode)
            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_playback))
            SkipRow(stringResource(R.string.settings_skip_backward), settings.skipBackwardSeconds, viewModel::setSkipBackward)
            SkipRow(stringResource(R.string.settings_skip_forward), settings.skipForwardSeconds, viewModel::setSkipForward)
            SwitchRow(
                title = stringResource(R.string.settings_volume_hardware),
                subtitle = stringResource(R.string.settings_volume_hardware_hint),
                checked = settings.useHardwareVolumeButtons,
                onChange = viewModel::setHardwareVolume,
            )
            SwitchRow(
                title = stringResource(R.string.settings_media_controls),
                subtitle = stringResource(R.string.settings_media_controls_hint),
                checked = settings.mediaNotificationEnabled,
                onChange = { enabled ->
                    val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    if (enabled && !granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.setMediaNotification(enabled)
                },
            )
            SwitchRow(title = stringResource(R.string.settings_haptics), subtitle = null, checked = settings.hapticsEnabled, onChange = viewModel::setHaptics)
            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_connection))
            ListItem(
                headlineContent = { Text(device?.name ?: stringResource(R.string.settings_not_paired)) },
                supportingContent = { device?.let { Text(it.host) } },
            )
            if (device != null) {
                OutlinedTextField(
                    value = macText,
                    onValueChange = { macText = it; if (it.isBlank() || WakeOnLan.isValidMac(it)) viewModel.setMacAddress(it) },
                    label = { Text(stringResource(R.string.settings_mac_label)) },
                    supportingText = { Text(stringResource(R.string.settings_mac_hint)) },
                    isError = macText.isNotBlank() && !WakeOnLan.isValidMac(macText),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_wake_now)) },
                    supportingContent = {
                        Text(
                            when {
                                wakeSent -> stringResource(R.string.settings_wake_sent)
                                device?.macAddress == null -> stringResource(R.string.settings_wake_no_mac)
                                else -> stringResource(R.string.settings_wake_hint)
                            },
                        )
                    },
                    modifier = Modifier.clickable { wakeSent = viewModel.wake() },
                )
            }
            ListItem(headlineContent = { Text(stringResource(R.string.settings_pair_another)) }, modifier = Modifier.clickable(onClick = onPairAnother))
            if (device != null) {
                ListItem(headlineContent = { Text(stringResource(R.string.settings_unpair)) }, modifier = Modifier.clickable { confirmForget = true })
            }
            HorizontalDivider()

            ListItem(headlineContent = { Text(stringResource(R.string.settings_logs)) }, modifier = Modifier.clickable(onClick = onOpenLogs))
            if (BuildConfig.DEBUG) {
                ListItem(headlineContent = { Text(stringResource(R.string.settings_hid_probe)) }, modifier = Modifier.clickable(onClick = onOpenHidProbe))
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.settings_unpair_title, device?.name ?: "")) },
            text = { Text(stringResource(R.string.settings_unpair_message)) },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; viewModel.forgetDevice(); onBack() }) { Text(stringResource(R.string.settings_unpair)) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ModeOption(mode: NavigationMode, selected: NavigationMode, title: Int, hint: Int, onSelect: (NavigationMode) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(hint)) },
        leadingContent = { RadioButton(selected = mode == selected, onClick = { onSelect(mode) }) },
        modifier = Modifier.clickable { onSelect(mode) },
    )
}

@Composable
private fun SkipRow(title: String, selected: Int, onSelect: (Int) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Row {
                listOf(5, 10, 15, 30).forEach { seconds ->
                    FilterChip(
                        selected = seconds == selected,
                        onClick = { onSelect(seconds) },
                        label = { Text(stringResource(R.string.settings_seconds, seconds)) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
