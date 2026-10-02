package com.ahmadnayfeh.shasha.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/**
 * «حول»: who made Shasha, and the developer's other apps (a QR code each), and nothing else. The version, the
 * extractor, SponsorBlock's attribution and the licences are under «تشخيص» → «معلومات تقنية والتراخيص»
 * ([TechnicalInfo]). Each card takes the focus in turn, so the D-pad reads the page from top to bottom.
 */
@Composable
internal fun AboutPane(first: FocusRequester) {
    PaneColumn(spacing = 12.dp) {
        DeveloperCard(Modifier.focusRequester(first).paneItem("about_developer"))
        Spacer(Modifier.height(2.dp))
        MoreApps()
    }
}
