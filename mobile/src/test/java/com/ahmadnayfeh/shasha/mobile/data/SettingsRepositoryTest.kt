package com.ahmadnayfeh.shasha.mobile.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import com.ahmadnayfeh.shasha.youtube.CommentSort
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun repository(file: File = File(folder.root, "settings.preferences_pb")) = SettingsRepository(
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { file },
        ),
    )

    private fun SettingsRepository.current() = runBlocking { settings.first() }

    @Test
    fun theDefaultsAreTheShippedOnes() {
        val settings = repository().current()

        assertThat(settings).isEqualTo(AppSettings())
        assertThat(settings.language).isEqualTo(AppLanguage.SYSTEM)
        assertThat(settings.theme).isEqualTo(AppTheme.SYSTEM)
        assertThat(settings.defaultQuality).isEqualTo(0)
        assertThat(settings.downloadQuality).isEqualTo(720)
        assertThat(settings.sponsorBlockEnabled).isTrue()
        assertThat(settings.sponsorCategories).containsExactly(SponsorCategory.SPONSOR, SponsorCategory.SELF_PROMO, SponsorCategory.INTERACTION)
        assertThat(settings.backgroundPlayback).isTrue()
        assertThat(settings.pictureInPicture).isTrue()
    }

    @Test
    fun everyChangeIsStoredAndComesBack() = runBlocking<Unit> {
        val repository = repository()

        repository.setLanguage(AppLanguage.ARABIC)
        repository.setTheme(AppTheme.DARK)
        repository.setDefaultQuality(1080)
        repository.setDownloadQuality(480)
        repository.setSponsorBlockEnabled(false)
        repository.setBackgroundPlayback(false)
        repository.setPictureInPicture(false)

        val settings = repository.current()
        assertThat(settings.language).isEqualTo(AppLanguage.ARABIC)
        assertThat(settings.theme).isEqualTo(AppTheme.DARK)
        assertThat(settings.defaultQuality).isEqualTo(1080)
        assertThat(settings.downloadQuality).isEqualTo(480)
        assertThat(settings.sponsorBlockEnabled).isFalse()
        assertThat(settings.backgroundPlayback).isFalse()
        assertThat(settings.pictureInPicture).isFalse()
    }

    @Test
    fun theCaptionsChosenInThePlayerAreKept_untilTurnedOffOrReset() = runBlocking<Unit> {
        val repository = repository()
        assertThat(repository.current().subtitleLanguage).isNull()

        repository.setSubtitleLanguage("en")
        assertThat(repository.current().subtitleLanguage).isEqualTo("en")
        repository.setSubtitleLanguage(null)
        assertThat(repository.current().subtitleLanguage).isNull()

        repository.setSubtitleLanguage("ar")
        repository.resetPlayback()
        assertThat(repository.current().subtitleLanguage).isNull()
        repository.setSubtitleLanguage("ar")
        repository.resetAll()
        assertThat(repository.current()).isEqualTo(AppSettings())
    }

    @Test
    fun commentsAreShownInTopOrderUntilChanged_theOrderIsKept_andTheirResetBringsBothBack() = runBlocking<Unit> {
        val repo = repository()
        assertThat(repo.current().showComments).isTrue()
        assertThat(repo.current().commentSort).isEqualTo(CommentSort.TOP)

        repo.setShowComments(false)
        repo.setCommentSort(CommentSort.NEWEST)
        assertThat(repo.current().showComments).isFalse()
        assertThat(repo.current().commentSort).isEqualTo(CommentSort.NEWEST)

        repo.resetComments()
        assertThat(repo.current().showComments).isTrue()
        assertThat(repo.current().commentSort).isEqualTo(CommentSort.TOP)

        repo.setShowComments(false)
        repo.resetAll()
        assertThat(repo.current().showComments).isTrue()
    }

    @Test
    fun theSettingsSurviveARestartOfTheStore() = runBlocking<Unit> {
        val file = File(folder.root, "again.preferences_pb")
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        SettingsRepository(PreferenceDataStoreFactory.create(scope = first, produceFile = { file })).setTheme(AppTheme.LIGHT)
        // The first store is closed (a DataStore allows one instance per file), as when the process ends.
        first.coroutineContext[Job]!!.cancelAndJoin()

        val second = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reopened = SettingsRepository(PreferenceDataStoreFactory.create(scope = second, produceFile = { file }))

        assertThat(reopened.current().theme).isEqualTo(AppTheme.LIGHT)
        second.cancel()
    }

    @Test
    fun sponsorBlockCategoriesAreSwitchedOneByOne() = runBlocking<Unit> {
        val repository = repository()

        repository.setSponsorCategory(SponsorCategory.INTRO, on = true)
        repository.setSponsorCategory(SponsorCategory.SPONSOR, on = false)

        assertThat(repository.current().sponsorCategories).containsExactly(SponsorCategory.SELF_PROMO, SponsorCategory.INTERACTION, SponsorCategory.INTRO)
    }

    @Test
    fun noCategoryAtAllIsAChoiceToo() = runBlocking<Unit> {
        val repository = repository()

        for (category in SponsorCategory.DEFAULTS) repository.setSponsorCategory(category, on = false)

        assertThat(repository.current().sponsorCategories).isEmpty()
    }

    @Test
    fun eachGroupHasItsOwnReset() = runBlocking<Unit> {
        val repository = repository()
        repository.setLanguage(AppLanguage.ENGLISH)
        repository.setTheme(AppTheme.DARK)
        repository.setDefaultQuality(480)
        repository.setDownloadQuality(360)
        repository.setSponsorBlockEnabled(false)
        repository.setSponsorCategory(SponsorCategory.OUTRO, true)
        repository.setBackgroundPlayback(false)

        repository.resetLanguage()
        assertThat(repository.current().language).isEqualTo(AppLanguage.SYSTEM)
        assertThat(repository.current().theme).isEqualTo(AppTheme.DARK) // the others stay

        repository.resetTheme()
        repository.resetDefaultQuality()
        assertThat(repository.current().theme).isEqualTo(AppTheme.SYSTEM)
        assertThat(repository.current().defaultQuality).isEqualTo(0)
        assertThat(repository.current().downloadQuality).isEqualTo(360)

        repository.resetDownloadQuality()
        assertThat(repository.current().downloadQuality).isEqualTo(720)

        repository.resetSponsorBlock()
        assertThat(repository.current().sponsorBlockEnabled).isTrue()
        assertThat(repository.current().sponsorCategories).isEqualTo(SponsorCategory.DEFAULTS)

        repository.resetPlayback()
        assertThat(repository.current().backgroundPlayback).isTrue()
    }

    @Test
    fun resetAllReturnsEverythingToTheDefaults() = runBlocking<Unit> {
        val repository = repository()
        repository.setLanguage(AppLanguage.ARABIC)
        repository.setTheme(AppTheme.LIGHT)
        repository.setDefaultQuality(144)
        repository.setDownloadQuality(144)
        repository.setSponsorBlockEnabled(false)
        repository.setSponsorCategory(SponsorCategory.FILLER, true)
        repository.setBackgroundPlayback(false)
        repository.setPictureInPicture(false)

        repository.resetAll()

        assertThat(repository.current()).isEqualTo(AppSettings())
    }

    @Test
    fun aDamagedFileStartsEmptyInsteadOfCrashing() {
        val file = File(folder.root, "broken.preferences_pb")
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))

        assertThat(repository(file).current()).isEqualTo(AppSettings())
    }

    @Test
    fun theLastFeedRefreshIsKeptApartFromTheChoices() = runBlocking<Unit> {
        val repository = repository()
        assertThat(repository.feedRefreshedAt.first()).isEqualTo(0L)

        repository.setFeedRefreshedAt(123_456L)
        repository.resetAll()

        assertThat(repository.feedRefreshedAt.first()).isEqualTo(123_456L)
    }

    @Test
    fun theLanguageHasItsLocale() {
        assertThat(AppLanguage.SYSTEM.locale).isNull()
        assertThat(AppLanguage.ARABIC.locale!!.language).isEqualTo("ar")
        assertThat(AppLanguage.ENGLISH.locale!!.language).isEqualTo("en")
    }
}
