package com.ahmadnayfeh.shasha.backup

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.Behavior
import com.ahmadnayfeh.shasha.data.BehaviorKeys
import com.ahmadnayfeh.shasha.data.BehaviorSettings
import com.ahmadnayfeh.shasha.data.OverridesRepository
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.PinResult
import com.ahmadnayfeh.shasha.data.PolicyStore
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.TestDataStores
import com.ahmadnayfeh.shasha.data.UsageRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.cancelAndWait
import com.ahmadnayfeh.shasha.data.testScope
import com.ahmadnayfeh.shasha.data.FixedProfile
import com.ahmadnayfeh.shasha.data.db.FeedItemEntity
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.policy.ALLOWED_CHANNEL
import com.ahmadnayfeh.shasha.policy.Blocklist
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.Overrides
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.PolicyJson
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.QuotaOverride
import com.ahmadnayfeh.shasha.policy.ScheduleOverride
import com.ahmadnayfeh.shasha.policy.SurrenderEvent
import com.ahmadnayfeh.shasha.policy.clip
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.policy.kid
import com.ahmadnayfeh.shasha.policy.owner
import com.ahmadnayfeh.shasha.remote.RemoteFakeSource
import com.ahmadnayfeh.shasha.remote.RemoteSettings
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * SPEC: «نسخة احتياطية JSON يدوية». Everything the TV keeps goes out in one JSON file and comes back the
 * same, on another (wiped) TV too: export → wipe → import → export again gives the same file.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class BackupManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = MutableClock(instantAt("2026-09-28T12:00"))
    private val engine = PolicyEngine(clock)
    private val tvs = mutableListOf<Tv>()

    /** One TV's storage: its database, its DataStore files and its backup folder. */
    private inner class Tv(name: String) {
        val dir = tmp.newFolder(name)
        val db: ShashaDatabase = inMemoryDatabase()
        val stores = TestDataStores(dir)
        val policy: DataStore<Preferences> = stores.open()
        val remote: DataStore<Preferences> = stores.open(RemoteSettings.FILE_NAME)
        val behaviorStore: DataStore<Preferences> = stores.open(BehaviorSettings.FILE_NAME)
        val restored = mutableListOf<RestoreReport>()
        val backup = BackupManager(
            db = db,
            policyStore = policy,
            sections = listOf(RemoteSettings.backupSection(remote), BehaviorSettings.backupSection(behaviorStore)),
            clock = clock,
            appVersion = "test",
            filesDir = { dir },
            afterRestore = { restored += it },
        )
        val profiles = ProfileRepository(db)
        val pin = ParentPinRepository(policy, clock, PinHasher(iterations = 1_000))
        val overrides = OverridesRepository(policy)
        val usage = UsageRepository(db)
        val remoteSettings = RemoteSettings(remote)
        val scope = testScope()
        val behavior = BehaviorSettings(behaviorStore, scope)

        fun watch(profileId: String) = WatchRepository(db, FixedProfile(profileId), clock = { clock.millis() })

        fun close() {
            scope.cancelAndWait()
            stores.closeAll()
            db.close()
        }
    }

    private fun tv(name: String) = Tv(name).also { tvs += it }

    @After
    fun tearDown() = tvs.forEach { it.close() }

    /** A TV with a bit of everything. */
    private suspend fun Tv.fill() {
        profiles.ensureOwner(owner().name)
        profiles.upsert(kid().copy(blocklist = Blocklist(titleKeywords = listOf("رعب"))))
        profiles.update("adult") { it.copy(blocklist = Blocklist(titleKeywords = listOf("رعب"))) }
        pin.setup("1357")
        val today = engine.today()
        overrides.add(
            Overrides(
                quota = listOf(QuotaOverride("kid", today, extraSeconds = 900)),
                schedule = listOf(ScheduleOverride("kid", clock.millis() + 3_600_000)),
            ),
            today,
            clock.millis(),
        )
        val subs = SubscriptionRepository(db, RemoteFakeSource(), clock = { clock.millis() })
        subs.add(ChannelItem(ALLOWED_CHANNEL, "قناة مسموحة", "https://yt3.ggpht.com/a", null))
        subs.add(ChannelItem("UCdocs000000000000000000", "Docs", null, null))
        watch("adult").apply {
            addToWatchLater(clip("later000001", channelId = "UCdocs000000000000000000", title = "For later"))
            addToHistory(clip("seen0000001", channelId = "UCdocs000000000000000000", title = "Seen"))
            saveProgress("seen0000001", positionMs = 120_000, durationMs = 600_000)
        }
        watch("kid").apply {
            addToHistory(clip("kidseen0001", title = "Cartoon"))
            saveProgress("kidseen0001", positionMs = 30_000, durationMs = 300_000)
        }
        usage.addForeground("kid", today, 1_800)
        usage.addPlayback("kid", today, 1_500, ALLOWED_CHANNEL, "قناة مسموحة")
        usage.addForeground("adult", today - 1, 3_600)
        usage.recordSurrender(SurrenderEvent("adult", clock.millis() - 60_000, "seen0000001"))
        remoteSettings.setEnabled(false)
        remoteSettings.rememberPort(8766)
        behavior.setSeriousMode(false)
        behavior.setNumber(BehaviorKeys.WARNING_MINUTES, 10)
        behavior.setDefaultSpeed(1.25f)
        behavior.addPhrase("وقتي لعملي")
    }

    private suspend fun Tv.behaviorNow(): Behavior {
        behavior.awaitLoaded()
        return BehaviorSettings.decode(behaviorStore.data.first())
    }

    private fun ByteArray.json(): JsonObject = Json.parseToJsonElement(decodeToString()).jsonObject

    /** The file without its export time: what must match after a round trip. */
    private fun ByteArray.content(): JsonObject = JsonObject(json() - "exportedAtMs")

    @Test
    fun exportWipeImport_givesBackTheSameTv() = runBlocking<Unit> {
        val first = tv("first").apply { fill() }
        val token = first.remoteSettings.token()
        val exported = first.backup.export()

        val file = exported.json()
        assertThat(file["app"].toString()).isEqualTo("\"shasha\"")
        assertThat(file["format"].toString()).isEqualTo("2")
        assertThat(file["settings"]!!.jsonObject.keys).contains(BehaviorSettings.FILE_NAME)
        // The PIN's hash travels (a restore keeps the same PIN), never the PIN; the pairing token never.
        assertThat(exported.decodeToString()).contains("pbkdf2$")
        assertThat(exported.decodeToString()).doesNotContain("1357")
        assertThat(exported.decodeToString()).doesNotContain(token)

        // A wiped TV (nothing but a first start's owner and another PIN) takes it all back.
        val second = tv("second")
        second.profiles.ensureOwner("أنا")
        second.pin.setup("9999")
        val report = second.backup.import(exported)
        assertThat(report).isEqualTo(RestoreReport(profiles = 2, subscriptions = 2, skipped = 0, pinChanged = true))
        assertThat(second.restored).containsExactly(report)

        assertThat(second.backup.export().content()).isEqualTo(exported.content())
        assertThat(second.pin.verify("1357")).isEqualTo(PinResult.Success)
        assertThat(second.profiles.all().map { it.id }).containsExactly("adult", "kid").inOrder()
        assertThat(second.profiles.get("kid")).isEqualTo(first.profiles.get("kid"))
        assertThat(second.overrides.current()).isEqualTo(first.overrides.current())
        assertThat(second.usage.foregroundSeconds("kid", engine.today())).isEqualTo(1_800)
        assertThat(second.usage.surrenders("adult", 0)).hasSize(1)
        assertThat(second.watch("adult").observeWatchLater().first().map { it.videoId }).containsExactly("later000001")
        assertThat(second.watch("kid").resumePositionMs("kidseen0001")).isEqualTo(30_000)
        assertThat(second.remoteSettings.enabled.first()).isFalse()
        assertThat(second.remoteSettings.preferredPort()).isEqualTo(8766)
        // v1.1's settings (format 2) come back too.
        val restoredBehavior = second.behaviorNow()
        assertThat(restoredBehavior).isEqualTo(first.behaviorNow())
        assertThat(restoredBehavior.seriousMode).isFalse()
        assertThat(restoredBehavior.warningMinutes).isEqualTo(10)
        assertThat(restoredBehavior.defaultSpeed).isEqualTo(1.25f)
        assertThat(restoredBehavior.phrases).contains("وقتي لعملي")
        // The second TV keeps its own pairing: its phones are paired again from its QR code.
        assertThat(second.remoteSettings.token()).isNotEqualTo(token)
    }

    @Test
    fun aFormatOneFile_stillRestores_andLeavesTheNewSettingsAsTheyAre() = runBlocking<Unit> {
        val first = tv("first").apply { fill() }
        // What v1.0 wrote: format 1, no "behavior" section.
        val file = first.backup.export().json()
        val settings = JsonObject(file["settings"]!!.jsonObject - BehaviorSettings.FILE_NAME)
        val old = JsonObject(file + ("format" to JsonPrimitive(1)) + ("settings" to settings)).toString().toByteArray()

        val second = tv("second")
        second.profiles.ensureOwner("أنا")
        second.behavior.setNumber(BehaviorKeys.SEEK_STEP_SECONDS, 30)
        val report = second.backup.import(old)
        assertThat(report.profiles).isEqualTo(2)
        assertThat(second.pin.verify("1357")).isEqualTo(PinResult.Success)
        assertThat(second.remoteSettings.preferredPort()).isEqualTo(8766)
        // Not in the file: this TV's own choices stay.
        val kept = second.behaviorNow()
        assertThat(kept.seekStepSeconds).isEqualTo(30)
        assertThat(kept.seriousMode).isTrue()
    }

    @Test
    fun aFormatTwoFile_withoutTheSection_bringsTheDefaultsBack() = runBlocking<Unit> {
        val first = tv("first").apply { fill() }
        val file = first.backup.export().json()
        val settings = JsonObject(file["settings"]!!.jsonObject - BehaviorSettings.FILE_NAME)
        first.backup.import(JsonObject(file + ("settings" to settings)).toString().toByteArray())
        assertThat(first.behaviorNow()).isEqualTo(Behavior.DEFAULT)
    }

    @Test
    fun aRestore_replacesEverything_andDropsTheFeedOfChannelsItTakesAway() = runBlocking<Unit> {
        val first = tv("first").apply { fill() }
        val exported = first.backup.export()
        // Meanwhile this TV changed: a new child, another subscription with videos in its feed.
        first.profiles.upsert(kid(id = "kid2", name = "يوسف"))
        first.db.subscriptions().insertAll(listOf(SubscriptionEntity("UCextra00000000000000000", "Extra", null, 0)))
        first.db.feed().upsert(listOf(feedItem("extra000001", "UCextra00000000000000000"), feedItem("docs0000001", "UCdocs000000000000000000")))

        first.backup.import(exported)
        assertThat(first.profiles.all().map { it.id }).containsExactly("adult", "kid").inOrder()
        assertThat(first.db.backup().subscriptions().map { it.channelId }).containsExactly(ALLOWED_CHANNEL, "UCdocs000000000000000000")
        assertThat(first.db.feed().forChannel("UCextra00000000000000000")).isEmpty()
        assertThat(first.db.feed().forChannel("UCdocs000000000000000000").map { it.videoId }).containsExactly("docs0000001")
        assertThat(first.backup.export().content()).isEqualTo(exported.content())
    }

    @Test
    fun whatIsNotABackup_changesNothing() = runBlocking<Unit> {
        val tv = tv("tv").apply { fill() }
        val before = tv.backup.export().content()
        fun problem(bytes: String) = assertThrows(BackupException::class.java) { runBlocking { tv.backup.import(bytes.toByteArray()) } }.problem

        assertThat(problem("not json at all")).isEqualTo(BackupProblem.NOT_A_BACKUP)
        assertThat(problem("""{"app":"other","format":1}""")).isEqualTo(BackupProblem.NOT_A_BACKUP)
        assertThat(problem("""{"app":"shasha","format":3,"profiles":[]}""")).isEqualTo(BackupProblem.NEWER_VERSION)
        val childOnly = """{"app":"shasha","format":1,"profiles":[${PolicyJsonFor.kid()}]}"""
        assertThat(problem(childOnly)).isEqualTo(BackupProblem.NO_ADULT)
        assertThat(tv.backup.export().content()).isEqualTo(before)
    }

    @Test
    fun badItems_areLeftOut_andCounted() = runBlocking<Unit> {
        val tv = tv("tv").apply { fill() }
        val file = tv.backup.export().json().toMutableMap()
        // A history row of a profile the file does not have, and one that is not a row at all.
        val history = (file["history"] as JsonArray).toMutableList()
        history += Json.parseToJsonElement("""{"profileId":"ghost","videoId":"x","title":"x","atMs":1}""")
        history += Json.parseToJsonElement("""{"videoId":3}""")
        file["history"] = JsonArray(history)
        val report = tv.backup.import(JsonObject(file).toString().toByteArray())
        assertThat(report.skipped).isEqualTo(2)
        assertThat(report.pinChanged).isFalse() // the same hash as the TV already had
    }

    @Test
    fun aPinHashThatNoPinCouldMatch_isLeftOut_andTheTvKeepsItsPin() = runBlocking<Unit> {
        val tv = tv("tv").apply { fill() }
        val file = tv.backup.export().json()
        val (prefix, iterations, salt, key) = (file["parentPinHash"] as JsonPrimitive).content.split('$')
        assertThat(prefix).isEqualTo("pbkdf2")
        assertThat(BackupManager.isUsablePinHash("pbkdf2$$iterations$$salt$$key")).isTrue()
        val unusable = listOf(
            "pbkdf2$0$$salt$$key", // no iterations at all
            "pbkdf2$99999999$$salt$$key", // more than PinHasher ever derives (its cap: 10 million)
            "pbkdf2$$iterations$$salt\$AAAA", // a 3-byte key: PBKDF2 derives 32 bytes
            "pbkdf2$$iterations$$salt$${key.dropLast(4)}", // cut short
        )
        for (hash in unusable) {
            assertThat(BackupManager.isUsablePinHash(hash)).isFalse()
            tv.profiles.update("kid") { it.copy(name = "تغيّر") }
            val report = tv.backup.import(JsonObject(file + ("parentPinHash" to JsonPrimitive(hash))).toString().toByteArray())
            // The rest of the file is restored; the hash is counted as left out; the PIN still opens everything.
            assertThat(tv.profiles.get("kid")!!.name).isEqualTo("سارة")
            assertThat(report.skipped).isEqualTo(1)
            assertThat(report.pinChanged).isFalse()
            assertThat(tv.pin.verify("1357")).isEqualTo(PinResult.Success)
        }
        // No hash at all (a TV that had no PIN yet) is not an error: the PIN stays too.
        val noHash = tv.backup.import(JsonObject(file + ("parentPinHash" to JsonNull)).toString().toByteArray())
        assertThat(noHash.skipped).isEqualTo(0)
        assertThat(tv.pin.verify("1357")).isEqualTo(PinResult.Success)
    }

    @Test
    fun theTvsFolder_exportAndImportFiles() = runBlocking<Unit> {
        val tv = tv("tv").apply { fill() }
        val file = tv.backup.exportToFile()
        assertThat(file.parentFile).isEqualTo(File(tv.dir, "backup"))
        assertThat(file.name).isEqualTo("shasha-backup-2026-09-28.json")
        assertThat(tv.backup.listFiles().map { it.name }).containsExactly("shasha-backup-2026-09-28.json")
        tv.profiles.update("kid") { it.copy(name = "تغيّر") }
        tv.backup.importFile(tv.backup.listFiles().single().file)
        assertThat(tv.profiles.get("kid")!!.name).isEqualTo("سارة")
        // The same day's export replaces the file; nothing else is left in the folder.
        tv.backup.exportToFile()
        assertThat(File(tv.dir, "backup").list()!!.toList()).containsExactly("shasha-backup-2026-09-28.json")
    }

    private fun feedItem(videoId: String, channelId: String) = FeedItemEntity(
        videoId = videoId,
        channelId = channelId,
        channelName = "n",
        title = videoId,
        publishedAtMs = clock.millis(),
        thumbnailUrl = null,
        durationSeconds = 100,
        isShort = false,
        isLive = false,
        url = "https://www.youtube.com/watch?v=$videoId",
        fetchedAtMs = clock.millis(),
    )

    private object PolicyJsonFor {
        fun kid(): String = PolicyJson.json.encodeToString(Profile.serializer(), com.ahmadnayfeh.shasha.policy.kid())
    }
}
