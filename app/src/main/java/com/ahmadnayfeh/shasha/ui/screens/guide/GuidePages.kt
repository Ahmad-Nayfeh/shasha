package com.ahmadnayfeh.shasha.ui.screens.guide

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import com.ahmadnayfeh.shasha.data.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.abs

/** «دليل شاشة»'s pages, bundled as assets/guide/<ar|en>/page-NN.webp (1280 px wide), one folder per app language. */
object GuideAssets {
    fun folder(language: AppLanguage): String = "guide/${language.tag}"

    /** The pages of [language]'s guide, in order (empty when this build has none). */
    fun pages(assets: AssetManager, language: AppLanguage): List<String> {
        val folder = folder(language)
        val names = try {
            assets.list(folder).orEmpty()
        } catch (e: IOException) {
            emptyArray()
        }
        return names.filter { it.endsWith(".webp") }.sorted().map { "$folder/$it" }
    }

    /**
     * One page decoded at the width it is shown ([targetWidth] pixels, never wider than the file): the TV holds what
     * it draws, not the file's full size. No transparency in the pages, so 2 bytes a pixel (RGB_565).
     */
    fun decode(assets: AssetManager, path: String, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        if (width <= 0) return null
        var sample = 1
        while (width / (sample * 2) >= targetWidth) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = assets.open(path).use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        // Within a tenth of the target: drawn as it is (the GPU scales it). Larger: scaled down once, here.
        if (decoded.width <= targetWidth * 11 / 10) return decoded
        val height = (decoded.height.toLong() * targetWidth / decoded.width).toInt().coerceAtLeast(1)
        return decoded.scale(targetWidth, height).also { if (it !== decoded) decoded.recycle() }
    }
}

/** A page as the viewer has it. */
@Immutable
sealed interface GuidePage {
    data object Loading : GuidePage

    data class Ready(val image: ImageBitmap) : GuidePage

    data object Failed : GuidePage
}

/**
 * The pages the viewer holds: the one on screen and its two neighbours (the next turn is instant), never more, so a
 * weak TV keeps three decoded pages at most. [decode] runs off the main thread.
 */
@Stable
class GuidePageCache(private val count: Int, private val decode: (index: Int) -> ImageBitmap?) {
    private val pages = mutableStateMapOf<Int, GuidePage>()

    operator fun get(index: Int): GuidePage = pages[index] ?: GuidePage.Loading

    /** [index] is on screen: forgets the pages beyond its neighbours, then reads it and its neighbours. */
    suspend fun show(index: Int) {
        pages.keys.filter { abs(it - index) > 1 }.forEach { pages.remove(it) }
        load(index)
        load(index + 1)
        load(index - 1)
    }

    private suspend fun load(index: Int) {
        if (index !in 0 until count || pages[index] is GuidePage.Ready) return
        val page = try {
            withContext(Dispatchers.IO) { decode(index) }?.let { GuidePage.Ready(it) } ?: GuidePage.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GuidePage.Failed
        } catch (e: OutOfMemoryError) {
            GuidePage.Failed
        }
        pages[index] = page
    }

    companion object {
        /** The bundled pages of [paths], decoded [targetWidth] pixels wide. */
        fun ofAssets(assets: AssetManager, paths: List<String>, targetWidth: Int) =
            GuidePageCache(paths.size) { index -> GuideAssets.decode(assets, paths[index], targetWidth)?.asImageBitmap() }
    }
}
