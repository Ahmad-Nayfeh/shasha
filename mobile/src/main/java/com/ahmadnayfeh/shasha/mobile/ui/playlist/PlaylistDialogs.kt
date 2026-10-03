package com.ahmadnayfeh.shasha.mobile.ui.playlist

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.data.UserPlaylistRepository

/**
 * Asks for the name of a playlist: «New playlist» (an empty [initial]) and «Rename» (the current name). The confirm button waits
 * for a name that is not blank; one longer than [UserPlaylistRepository.MAX_NAME] cannot be typed.
 */
@Composable
fun PlaylistNameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val valid = UserPlaylistRepository.cleanName(text) != null
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun confirm() {
        if (!valid) return
        onConfirm(text)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(UserPlaylistRepository.MAX_NAME) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                label = { Text(stringResource(R.string.playlist_name_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
            )
        },
        confirmButton = { TextButton(onClick = ::confirm, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
