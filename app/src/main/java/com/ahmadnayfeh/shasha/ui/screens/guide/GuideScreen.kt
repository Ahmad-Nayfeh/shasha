package com.ahmadnayfeh.shasha.ui.screens.guide

import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_PAGE_DOWN
import android.view.KeyEvent.KEYCODE_PAGE_UP
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoader
import com.ahmadnayfeh.shasha.ui.settings.AppearanceIcons
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.LocalAppLanguage
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import kotlinx.coroutines.launch

/** «دليل شاشة» in the app's language: its bundled pages, decoded as wide as they are shown. */
@Composable
fun GuideRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val paths = remember(language) { GuideAssets.pages(context.assets, language) }
    val width = with(LocalDensity.current) { PageWidth.roundToPx() }
    val pages = remember(paths, width) { GuidePageCache.ofAssets(context.assets, paths, width) }
    GuideScreen(pages = pages, count = paths.size, onBack = onBack)
}

/**
 * The guide's pages, one at a time, at the width of a page you can read from the sofa (the TV's pixels, no scaling)
 * and scrolled when taller than the screen. LEFT and RIGHT turn the pages the way the book reads (in Arabic LEFT is
 * the next page, in English RIGHT), UP and DOWN scroll the page, BACK leaves. The counter and the keys' reminder sit
 * beside the page.
 */
@Composable
fun GuideScreen(pages: GuidePageCache, count: Int, onBack: () -> Unit, modifier: Modifier = Modifier, initialPage: Int = 0) {
    var index by rememberSaveable { mutableIntStateOf(initialPage.coerceIn(0, (count - 1).coerceAtLeast(0))) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    LaunchedEffect(index) {
        scroll.scrollTo(0)
        pages.show(index)
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    BackHandler(onBack = onBack)
    fun turn(by: Int) {
        val next = (index + by).coerceIn(0, (count - 1).coerceAtLeast(0))
        if (next != index) index = next
    }
    Row(
        modifier
            .fillMaxSize()
            .background(ShashaColors.Background)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    // The next page comes from the side the book is read towards: the left in Arabic.
                    KEYCODE_DPAD_LEFT -> turn(if (rtl) 1 else -1)
                    KEYCODE_DPAD_RIGHT -> turn(if (rtl) -1 else 1)
                    KEYCODE_PAGE_DOWN -> turn(1)
                    KEYCODE_PAGE_UP -> turn(-1)
                    KEYCODE_DPAD_DOWN -> scope.launch { scroll.step(down = true) }
                    KEYCODE_DPAD_UP -> scope.launch { scroll.step(down = false) }
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .focusable()
            .testTag("guide"),
    ) {
        // The start (the right in Arabic): what this is, where you are, and the keys; the way back to earlier pages.
        Box(Modifier.width(SideWidth).fillMaxHeight()) {
            Column(Modifier.padding(start = 28.dp, end = 16.dp, top = 44.dp)) {
                Icon(AppearanceIcons.MenuBook, contentDescription = null, tint = LocalAccent.current.bright, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.guide_open), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(14.dp))
                val described = stringResource(R.string.guide_page_of, (index + 1).toString(), count.toString())
                Text(
                    "${index + 1} / $count",
                    style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Ltr),
                    color = LocalAccent.current.bright,
                    modifier = Modifier.semantics { contentDescription = described }.testTag("guide_counter"),
                )
            }
            Column(
                Modifier.align(Alignment.BottomStart).padding(start = 28.dp, end = 16.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Hint(stringResource(R.string.guide_hint_pages))
                Hint(stringResource(R.string.guide_hint_scroll))
            }
            TurnMark(visible = index > 0, forward = false, modifier = Modifier.align(Alignment.CenterEnd))
        }
        Box(Modifier.width(PageWidth).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(vertical = 24.dp)) {
                Page(pages[index])
            }
            if (scroll.canScrollForward) EdgeFade(Modifier.align(Alignment.BottomCenter), toBottom = true)
            if (scroll.canScrollBackward) EdgeFade(Modifier.align(Alignment.TopCenter), toBottom = false)
        }
        // The end: the way on, to the next page.
        Box(Modifier.width(SideWidth).fillMaxHeight()) {
            TurnMark(visible = index < count - 1, forward = true, modifier = Modifier.align(Alignment.CenterStart))
        }
    }
}

@Composable
private fun Page(page: GuidePage) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(PAGE_ASPECT)
            .shadow(10.dp, shape)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, ShashaColors.Outline, shape)
            .testTag("guide_page"),
        contentAlignment = Alignment.Center,
    ) {
        when (page) {
            GuidePage.Loading -> ShashaLoader(size = 48.dp)
            GuidePage.Failed -> Text(
                stringResource(R.string.guide_page_failed),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF14202B),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
            is GuidePage.Ready -> Image(page.image, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize().testTag("guide_page_ready"))
        }
    }
}

/** A quiet arrow beside the page where there is a page to turn to (never focusable: the keys turn the pages). */
@Composable
private fun TurnMark(visible: Boolean, forward: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(horizontal = 12.dp)
            .size(40.dp)
            .alpha(if (visible) 1f else 0f)
            .background(ShashaColors.Surface, CircleShape)
            .border(1.dp, ShashaColors.Outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // The chevron points the way the book reads (mirrored with the layout); turned around for the earlier pages.
        Icon(
            ShashaIcons.Chevron,
            contentDescription = null,
            tint = ShashaColors.Muted,
            modifier = Modifier.size(24.dp).graphicsLayer { if (!forward) scaleX = -1f },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = ShashaColors.Muted)
}

@Composable
private fun EdgeFade(modifier: Modifier, toBottom: Boolean) {
    val colors = if (toBottom) listOf(Color.Transparent, ShashaColors.Background) else listOf(ShashaColors.Background, Color.Transparent)
    Box(modifier.fillMaxWidth().height(28.dp).background(Brush.verticalGradient(colors)))
}

/** UP / DOWN: a good part of the screen at a time, smoothly. */
private suspend fun ScrollState.step(down: Boolean) {
    val by = viewportSize * SCROLL_FRACTION
    animateScrollBy(if (down) by else -by)
}

/** The pages' own width on a 1920x1080 TV (xhdpi: 2 px a dp) is 1280 px: drawn one to one. */
private val PageWidth: Dp = 640.dp

/** Each side of the page: (960 - 640) / 2 on the TV's 960 dp. */
private val SideWidth: Dp = 160.dp
private const val PAGE_ASPECT = 1280f / 1812f
private const val SCROLL_FRACTION = 0.45f
