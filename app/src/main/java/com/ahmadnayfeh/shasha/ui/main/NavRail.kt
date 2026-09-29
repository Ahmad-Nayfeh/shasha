package com.ahmadnayfeh.shasha.ui.main

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.ShashaFocus
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/** The rail's destinations, top to bottom (settings sits apart at the bottom). */
enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    HOME(R.string.nav_home, ShashaIcons.Home),
    SEARCH(R.string.nav_search, ShashaIcons.Search),
    SUBSCRIPTIONS(R.string.nav_subscriptions, ShashaIcons.Subscriptions),
    WATCH_LATER(R.string.nav_watch_later, ShashaIcons.WatchLater),
    HISTORY(R.string.nav_history, ShashaIcons.History),
    SETTINGS(R.string.nav_settings, ShashaIcons.Settings),
}

/** Width the rail always takes on the right; the screens are laid out beside it. */
val RailCollapsedWidth = 104.dp
private val RailExpandedWidth = 272.dp
private val RailEdge = 48.dp // overscan-safe distance from the screen's right edge
private val ItemHeight = 50.dp
private val ScrimWidth = 360.dp

/**
 * The navigation rail on the right (the start side in Arabic). Icons only while the content has focus;
 * when focus enters it, it widens over the content and shows the labels. Focus always enters on the
 * selected tab, and OK switches tabs (moving the focus doesn't: no screen loads just because focus
 * passes by).
 */
@Composable
fun NavRail(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    itemFocus: Map<Tab, FocusRequester>,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val width by animateDpAsState(if (expanded) RailExpandedWidth else RailCollapsedWidth, tween(240), label = "railWidth")
    val panel by animateFloatAsState(if (expanded) 1f else 0f, tween(240), label = "railPanel")

    Box(
        modifier
            .fillMaxHeight()
            .width(width)
            .drawBehind {
                // Collapsed: a faint shade behind the icons. Expanded: an opaque panel, and the content
                // beside it recedes into a soft shadow (drawn left of the rail, outside its bounds).
                val right = ShashaColors.Surface.copy(alpha = 0.55f + 0.45f * panel)
                val left = ShashaColors.Surface.copy(alpha = panel)
                drawRect(Brush.horizontalGradient(listOf(left, right), startX = 0f, endX = size.width))
                if (panel > 0f) {
                    val shade = ScrimWidth.toPx()
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, ShashaColors.Background.copy(alpha = 0.72f * panel)),
                            startX = -shade,
                            endX = 0f,
                        ),
                        topLeft = Offset(-shade, 0f),
                        size = size.copy(width = shade),
                    )
                    drawRect(ShashaColors.Outline.copy(alpha = panel), topLeft = Offset.Zero, size = size.copy(width = 1.dp.toPx()))
                }
            }
            .testTag("nav_rail"),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(start = RailEdge, end = if (expanded) 20.dp else 0.dp, top = 40.dp, bottom = 40.dp)
                .onFocusChanged { expanded = it.hasFocus }
                .focusProperties { onEnter = { itemFocus[selected]?.requestFocus() } }
                .focusGroup(),
        ) {
            RailLogo(expanded)
            Spacer(Modifier.height(40.dp))
            Tab.entries.filter { it != Tab.SETTINGS }.forEach { tab ->
                RailItem(tab, tab == selected, expanded, { onSelect(tab) }, Modifier.focusRequester(itemFocus.getValue(tab)))
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.weight(1f))
            RailItem(Tab.SETTINGS, selected == Tab.SETTINGS, expanded, { onSelect(Tab.SETTINGS) }, Modifier.focusRequester(itemFocus.getValue(Tab.SETTINGS)))
        }
    }
}

@Composable
private fun RailLogo(expanded: Boolean) {
    Row(Modifier.height(40.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.splash_logo), contentDescription = null, modifier = Modifier.size(40.dp).padding(2.dp))
        AnimatedVisibility(expanded, enter = fadeIn(tween(200, delayMillis = 80)), exit = fadeOut(tween(120))) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = ShashaColors.Text,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun RailItem(tab: Tab, selected: Boolean, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(ItemHeight)
            .semantics { this.selected = selected }
            .testTag("rail_${tab.name}"),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) ShashaColors.Primary.copy(alpha = 0.16f) else Color.Transparent,
            contentColor = when {
                selected -> ShashaColors.PrimaryBright
                expanded -> ShashaColors.Text
                else -> ShashaColors.Muted
            },
            focusedContainerColor = ShashaColors.Primary,
            focusedContentColor = ShashaColors.OnPrimary,
            pressedContainerColor = ShashaColors.PrimaryBright,
            pressedContentColor = ShashaColors.OnPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = ShashaFocus.ButtonGlow),
    ) {
        Row(Modifier.fillMaxHeight().padding(start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(tab.icon, contentDescription = stringResource(tab.label), modifier = Modifier.size(24.dp))
            AnimatedVisibility(expanded, enter = fadeIn(tween(200, delayMillis = 80)), exit = fadeOut(tween(100))) {
                Text(
                    stringResource(tab.label),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}
