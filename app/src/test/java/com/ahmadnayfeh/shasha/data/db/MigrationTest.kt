package com.ahmadnayfeh.shasha.data.db

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ADULT_PROFILE_ID
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.data.UsageRepository
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
 * A TV that has a phase-2 build installed has a version 1 database: the phase-3 build must open it,
 * keep its rows and add the new tables (Room refuses to open a changed schema under the same version).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun version1_opensAsVersion2_keepingItsRows() = runBlocking<Unit> {
        val file = context.getDatabasePath("migration.db").apply { parentFile?.mkdirs(); delete() }
        createVersion1(file)

        val db = Room.databaseBuilder(context, ShashaDatabase::class.java, file.name).build()
        try {
            assertThat(db.history().observe(ADULT_PROFILE_ID, 10).first().map { it.videoId }).containsExactly("kept")
            assertThat(db.subscriptions().get("UCkeptkeptkeptkeptkept00")?.name).isEqualTo("قناة")

            val owner = ProfileRepository(db).ensureOwner("الكبار")
            assertThat(owner.id).isEqualTo(ADULT_PROFILE_ID)
            UsageRepository(db).addForeground(owner.id, 1, 15)
            assertThat(UsageRepository(db).foregroundSeconds(owner.id, 1)).isEqualTo(15)
            db.surrenders().insert(SurrenderEventEntity(profileId = owner.id, epochMs = 1, videoId = "v"))
            // Through version 2 to the current one (3 added the SponsorBlock cache: SponsorSegmentsMigrationTest; 4 the saved
            // playlists: SavedPlaylistsMigrationTest).
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(4)
        } finally {
            db.close()
        }
    }

    /** The database exactly as app/schemas/…/1.json describes it, with one row in two tables. */
    private fun createVersion1(file: File) {
        val schema = Json.parseToJsonElement(schemaFile(1).readText()).jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL("INSERT INTO subscriptions (channelId, name, avatarUrl, addedAtMs) VALUES ('UCkeptkeptkeptkeptkept00', 'قناة', NULL, 1)")
            db.execSQL(
                "INSERT INTO history (profileId, videoId, title, channelId, channelName, thumbnailUrl, durationSeconds, watchedAtMs) " +
                    "VALUES ('$ADULT_PROFILE_ID', 'kept', 'عنوان', NULL, NULL, NULL, 60, 1)",
            )
            db.version = 1
        }
    }

    /** Unit tests run in the module directory; the fallback covers a run from the project root. */
    private fun schemaFile(version: Int): File {
        val path = "schemas/com.ahmadnayfeh.shasha.data.db.ShashaDatabase/$version.json"
        return listOf(File(path), File("app/$path")).first { it.exists() }
    }
}
