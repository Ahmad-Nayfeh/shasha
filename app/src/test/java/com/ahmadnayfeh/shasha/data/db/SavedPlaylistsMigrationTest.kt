package com.ahmadnayfeh.shasha.data.db

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ADULT_PROFILE_ID
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * A TV with a v1.1–1.6 build has a version 3 database. The build with the saved playlists must open it (a changed schema
 * under the same version would fail Room's check and crash), keep its rows, and add the empty saved_playlists table.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SavedPlaylistsMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun version3_opensAsVersion4_keepingItsRows_withAnEmptyLibrary() = runBlocking<Unit> {
        val file = context.getDatabasePath("migration-v3.db").apply { parentFile?.mkdirs(); delete() }
        createVersion3(file)

        val db = Room.databaseBuilder(context, ShashaDatabase::class.java, file.name).build()
        try {
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(4)
            assertThat(db.profiles().get(ADULT_PROFILE_ID)?.json).isEqualTo("{}")
            assertThat(db.watchLater().observe(ADULT_PROFILE_ID).first().map { it.videoId }).containsExactly("kept")
            assertThat(db.sponsorSegments().get("lC3h3Lm3q54")?.segments).isEqualTo("[]")

            val saved = db.savedPlaylists()
            assertThat(saved.observe(ADULT_PROFILE_ID).first()).isEmpty()
            saved.upsert(SavedPlaylistEntity(ADULT_PROFILE_ID, "PLkept", "قائمة", "قناة", null, 12, 5))
            assertThat(saved.observe(ADULT_PROFILE_ID).first().map { it.playlistId }).containsExactly("PLkept")
            // Deleting the profile takes its saved playlists with it.
            db.profiles().deleteSavedPlaylists(ADULT_PROFILE_ID)
            assertThat(saved.observe(ADULT_PROFILE_ID).first()).isEmpty()
        } finally {
            db.close()
        }
    }

    /** The database exactly as app/schemas/…/3.json describes it, with a few rows. */
    private fun createVersion3(file: File) {
        val schema = Json.parseToJsonElement(schemaFile(3).readText()).jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL("INSERT INTO profiles (id, sortOrder, json) VALUES ('$ADULT_PROFILE_ID', 0, '{}')")
            db.execSQL(
                "INSERT INTO watch_later (profileId, videoId, title, channelId, channelName, thumbnailUrl, durationSeconds, addedAtMs) " +
                    "VALUES ('$ADULT_PROFILE_ID', 'kept', 'عنوان', NULL, NULL, NULL, 60, 1)",
            )
            db.execSQL("INSERT INTO sponsor_segments (videoId, fetchedAtMs, segments) VALUES ('lC3h3Lm3q54', 1, '[]')")
            db.version = 3
        }
    }

    /** Unit tests run in the module directory; the fallback covers a run from the project root. */
    private fun schemaFile(version: Int): File {
        val path = "schemas/com.ahmadnayfeh.shasha.data.db.ShashaDatabase/$version.json"
        return listOf(File(path), File("app/$path")).first { it.exists() }
    }
}
