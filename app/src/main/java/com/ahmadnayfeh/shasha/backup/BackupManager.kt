package com.ahmadnayfeh.shasha.backup

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import com.ahmadnayfeh.shasha.data.PolicyStore
import com.ahmadnayfeh.shasha.data.db.ProfileEntity
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.policy.Overrides
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.PolicyJson
import com.ahmadnayfeh.shasha.policy.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.util.Base64

/** Why a backup was not restored (nothing changed). */
enum class BackupProblem { NOT_A_BACKUP, NEWER_VERSION, NO_ADULT, TOO_LARGE, FAILED }

class BackupException(val problem: BackupProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/** What a restore brought back; [skipped] items were invalid and left out. */
data class RestoreReport(val profiles: Int, val subscriptions: Int, val skipped: Int, val pinChanged: Boolean)

/** A backup file in the app's folder ([BackupManager.backupDir]). */
data class BackupFile(val file: File, val sizeBytes: Long, val modifiedAtMs: Long) {
    val name: String get() = file.name
}

/**
 * SPEC: «نسخة احتياطية JSON يدوية». Everything the TV keeps, in one versioned JSON file ([FORMAT]):
 * the profiles (their rules, allowlists and blocklists), the parent PIN's hash (never the PIN: a restore
 * keeps the same PIN), the parent's time grants, the subscriptions, watch later, the history, the resume
 * positions, the usage stats and surrenders, and the settings [sections]. Not the feed (fetched again), not
 * the playback lock, the PIN lockout or the pairing token (a restored TV is paired again).
 *
 * [import] checks the whole file first (its app and format; at least one adult profile: a file that fails
 * changes nothing), then replaces the tables in ONE Room transaction, then writes the PIN hash and grants in
 * one DataStore edit and the settings. Invalid items are left out and counted, never the whole file: a PIN
 * hash that no PIN could match too (the TV keeps its PIN).
 * [afterRestore] runs once it is all written (the feed sync, logging the phones out of the parent section
 * when the PIN changed).
 *
 * The same bytes go to the phone ([export]) and to a file in the app's folder ([exportToFile], retrievable
 * with `adb pull`), and come back from either.
 */
class BackupManager(
    private val db: ShashaDatabase,
    private val policyStore: DataStore<Preferences>,
    private val sections: List<SettingsSection>,
    private val clock: Clock,
    private val appVersion: String,
    /** The app's external files folder (null when the TV has no external storage). */
    private val filesDir: () -> File?,
    private val afterRestore: suspend (RestoreReport) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao = db.backup()

    /** `<external files>/backup/`, where [exportToFile] writes and [listFiles] looks. */
    val backupDir: File? get() = filesDir()?.let { File(it, DIR) }

    /** The whole backup, UTF-8 JSON. */
    suspend fun export(): ByteArray {
        val tables = db.withTransaction {
            Tables(
                profiles = dao.profiles(),
                subscriptions = dao.subscriptions().map(SubscriptionRow::of),
                watchLater = dao.watchLater().map(SavedVideoRow::of),
                history = dao.history().map(SavedVideoRow::of),
                progress = dao.progress().map(ProgressRow::of),
                usage = dao.usage().map(UsageRow::of),
                surrenders = dao.surrenders().map(SurrenderRow::of),
            )
        }
        val policy = policyStore.data.first()
        val overrides = policy[PolicyStore.OVERRIDES]?.let { PolicyJson.decodeOrNull(Overrides.serializer(), it) } ?: Overrides.NONE
        val settings = sections.associate { it.name to JsonObject(it.export()) }.toSortedMap()
        val root = buildJsonObject {
            put(KEY_APP, APP)
            put(KEY_FORMAT, FORMAT)
            put(KEY_EXPORTED_AT, clock.millis())
            put(KEY_APP_VERSION, appVersion)
            // Profiles in the picker's order; a row that no longer decodes (see ProfileRepository) is left out.
            put(KEY_PROFILES, JsonArray(tables.profiles.mapNotNull { row -> decodeProfile(row)?.let { encode(Profile.serializer(), it) } }))
            put(KEY_PIN_HASH, policy[PolicyStore.PARENT_PIN_HASH]?.let(::JsonPrimitive) ?: JsonNull)
            put(KEY_OVERRIDES, encode(Overrides.serializer(), overrides))
            put(KEY_SUBSCRIPTIONS, list(SubscriptionRow.serializer(), tables.subscriptions))
            put(KEY_WATCH_LATER, list(SavedVideoRow.serializer(), tables.watchLater))
            put(KEY_HISTORY, list(SavedVideoRow.serializer(), tables.history))
            put(KEY_PROGRESS, list(ProgressRow.serializer(), tables.progress))
            put(KEY_USAGE, list(UsageRow.serializer(), tables.usage))
            put(KEY_SURRENDERS, list(SurrenderRow.serializer(), tables.surrenders))
            put(KEY_SETTINGS, JsonObject(settings))
        }
        return withContext(Dispatchers.Default) { FileJson.encodeToString(JsonObject.serializer(), root).toByteArray(Charsets.UTF_8) }
    }

    /**
     * Replaces everything with the backup in [bytes].
     * @throws BackupException when the file is not a usable backup, or writing it failed (nothing changed then).
     */
    suspend fun import(bytes: ByteArray): RestoreReport {
        if (bytes.size > MAX_BYTES) throw BackupException(BackupProblem.TOO_LARGE)
        val backup = withContext(Dispatchers.Default) { parse(bytes) }
        val oldHash = policyStore.data.first()[PolicyStore.PARENT_PIN_HASH]
        try {
            db.withTransaction {
                dao.deleteSurrenders()
                dao.deleteUsage()
                dao.deleteProgress()
                dao.deleteHistory()
                dao.deleteWatchLater()
                dao.deleteSubscriptions()
                dao.deleteProfiles()
                dao.insertProfiles(backup.profiles.mapIndexed { index, profile -> ProfileEntity(profile.id, index, encodeProfile(profile)) })
                dao.insertSubscriptions(backup.subscriptions.map { it.toEntity() })
                dao.deleteFeedOfOtherChannels()
                dao.insertWatchLater(backup.watchLater.map { it.toWatchLater() })
                dao.insertHistory(backup.history.map { it.toHistory() })
                dao.insertProgress(backup.progress.map { it.toEntity() })
                dao.insertUsage(backup.usage.map { it.toEntity() })
                dao.insertSurrenders(backup.surrenders.map { it.toEntity() })
            }
            policyStore.edit { prefs ->
                // No hash in the file (a TV without a PIN yet): the PIN stays as it is, never removed.
                backup.pinHash?.let { prefs[PolicyStore.PARENT_PIN_HASH] = it }
                val grants = backup.overrides.pruned(LocalDate.now(clock).toEpochDay(), clock.millis())
                if (grants == Overrides.NONE) {
                    prefs.remove(PolicyStore.OVERRIDES)
                } else {
                    prefs[PolicyStore.OVERRIDES] = PolicyJson.json.encodeToString(Overrides.serializer(), grants)
                }
            }
            for (section in sections) {
                val values = backup.settings[section.name]
                // A file older than the section never had it: those settings stay as they are.
                if (values == null && backup.format < section.sinceFormat) continue
                section.restore(values.orEmpty())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(BackupProblem.FAILED, e)
        }
        val report = RestoreReport(
            profiles = backup.profiles.size,
            subscriptions = backup.subscriptions.size,
            skipped = backup.skipped,
            pinChanged = backup.pinHash != null && backup.pinHash != oldHash,
        )
        try {
            afterRestore(report)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The restore itself is done; what follows (a sync) catches up later.
            Log.w(TAG, "After the restore", e)
        }
        return report
    }

    /** Writes the backup to [backupDir]/shasha-backup-<date>.json (the same day's file is replaced). */
    suspend fun exportToFile(): File {
        val dir = backupDir ?: throw IOException("No external storage")
        val bytes = export()
        return withContext(io) {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
            val target = File(dir, fileName(LocalDate.now(clock)))
            val partial = File(dir, target.name + ".part")
            partial.writeBytes(bytes)
            if (!partial.renameTo(target)) {
                partial.delete()
                throw IOException("Cannot write $target")
            }
            target
        }
    }

    /** The backups in [backupDir], newest first. */
    suspend fun listFiles(): List<BackupFile> = withContext(io) {
        val dir = backupDir ?: return@withContext emptyList()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }.orEmpty()
            .map { BackupFile(it, it.length(), it.lastModified()) }
            .sortedWith(compareByDescending<BackupFile> { it.modifiedAtMs }.thenBy { it.name })
    }

    /** Restores [file] (one of [listFiles]). @throws BackupException, [IOException] when it cannot be read. */
    suspend fun importFile(file: File): RestoreReport {
        val bytes = withContext(io) {
            if (file.length() > MAX_BYTES) throw BackupException(BackupProblem.TOO_LARGE)
            file.readBytes()
        }
        return import(bytes)
    }

    private class Tables(
        val profiles: List<ProfileEntity>,
        val subscriptions: List<SubscriptionRow>,
        val watchLater: List<SavedVideoRow>,
        val history: List<SavedVideoRow>,
        val progress: List<ProgressRow>,
        val usage: List<UsageRow>,
        val surrenders: List<SurrenderRow>,
    )

    /** A checked backup, ready to write. */
    private class Parsed(
        val profiles: List<Profile>,
        val pinHash: String?,
        val overrides: Overrides,
        val subscriptions: List<SubscriptionRow>,
        val watchLater: List<SavedVideoRow>,
        val history: List<SavedVideoRow>,
        val progress: List<ProgressRow>,
        val usage: List<UsageRow>,
        val surrenders: List<SurrenderRow>,
        val settings: Map<String, Map<String, JsonElement>>,
        val skipped: Int,
        val format: Int,
    )

    private fun parse(bytes: ByteArray): Parsed {
        val root = try {
            FileJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: IllegalArgumentException) { // SerializationException included
            null
        } ?: throw BackupException(BackupProblem.NOT_A_BACKUP)
        if ((root[KEY_APP] as? JsonPrimitive)?.contentOrNullIfString() != APP) throw BackupException(BackupProblem.NOT_A_BACKUP)
        val format = (root[KEY_FORMAT] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: throw BackupException(BackupProblem.NOT_A_BACKUP)
        if (format > FORMAT) throw BackupException(BackupProblem.NEWER_VERSION)
        if (format < 1) throw BackupException(BackupProblem.NOT_A_BACKUP)

        var skipped = 0
        fun <T> items(key: String, serializer: KSerializer<T>, valid: (T) -> Boolean): List<T> {
            val array = root[key] as? JsonArray ?: return emptyList()
            return array.mapNotNull { element ->
                val item = try {
                    PolicyJson.json.decodeFromJsonElement(serializer, element)
                } catch (e: IllegalArgumentException) {
                    null
                }
                item?.takeIf(valid).also { if (it == null) skipped++ }
            }
        }

        // Profiles: the first of an id wins; the backup must bring the adult who holds the PIN.
        val seen = HashSet<String>()
        val profiles = items(KEY_PROFILES, Profile.serializer()) { it.id.isNotBlank() }.filter { seen.add(it.id) }
        if (profiles.none { it.isAdult }) throw BackupException(BackupProblem.NO_ADULT)
        val ids = profiles.mapTo(HashSet()) { it.id }

        // A hash that no PIN could ever match (a damaged or edited file) would lock the parent out of every PIN
        // check, on the TV and the phone, until the app's data is cleared: it is left out and the PIN stays.
        val pinHashValue = root[KEY_PIN_HASH]?.takeUnless { it is JsonNull }
        val pinHash = (pinHashValue as? JsonPrimitive)?.contentOrNullIfString()?.takeIf { isUsablePinHash(it) }
        if (pinHashValue != null && pinHash == null) skipped++
        val overrides = root[KEY_OVERRIDES]?.takeUnless { it is JsonNull }?.let { element ->
            try {
                PolicyJson.json.decodeFromJsonElement(Overrides.serializer(), element)
            } catch (e: IllegalArgumentException) {
                skipped++
                null
            }
        } ?: Overrides.NONE
        val settings = (root[KEY_SETTINGS] as? JsonObject)
            ?.mapNotNull { (name, value) -> (value as? JsonObject)?.let { name to it.toMap() } }
            ?.toMap()
            ?: emptyMap()
        return Parsed(
            profiles = profiles,
            pinHash = pinHash,
            overrides = Overrides(overrides.quota.filter { it.profileId in ids }, overrides.schedule.filter { it.profileId in ids }),
            subscriptions = items(KEY_SUBSCRIPTIONS, SubscriptionRow.serializer()) { it.valid }.distinctBy { it.channelId },
            watchLater = items(KEY_WATCH_LATER, SavedVideoRow.serializer()) { it.valid && it.profileId in ids },
            history = items(KEY_HISTORY, SavedVideoRow.serializer()) { it.valid && it.profileId in ids },
            progress = items(KEY_PROGRESS, ProgressRow.serializer()) { it.valid && it.profileId in ids },
            usage = items(KEY_USAGE, UsageRow.serializer()) { it.valid && it.profileId in ids },
            surrenders = items(KEY_SURRENDERS, SurrenderRow.serializer()) { it.valid && it.profileId in ids },
            settings = settings,
            skipped = skipped,
            format = format,
        )
    }

    private fun decodeProfile(row: ProfileEntity): Profile? =
        PolicyJson.decodeOrNull(Profile.serializer(), row.json)?.takeIf { it.id == row.id }

    private fun encodeProfile(profile: Profile): String = PolicyJson.json.encodeToString(Profile.serializer(), profile)

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement = PolicyJson.json.encodeToJsonElement(serializer, value)

    private fun <T> list(serializer: KSerializer<T>, rows: List<T>): JsonArray = JsonArray(rows.map { encode(serializer, it) })

    private fun JsonPrimitive.contentOrNullIfString(): String? = if (isString) content else null

    companion object {
        private const val TAG = "BackupManager"

        const val APP = "shasha"

        /**
         * Bump with every change a previous app could not read; [import] refuses newer ones and reads every older
         * one. 2: the settings gained the "behavior" section (Serious Mode, timings, the PIN's brake…). 3 (v1.2):
         * «الوضع الجاد» is two switches, the children's and the adult's; a format-2 file's single switch sets both
         * (BehaviorSettings.backupSection).
         */
        const val FORMAT = 3

        /** What the phone may upload (:remote's limit) and more than years of history take. */
        const val MAX_BYTES = 10 * 1024 * 1024

        const val DIR = "backup"

        fun fileName(date: LocalDate): String = "shasha-backup-$date.json"

        private const val KEY_APP = "app"
        private const val KEY_FORMAT = "format"
        private const val KEY_EXPORTED_AT = "exportedAtMs"
        private const val KEY_APP_VERSION = "appVersion"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_PIN_HASH = "parentPinHash"
        private const val KEY_OVERRIDES = "overrides"
        private const val KEY_SUBSCRIPTIONS = "subscriptions"
        private const val KEY_WATCH_LATER = "watchLater"
        private const val KEY_HISTORY = "history"
        private const val KEY_PROGRESS = "progress"
        private const val KEY_USAGE = "usage"
        private const val KEY_SURRENDERS = "surrenders"
        private const val KEY_SETTINGS = "settings"

        /** PinHasher's shape: pbkdf2$<iterations>$<salt>$<hash>. */
        private val PIN_HASH = Regex("""pbkdf2\$\d{1,8}\$[A-Za-z0-9+/=]{4,}\$[A-Za-z0-9+/=]{4,}""")

        /** Only its parser is used ([isUsablePinHash]): it never hashes anything. */
        private val hashParser by lazy { PinHasher(iterations = 1) }

        /**
         * Whether [PinHasher] can ever verify [encoded]: its own parser takes it (an iteration count within its
         * bounds, base64 salt and key) and the key is as long as the one PBKDF2 derives.
         */
        internal fun isUsablePinHash(encoded: String): Boolean {
            if (!PIN_HASH.matches(encoded)) return false
            // False only for a hash the parser reads (this hasher's own count, 1, is never above a stored one).
            if (hashParser.needsRehash(encoded)) return false
            val key = try {
                Base64.getDecoder().decode(encoded.substringAfterLast('$'))
            } catch (e: IllegalArgumentException) {
                return false
            }
            return key.size == PinHasher.KEY_BITS / 8
        }

        private val FileJson = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
