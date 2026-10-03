package com.ahmadnayfeh.shasha.mobile.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ahmadnayfeh.shasha.mobile.MainDispatcherRule
import com.ahmadnayfeh.shasha.mobile.awaitReal
import com.ahmadnayfeh.shasha.mobile.ui.settings.SettingsViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** «Show Shorts»: on by default, stored like the other settings, with a reset of its own that the other groups' resets leave alone. */
class ShortsSettingTest {

    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun repository(file: File = File(folder.root, "settings.preferences_pb")) =
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))

    private fun SettingsRepository.current() = runBlocking { settings.first() }

    @Test
    fun shortsAreShownUnlessTheViewerTurnsThemOff() = runBlocking<Unit> {
        val repository = repository()
        assertThat(repository.current().showShorts).isTrue()
        assertThat(AppSettings().showShorts).isTrue()

        repository.setShowShorts(false)
        assertThat(repository.current().showShorts).isFalse()

        repository.setShowShorts(true)
        assertThat(repository.current().showShorts).isTrue()
    }

    @Test
    fun theChoiceSurvivesARestartOfTheStore() = runBlocking<Unit> {
        val file = File(folder.root, "again.preferences_pb")
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        SettingsRepository(PreferenceDataStoreFactory.create(scope = first, produceFile = { file })).setShowShorts(false)
        first.cancel()
        kotlinx.coroutines.delay(100) // the first store is closed (a DataStore allows one instance per file), as when the process ends

        val second = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reopened = SettingsRepository(PreferenceDataStoreFactory.create(scope = second, produceFile = { file }))

        assertThat(reopened.current().showShorts).isFalse()
        second.cancel()
    }

    @Test
    fun theirResetAloneBringsThemBack_andTheOtherResetsLeaveTheChoiceAlone() = runBlocking<Unit> {
        val repository = repository()
        repository.setShowShorts(false)
        repository.setBackgroundPlayback(false)

        repository.resetPlayback()
        repository.resetSponsorBlock()
        repository.resetTheme()
        assertThat(repository.current().showShorts).isFalse() // another group's reset does not touch it
        assertThat(repository.current().backgroundPlayback).isTrue()

        repository.resetShorts()
        assertThat(repository.current().showShorts).isTrue()
    }

    @Test
    fun resetAllBringsThemBackToo() = runBlocking<Unit> {
        val repository = repository()
        repository.setShowShorts(false)
        repository.setTheme(AppTheme.DARK)

        repository.resetAll()

        assertThat(repository.current()).isEqualTo(AppSettings())
        assertThat(repository.current().showShorts).isTrue()
    }

    @Test
    fun theSettingsPageChangesItThroughItsViewModel() = runBlocking<Unit> {
        val repository = repository()
        val vm = SettingsViewModel(repository)

        vm.setShowShorts(false)
        awaitReal { !repository.current().showShorts }

        vm.resetShorts()
        awaitReal { repository.current().showShorts }
    }
}
