package com.ahmadnayfeh.shasha.sponsorblock

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.db.SponsorSegmentsEntity
import com.google.common.truth.Truth.assertThat
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
 * A TV with a phase 3–5 build has a version 2 database. The build with the SponsorBlock cache must open it (a
 * changed schema under the same version would fail Room's check and crash), keep its rows, and add the table.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SponsorSegmentsMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun version2_opensAsVersion3_keepingItsRows_withTheSponsorCache() = runBlocking<Unit> {
        val file = context.getDatabasePath("migration-v2.db").apply { parentFile?.mkdirs(); delete() }
        createVersion2(file)

        val db = Room.databaseBuilder(context, ShashaDatabase::class.java, file.name).build()
        try {
            assertThat(db.profiles().get("adult")?.json).isEqualTo("{}")
            assertThat(db.subscriptions().get("UCkeptkeptkeptkeptkept00")?.name).isEqualTo("قناة")
            val dao = db.sponsorSegments()
            assertThat(dao.get("lC3h3Lm3q54")).isNull()
            dao.put(SponsorSegmentsEntity("lC3h3Lm3q54", 1, "[]"))
            assertThat(dao.get("lC3h3Lm3q54")?.segments).isEqualTo("[]")
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(3)
        } finally {
            db.close()
        }
    }

    /** The database exactly as app/schemas/…/2.json describes it, with a few rows. */
    private fun createVersion2(file: File) {
        val schema = Json.parseToJsonElement(schemaFile(2).readText()).jsonObject.getValue("database").jsonObject
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
            db.execSQL("INSERT INTO profiles (id, sortOrder, json) VALUES ('adult', 0, '{}')")
            db.version = 2
        }
    }

    /** Unit tests run in the module directory; the fallback covers a run from the project root. */
    private fun schemaFile(version: Int): File {
        val path = "schemas/com.ahmadnayfeh.shasha.data.db.ShashaDatabase/$version.json"
        return listOf(File(path), File("app/$path")).first { it.exists() }
    }
}
