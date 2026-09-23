package com.jrs8205.appletvremote.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jrs8205.appletvremote.R
import com.jrs8205.appletvremote.protocol.companion.ConnectionState
import com.jrs8205.appletvremote.protocol.companion.HidButton

/** Interim layout used to exercise the connection on a real Apple TV; the Siri Remote layout replaces it. */
@Composable
fun RemoteScreen(viewModel: RemoteViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.device?.credentials?.controller?.pairingId) { if (state.device != null) viewModel.connect() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(state.device?.name ?: stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                Text(connectionLabel(state.connection), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings)) }
        }
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.press(HidButton.MENU) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cd_back)) }
                Button(onClick = { viewModel.press(HidButton.HOME) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cd_home)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.press(HidButton.UP) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cd_up)) }
                Button(onClick = { viewModel.press(HidButton.SELECT) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.remote_select)) }
                Button(onClick = { viewModel.press(HidButton.DOWN) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cd_down)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.press(HidButton.PLAY_PAUSE) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cd_play_pause)) }
                Button(onClick = { viewModel.press(HidButton.VOLUME_DOWN) }, modifier = Modifier.weight(1f)) { Text("−") }
                Button(onClick = { viewModel.press(HidButton.VOLUME_UP) }, modifier = Modifier.weight(1f)) { Text("+") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun connectionLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Disconnected -> stringResource(R.string.state_disconnected)
    ConnectionState.Connecting -> stringResource(R.string.state_connecting)
    ConnectionState.Ready -> stringResource(R.string.state_connected)
    is ConnectionState.Failed -> stringResource(R.string.state_failed)
}
