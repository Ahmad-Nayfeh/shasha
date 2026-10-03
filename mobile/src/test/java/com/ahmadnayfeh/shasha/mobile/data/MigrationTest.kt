package com.ahmadnayfeh.shasha.mobile.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.mobile.video
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
 * A phone that has the build of before the playlists installed has a version 1 database: this build must open it, keep every row
 * and add the playlists' tables (Room refuses to open a changed schema under the same version, and an auto migration whose
 * tables do not match the exported schema fails right here).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun version1_opensAsVersion2_keepingItsRows_andGainingThePlaylists() = runBlocking<Unit> {
        val file = context.getDatabasePath("migration.db").apply { parentFile?.mkdirs(); delete() }
        createVersion1(file)

        val db = Room.databaseBuilder(context, ShashaDatabase::class.java, file.name).build()
        try {
            assertThat(db.subscriptions().get("UCkeptkeptkeptkeptkept00")?.name).isEqualTo("قناة")
            assertThat(db.history().observe(10).first().map { it.videoId }).containsExactly("kept-history")
            assertThat(db.watchLater().contains("kept-later")).isTrue()
            assertThat(db.progress().get("kept-history")?.positionMs).isEqualTo(42_000)
            assertThat(db.searchHistory().observe(10).first().map { it.query }).containsExactly("قطط")
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(2)

            // The new tables work on that file: a list, a video in it, its order, and the cascade on delete.
            val playlists = UserPlaylistRepository(db.userPlaylists()) { 1L }
            val id = checkNotNull(playlists.create("قائمتي"))
            assertThat(playlists.add(id, video("a"))).isTrue()
            assertThat(playlists.add(id, video("b"))).isTrue()
            assertThat(playlists.videosNow(id).map { it.videoId }).containsExactly("a", "b").inOrder()
            playlists.delete(id)
            assertThat(db.userPlaylists().items(id)).isEmpty()
        } finally {
            db.close()
        }
    }

    @Test
    fun theExportedSchemasAreCommittedForEveryVersion() {
        val v1 = tables(schemaFile(1))
        val v2 = tables(schemaFile(2))

        assertThat(v1).doesNotContain("user_playlists")
        assertThat(v2).containsAtLeastElementsIn(v1)
        assertThat(v2).containsAtLeast("user_playlists", "user_playlist_items")
    }

    private fun tables(schema: File): List<String> =
        Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject
            .getValue("entities").jsonArray.map { it.jsonObject.getValue("tableName").jsonPrimitive.content }

    /** The database exactly as mobile/schemas/…/1.json describes it, with a row in some of its tables. */
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
            db.execSQL("INSERT INTO subscriptions (channelId, name, avatarUrl, subscribedAt) VALUES ('UCkeptkeptkeptkeptkept00', 'قناة', NULL, 1)")
            db.execSQL(
                "INSERT INTO history (videoId, title, channelId, channelName, thumbnailUrl, durationSeconds, watchedAt) " +
                    "VALUES ('kept-history', 'عنوان', NULL, NULL, NULL, 60, 1)",
            )
            db.execSQL(
                "INSERT INTO watch_later (videoId, title, channelId, channelName, thumbnailUrl, durationSeconds, addedAt) " +
                    "VALUES ('kept-later', 'عنوان', NULL, NULL, NULL, 60, 1)",
            )
            db.execSQL("INSERT INTO watch_progress (videoId, positionMs, durationMs, updatedAt) VALUES ('kept-history', 42000, 60000, 1)")
            db.execSQL("INSERT INTO search_history (`query`, searchedAt) VALUES ('قطط', 1)")
            db.version = 1
        }
    }

    /** Unit tests run in the module directory; the fallback covers a run from the project root. */
    private fun schemaFile(version: Int): File {
        val path = "schemas/com.ahmadnayfeh.shasha.mobile.data.db.ShashaDatabase/$version.json"
        return listOf(File(path), File("mobile/$path")).first { it.exists() }
    }
}
