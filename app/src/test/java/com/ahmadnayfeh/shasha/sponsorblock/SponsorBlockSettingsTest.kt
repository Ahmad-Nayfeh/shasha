package com.ahmadnayfeh.shasha.sponsorblock

import com.ahmadnayfeh.shasha.data.TestDataStores
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SPEC §7 «فئات SponsorBlock»: the choice, its defaults, and the manual backup's part of it (plain JVM). */
class SponsorBlockSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val stores by lazy { TestDataStores(folder.root) }

    @After
    fun tearDown() = stores.closeAll()

    private fun SponsorBlockSettings.now() = runBlocking { categories.first() }

    @Test
    fun nothingChosenYet_meansTheSpecsDefaults() {
        val settings = SponsorBlockSettings(stores.open(SponsorBlockSettings.FILE_NAME))
        assertThat(settings.now()).containsExactly(
            SponsorCategory.SPONSOR,
            SponsorCategory.SELF_PROMO,
            SponsorCategory.INTERACTION,
            SponsorCategory.INTRO,
            SponsorCategory.OUTRO,
        )
    }

    @Test
    fun theChoiceIsKept_acrossARestart() = runBlocking<Unit> {
        val settings = SponsorBlockSettings(stores.open(SponsorBlockSettings.FILE_NAME))
        settings.setEnabled(SponsorCategory.FILLER, true)
        settings.setEnabled(SponsorCategory.OUTRO, false)
        val expected = SponsorCategory.DEFAULTS - SponsorCategory.OUTRO + SponsorCategory.FILLER
        assertThat(settings.now()).isEqualTo(expected)

        val restarted = SponsorBlockSettings(stores.restart(SponsorBlockSettings.FILE_NAME))
        assertThat(restarted.now()).isEqualTo(expected)
    }

    @Test
    fun anEmptyChoice_turnsSponsorBlockOff_andResetBringsTheDefaultsBack() = runBlocking<Unit> {
        val settings = SponsorBlockSettings(stores.open(SponsorBlockSettings.FILE_NAME))
        settings.setCategories(emptySet())
        assertThat(settings.now()).isEmpty()
        settings.reset()
        assertThat(settings.now()).isEqualTo(SponsorCategory.DEFAULTS)
    }

    @Test
    fun theBackupKeepsTheChoice_andARestoreBringsItBack() = runBlocking<Unit> {
        val store = stores.open(SponsorBlockSettings.FILE_NAME)
        val settings = SponsorBlockSettings(store)
        settings.setCategories(setOf(SponsorCategory.SPONSOR, SponsorCategory.MUSIC_OFFTOPIC))
        val exported = SponsorBlockSettings.backupSection(store).export()
        assertThat(exported).containsExactly(
            "categories",
            JsonObject(mapOf("stringSet" to JsonArray(listOf(JsonPrimitive("music_offtopic"), JsonPrimitive("sponsor"))))),
        )

        val other = stores.open("other")
        SponsorBlockSettings.backupSection(other).restore(exported)
        assertThat(SponsorBlockSettings(other).now()).containsExactly(SponsorCategory.SPONSOR, SponsorCategory.MUSIC_OFFTOPIC)

        // A backup made before this choice existed: back to the defaults. Unknown names are ignored.
        SponsorBlockSettings.backupSection(other).restore(emptyMap())
        assertThat(SponsorBlockSettings(other).now()).isEqualTo(SponsorCategory.DEFAULTS)
        SponsorBlockSettings.backupSection(other).restore(
            mapOf("categories" to JsonObject(mapOf("stringSet" to JsonArray(listOf(JsonPrimitive("intro"), JsonPrimitive("fromTheFuture")))))),
        )
        assertThat(SponsorBlockSettings(other).now()).containsExactly(SponsorCategory.INTRO)
    }
}
