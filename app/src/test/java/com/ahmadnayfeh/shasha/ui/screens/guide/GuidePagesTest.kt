package com.ahmadnayfeh.shasha.ui.screens.guide

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.AppLanguage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** «دليل شاشة»'s pages: both languages bundled page for page, decoded at the shown width, three in memory at most. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GuidePagesTest {
    private val assets get() = ApplicationProvider.getApplicationContext<android.content.Context>().assets

    private val decoded = mutableListOf<Int>()
    private val cache = GuidePageCache(PAGES) { index ->
        decoded += index
        ImageBitmap(4, 4)
    }

    private fun ready() = (0 until PAGES).filter { cache[it] is GuidePage.Ready }

    @Test
    fun bothLanguages_bundleTheSamePages_inOrder() {
        val arabic = GuideAssets.pages(assets, AppLanguage.ARABIC)
        val english = GuideAssets.pages(assets, AppLanguage.ENGLISH)
        assertThat(arabic).hasSize(PAGES)
        assertThat(english.map { it.substringAfterLast('/') }).isEqualTo(arabic.map { it.substringAfterLast('/') })
        assertThat(arabic.first()).isEqualTo("guide/ar/page-01.webp")
        assertThat(english.last()).isEqualTo("guide/en/page-17.webp")
    }

    @Test
    fun aPage_isDecodedAtTheWidthItIsShown_twoBytesAPixel() {
        val page = GuideAssets.decode(assets, "guide/en/page-01.webp", targetWidth = 640)!!
        assertThat(page.width).isAtMost(704)
        assertThat(page.width).isAtLeast(576)
        assertThat(page.config).isEqualTo(Bitmap.Config.RGB_565)
    }

    @Test
    fun theViewerHolds_thePageOnScreenAndItsNeighbours_neverMore() = runBlocking<Unit> {
        cache.show(0)
        assertThat(ready()).containsExactly(0, 1)
        cache.show(1)
        assertThat(ready()).containsExactly(0, 1, 2)
        cache.show(5)
        assertThat(ready()).containsExactly(4, 5, 6)
        cache.show(PAGES - 1)
        assertThat(ready()).containsExactly(PAGES - 2, PAGES - 1)
    }

    @Test
    fun aPageStillHeld_isNotDecodedAgain() = runBlocking<Unit> {
        cache.show(3)
        cache.show(4)
        cache.show(3)
        assertThat(decoded.count { it == 3 }).isEqualTo(1)
        assertThat(decoded.count { it == 4 }).isEqualTo(1)
    }

    @Test
    fun aPageThatCannotBeRead_saysSo_insteadOfLoadingForever() = runBlocking<Unit> {
        val failing = GuidePageCache(3) { index -> if (index == 1) null else ImageBitmap(4, 4) }
        failing.show(1)
        assertThat(failing[1]).isEqualTo(GuidePage.Failed)
        assertThat(failing[0]).isInstanceOf(GuidePage.Ready::class.java)
        assertThat(failing[2]).isInstanceOf(GuidePage.Ready::class.java)
    }

    private companion object {
        const val PAGES = 17
    }
}
