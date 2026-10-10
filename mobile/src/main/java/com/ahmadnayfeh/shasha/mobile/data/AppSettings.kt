package com.ahmadnayfeh.shasha.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorCategory
import com.ahmadnayfeh.shasha.youtube.CommentSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.Locale

/** «Language»: the app's own, whatever the phone's. [SYSTEM] follows the phone (and Android's per-app language). */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ARABIC("ar"),
    ENGLISH("en"),
    ;

    val locale: Locale? get() = tag?.let { Locale.forLanguageTag(it) }

    companion object {
        val DEFAULT = SYSTEM
    }
}

enum class AppTheme {
    SYSTEM,
    LIGHT,
    DARK,
}

/** Heights the quality settings offer (0 = automatic). */
object QualityChoices {
    /** The player's default quality: automatic, or the best one up to this height. */
    val PLAYBACK = listOf(0, 1080, 720, 480, 360, 240, 144)

    /** What a download asks for first: the best quality up to this height that the video has. */
    val DOWNLOAD = listOf(1080, 720, 480, 360, 240, 144)
}

/** Everything the viewer can change in Settings; the defaults are what «Reset» returns to. */
data class AppSettings(
    val language: AppLanguage = AppLanguage.DEFAULT,
    val theme: AppTheme = DEFAULT_THEME,
    /** 0: automatic (the player adapts to the connection). Otherwise the best quality up to this height. */
    val defaultQuality: Int = DEFAULT_PLAYBACK_QUALITY,
    /** The quality a download starts from: the best one the video has up to this height. */
    val downloadQuality: Int = DEFAULT_DOWNLOAD_QUALITY,
    val sponsorBlockEnabled: Boolean = true,
    val sponsorCategories: Set<SponsorCategory> = SponsorCategory.DEFAULTS,
    /** Keep playing (audio) when the app is left or the screen turns off. */
    val backgroundPlayback: Boolean = true,
    /** Leaving the app while a video plays turns it into a floating window. */
    val pictureInPicture: Boolean = true,
    /**
     * Shorts (the vertical videos): their rows on Home and in search, their tab on a channel's page and their place among the
     * suggestions. Off hides them everywhere on the phone, so a family that does not want them never meets one.
     */
    val showShorts: Boolean = true,
    /** Comments: the section under a video and the button of the Shorts viewer. Off hides them entirely. */
    val showComments: Boolean = true,
    /** The order comments were last shown in (YouTube's «Top» or «Newest first»): the next video opens its comments in it. */
    val commentSort: CommentSort = CommentSort.TOP,
    /**
     * The captions last chosen in the player (a subtitle language code, such as "ar" or "en"), shown on every video that has them;
     * null: off. Choosing in the player changes it.
     */
    val subtitleLanguage: String? = null,
) {
    companion object {
        val DEFAULT_THEME = AppTheme.SYSTEM
        const val DEFAULT_PLAYBACK_QUALITY = 0
        const val DEFAULT_DOWNLOAD_QUALITY = 720
    }
}

/**
 * [AppSettings] in a Preferences DataStore. A damaged file starts empty instead of crashing, and an unknown value in it
 * reads as the default. Each group of settings has its own reset.
 */
class SettingsRepository(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    /** When the home feed was last refreshed (wall clock ms; 0 = never). */
    val feedRefreshedAt: Flow<Long> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[FEED_REFRESHED_AT] ?: 0L }
        .distinctUntilChanged()

    suspend fun setFeedRefreshedAt(timeMs: Long) {
        store.edit { it[FEED_REFRESHED_AT] = timeMs }
    }

    suspend fun setLanguage(language: AppLanguage) = store.edit { it[LANGUAGE] = language.name }.let {}

    suspend fun setTheme(theme: AppTheme) = store.edit { it[THEME] = theme.name }.let {}

    suspend fun setDefaultQuality(height: Int) = store.edit { it[DEFAULT_QUALITY] = height.coerceAtLeast(0) }.let {}

    suspend fun setDownloadQuality(height: Int) = store.edit { it[DOWNLOAD_QUALITY] = height.coerceAtLeast(1) }.let {}

    suspend fun setSponsorBlockEnabled(enabled: Boolean) = store.edit { it[SPONSORBLOCK_ENABLED] = enabled }.let {}

    suspend fun setSponsorCategory(category: SponsorCategory, on: Boolean) {
        store.edit { prefs ->
            val current = prefs.toSettings().sponsorCategories
            val next = if (on) current + category else current - category
            prefs[SPONSORBLOCK_CATEGORIES] = next.mapTo(HashSet()) { it.apiKey }
        }
    }

    suspend fun setBackgroundPlayback(enabled: Boolean) = store.edit { it[BACKGROUND_PLAYBACK] = enabled }.let {}

    suspend fun setPictureInPicture(enabled: Boolean) = store.edit { it[PICTURE_IN_PICTURE] = enabled }.let {}

    suspend fun setShowShorts(enabled: Boolean) = store.edit { it[SHOW_SHORTS] = enabled }.let {}

    suspend fun setShowComments(enabled: Boolean) = store.edit { it[SHOW_COMMENTS] = enabled }.let {}

    suspend fun setCommentSort(sort: CommentSort) = store.edit { it[COMMENT_SORT] = sort.name }.let {}

    /** The captions' language, or null for none. */
    suspend fun setSubtitleLanguage(language: String?) = store.edit {
        val code = language?.trim()
        if (code.isNullOrEmpty()) it.remove(SUBTITLE_LANGUAGE) else it[SUBTITLE_LANGUAGE] = code
    }.let {}

    suspend fun resetLanguage() = store.edit { it.remove(LANGUAGE) }.let {}

    suspend fun resetTheme() = store.edit { it.remove(THEME) }.let {}

    suspend fun resetDefaultQuality() = store.edit { it.remove(DEFAULT_QUALITY) }.let {}

    suspend fun resetDownloadQuality() = store.edit { it.remove(DOWNLOAD_QUALITY) }.let {}

    /** SponsorBlock: on, with the default categories. */
    suspend fun resetSponsorBlock() = store.edit {
        it.remove(SPONSORBLOCK_ENABLED)
        it.remove(SPONSORBLOCK_CATEGORIES)
    }.let {}

    /** Background playback, picture-in-picture and the captions (off). */
    suspend fun resetPlayback() = store.edit {
        it.remove(BACKGROUND_PLAYBACK)
        it.remove(PICTURE_IN_PICTURE)
        it.remove(SUBTITLE_LANGUAGE)
    }.let {}

    /** Shorts: shown. */
    suspend fun resetShorts() = store.edit { it.remove(SHOW_SHORTS) }.let {}

    /** Comments: shown, in YouTube's «Top» order. */
    suspend fun resetComments() = store.edit {
        it.remove(SHOW_COMMENTS)
        it.remove(COMMENT_SORT)
    }.let {}

    suspend fun resetAll() = store.edit {
        for (key in listOf(LANGUAGE, THEME, DEFAULT_QUALITY, DOWNLOAD_QUALITY, SPONSORBLOCK_ENABLED, BACKGROUND_PLAYBACK, PICTURE_IN_PICTURE, SHOW_SHORTS, SHOW_COMMENTS)) {
            it.remove(key)
        }
        it.remove(COMMENT_SORT)
        it.remove(SPONSORBLOCK_CATEGORIES)
        it.remove(SUBTITLE_LANGUAGE)
    }.let {}

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            language = this[LANGUAGE]?.let { name -> AppLanguage.entries.firstOrNull { it.name == name } } ?: defaults.language,
            theme = this[THEME]?.let { name -> AppTheme.entries.firstOrNull { it.name == name } } ?: defaults.theme,
            defaultQuality = this[DEFAULT_QUALITY]?.takeIf { it >= 0 } ?: defaults.defaultQuality,
            downloadQuality = this[DOWNLOAD_QUALITY]?.takeIf { it > 0 } ?: defaults.downloadQuality,
            sponsorBlockEnabled = this[SPONSORBLOCK_ENABLED] ?: defaults.sponsorBlockEnabled,
            sponsorCategories = this[SPONSORBLOCK_CATEGORIES]
                ?.mapNotNullTo(LinkedHashSet()) { SponsorCategory.fromApiKey(it) }
                ?: defaults.sponsorCategories,
            backgroundPlayback = this[BACKGROUND_PLAYBACK] ?: defaults.backgroundPlayback,
            pictureInPicture = this[PICTURE_IN_PICTURE] ?: defaults.pictureInPicture,
            showShorts = this[SHOW_SHORTS] ?: defaults.showShorts,
            showComments = this[SHOW_COMMENTS] ?: defaults.showComments,
            commentSort = this[COMMENT_SORT]?.let { name -> CommentSort.entries.firstOrNull { it.name == name } } ?: defaults.commentSort,
            subtitleLanguage = this[SUBTITLE_LANGUAGE]?.takeIf { it.isNotBlank() } ?: defaults.subtitleLanguage,
        )
    }

    companion object {
        private val LANGUAGE = stringPreferencesKey("language")
        private val THEME = stringPreferencesKey("theme")
        private val DEFAULT_QUALITY = intPreferencesKey("default_quality")
        private val DOWNLOAD_QUALITY = intPreferencesKey("download_quality")
        private val SPONSORBLOCK_ENABLED = booleanPreferencesKey("sponsorblock_enabled")
        private val SPONSORBLOCK_CATEGORIES = stringSetPreferencesKey("sponsorblock_categories")
        private val BACKGROUND_PLAYBACK = booleanPreferencesKey("background_playback")
        private val PICTURE_IN_PICTURE = booleanPreferencesKey("picture_in_picture")
        private val SHOW_SHORTS = booleanPreferencesKey("show_shorts")
        private val SHOW_COMMENTS = booleanPreferencesKey("show_comments")
        private val COMMENT_SORT = stringPreferencesKey("comment_sort")
        private val SUBTITLE_LANGUAGE = stringPreferencesKey("subtitle_language")
        private val FEED_REFRESHED_AT = longPreferencesKey("feed_refreshed_at")

        /** The settings file (`settings.preferences_pb`); a damaged one starts empty. */
        fun createStore(context: Context, scope: CoroutineScope): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { context.preferencesDataStoreFile("settings") },
        )
    }
}
