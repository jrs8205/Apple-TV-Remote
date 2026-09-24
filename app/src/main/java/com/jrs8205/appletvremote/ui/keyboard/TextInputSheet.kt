package com.jrs8205.appletvremote.ui.keyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jrs8205.appletvremote.R
import com.jrs8205.appletvremote.protocol.companion.HidButton
import com.jrs8205.appletvremote.protocol.textinput.TextInputState
import kotlinx.coroutines.delay

/**
 * Types into whatever text field the Apple TV has focused. The phone's field is the source of
 * truth: every edit replaces the TV's text after a short pause, so the TV never lags behind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextInputSheet(
    keyboard: TextInputState?,
    onSend: (String) -> Unit,
    onPress: (HidButton) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf(keyboard?.currentText ?: "") }
    var lastSent by remember { mutableStateOf(keyboard?.currentText ?: "") }
    var primed by remember { mutableStateOf(keyboard != null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        onRefresh()
        focus.requestFocus()
    }
    // Opened before the TV answered: adopt its text once, unless the user has already started typing.
    LaunchedEffect(keyboard) {
        if (primed || keyboard == null) return@LaunchedEffect
        primed = true
        if (text.isEmpty()) {
            text = keyboard.currentText ?: ""
            lastSent = text
        }
    }
    LaunchedEffect(text) {
        if (text == lastSent) return@LaunchedEffect
        delay(SEND_DEBOUNCE_MS)
        if (text == lastSent) return@LaunchedEffect
        lastSent = text
        onSend(text)
    }
    // Text still waiting on the debounce must reach the TV before the selection that may close its field.
    val done = {
        if (text != lastSent) {
            lastSent = text
            onSend(text)
        }
        onPress(HidButton.SELECT)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .imePadding(),
        ) {
            Text(stringResource(R.string.keyboard_title), style = MaterialTheme.typography.titleMedium)
            Text(
                keyboard?.prompt?.takeIf { it.isNotBlank() } ?: stringResource(if (keyboard == null) R.string.keyboard_no_field else R.string.keyboard_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { done() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { text = "" }) { Text(stringResource(R.string.keyboard_clear)) }
                TextButton(onClick = done) { Text(stringResource(R.string.keyboard_done)) }
            }
        }
    }
}

private const val SEND_DEBOUNCE_MS = 300L
