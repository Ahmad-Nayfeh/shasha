package com.ahmadnayfeh.shasha.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * The ViewModels of one profile's screens (and of its navigation back stack) live here and are cleared
 * when the profile's screens go (a switch, the picker): nothing of the adult's search or player stays in
 * memory after the TV was handed to a child, and nothing leaks from one profile session to the next.
 */
@Composable
fun ProfileScope(content: @Composable () -> Unit) {
    val owner = remember { ScopeOwner() }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

private class ScopeOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}
