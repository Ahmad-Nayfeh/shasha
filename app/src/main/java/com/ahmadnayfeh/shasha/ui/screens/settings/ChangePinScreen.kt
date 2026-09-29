package com.ahmadnayfeh.shasha.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.LocalAppMessages
import com.ahmadnayfeh.shasha.ui.components.NewPinPad
import com.ahmadnayfeh.shasha.ui.components.ParentPinPad
import com.ahmadnayfeh.shasha.ui.components.PinCheck
import com.ahmadnayfeh.shasha.ui.components.PinPanel
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import kotlinx.coroutines.launch

/**
 * تغيير رمز PIN: the current PIN (through the one attempt limiter), then the new one twice. The phone's
 * parent sessions end when it changes (the data layer announces it).
 */
@Composable
fun ChangePinRoute(onDone: () -> Unit) {
    val vm = containerViewModel { ChangePinViewModel(it.parentPin) }
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val resources = LocalResources.current
    var current by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(ShashaDimens.ScreenPadding).testTag("change_pin"), contentAlignment = Alignment.Center) {
        AnimatedContent(current == null, transitionSpec = { fadeIn(tween(260, 60)) togetherWith fadeOut(tween(140)) }, label = "changePin") { askCurrent ->
            PinPanel {
                if (askCurrent) {
                    ParentPinPad(
                        title = stringResource(R.string.change_pin_current),
                        subtitle = stringResource(R.string.change_pin_current_body),
                        check = vm::checkCurrent,
                        onAccepted = { current = it },
                        initialLockMillis = vm::lockMillis,
                    )
                } else {
                    NewPinPad(
                        title = stringResource(R.string.change_pin_new),
                        subtitle = stringResource(R.string.setup_pin_body_short),
                        busy = busy,
                        onChosen = { new ->
                            val old = current ?: return@NewPinPad
                            busy = true
                            scope.launch {
                                val result = vm.change(old, new)
                                busy = false
                                if (result == PinCheck.Accepted) {
                                    messages?.show(resources.getString(R.string.change_pin_done), ShashaIcons.Key)
                                    onDone()
                                } else {
                                    // The current PIN stopped working meanwhile (changed from elsewhere, locked out): start over.
                                    messages?.show(resources.getString(R.string.change_pin_failed), ShashaIcons.Info)
                                    current = null
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
