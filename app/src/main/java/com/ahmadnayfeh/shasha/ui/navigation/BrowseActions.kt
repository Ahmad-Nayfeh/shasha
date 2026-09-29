package com.ahmadnayfeh.shasha.ui.navigation

import androidx.compose.runtime.Stable
import com.ahmadnayfeh.shasha.ui.model.ChannelUi
import com.ahmadnayfeh.shasha.ui.model.ItemUi
import com.ahmadnayfeh.shasha.ui.model.PlaylistUi
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.youtube.VideoItem

/** Where browsing screens can go. Built once by the nav host; screens never see the NavController. */
@Stable
class BrowseActions(
    val play: (VideoItem) -> Unit,
    val openChannel: (channelId: String, name: String?) -> Unit,
    val openPlaylist: (playlistId: String) -> Unit,
    val openImport: () -> Unit,
    val openDiagnostics: () -> Unit,
    val checkUpdates: () -> Unit,
) {
    fun open(item: ItemUi) = when (item) {
        is VideoUi -> play(item.video)
        is ChannelUi -> openChannel(item.channel.channelId, item.channel.name)
        is PlaylistUi -> openPlaylist(item.playlist.playlistId)
    }
}

/** Where the settings go (adult profile). The parent PIN is asked before the parental ones open. */
@Stable
class SettingsActions(
    val openProfiles: () -> Unit,
    val openProfile: (profileId: String) -> Unit,
    val openAllowlist: (profileId: String) -> Unit,
    val openBlocklist: () -> Unit,
    val openChangePin: () -> Unit,
    val openStats: () -> Unit,
    val openImport: () -> Unit,
    val openDiagnostics: () -> Unit,
    val openProtection: () -> Unit = {},
)
