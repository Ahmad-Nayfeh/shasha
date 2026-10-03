package com.ahmadnayfeh.shasha.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ahmadnayfeh.shasha.R

/**
 * «حول»: who made Shasha, and the developer's other apps (a QR code each). At the very bottom, one closed row
 * «التراخيص» opens SponsorBlock's attribution and the libraries with their licences ([TechnicalInfo]): their
 * licences ask that whoever gets the app can read them, but nobody needs them in view (owner decision). Each card
 * takes the focus in turn, so the D-pad reads the page from top to bottom.
 */
@Composable
internal fun AboutPane(first: FocusRequester) {
    var licences by remember { mutableStateOf(false) }
    PaneColumn(spacing = 12.dp) {
        DeveloperCard(Modifier.focusRequester(first).paneItem("about_developer"))
        Spacer(Modifier.height(2.dp))
        MoreApps()
        Spacer(Modifier.height(2.dp))
        PaneRow(
            title = stringResource(R.string.about_licences),
            onClick = { licences = !licences },
            modifier = Modifier.paneItem("about_licences"),
            icon = SettingsIcons.Description,
        )
        if (licences) TechnicalInfo()
    }
}
