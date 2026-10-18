package com.ahmadnayfeh.shasha.data

import androidx.room.withTransaction
import com.ahmadnayfeh.shasha.data.db.ProfileEntity
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.policy.PolicyJson
import com.ahmadnayfeh.shasha.policy.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

sealed interface DeleteProfileResult {
    data object Deleted : DeleteProfileResult

    /** Refused: the app always keeps an adult profile (the owner, who holds the parent PIN). */
    data object LastAdult : DeleteProfileResult

    data object NotFound : DeleteProfileResult
}

/**
 * The viewer profiles (SPEC §3), in the picker's order. Each row holds the whole [Profile] as
 * [PolicyJson]; a row that no longer decodes (a value out of range after a downgrade) is skipped rather
 * than hiding the others. There is always at least one adult: [ensureOwner] creates it on the first
 * start, [delete] and [upsert] refuse to remove the last one.
 *
 * Main-safe: Room runs the queries on its own threads.
 */
class ProfileRepository(private val db: ShashaDatabase) {
    private val dao = db.profiles()

    /** In the picker's order. */
    fun observeAll(): Flow<List<Profile>> = dao.observeAll().map { rows -> rows.mapNotNull(::decode) }.distinctUntilChanged()

    fun observe(id: String): Flow<Profile?> = observeAll().map { all -> all.firstOrNull { it.id == id } }.distinctUntilChanged()

    suspend fun all(): List<Profile> = dao.all().mapNotNull(::decode)

    suspend fun get(id: String): Profile? = dao.get(id)?.let(::decode)

    /**
     * Stores [profile]: a new one goes to the end of the picker, an existing one keeps its place.
     * @throws IllegalArgumentException when it would turn the last adult into a child.
     */
    suspend fun upsert(profile: Profile) {
        db.withTransaction { write(profile) }
    }

    /**
     * Changes a stored profile in one transaction (the TV and the phone may edit the same profile at
     * once: read-modify-write outside a transaction could lose one of the edits).
     * @return the stored result, or null when there is no such profile.
     * @throws IllegalArgumentException when [change] changes the id or turns the last adult into a child.
     */
    suspend fun update(id: String, change: (Profile) -> Profile): Profile? = db.withTransaction {
        val current = get(id) ?: return@withTransaction null
        val changed = change(current)
        require(changed.id == id) { "A profile's id cannot change ($id → ${changed.id})" }
        write(changed)
        changed
    }

    /**
     * Deletes a profile with everything that belongs to it (history, progress, watch later, saved playlists,
     * usage, surrenders). The last adult is never deleted.
     */
    suspend fun delete(id: String): DeleteProfileResult = db.withTransaction {
        val all = all()
        val target = all.firstOrNull { it.id == id } ?: return@withTransaction DeleteProfileResult.NotFound
        if (target.isAdult && all.none { it.isAdult && it.id != id }) return@withTransaction DeleteProfileResult.LastAdult
        dao.delete(id)
        dao.deleteHistory(id)
        dao.deleteWatchLater(id)
        dao.deleteProgress(id)
        dao.deleteSavedPlaylists(id)
        dao.deleteUsage(id)
        dao.deleteSurrenders(id)
        DeleteProfileResult.Deleted
    }

    /** Puts the profiles in the order of [ids]; profiles not listed keep their relative order after them. */
    suspend fun reorder(ids: List<String>) {
        db.withTransaction {
            val rows = dao.all()
            val ranked = rows.sortedBy { row -> ids.indexOf(row.id).let { if (it < 0) ids.size else it } }
            ranked.forEachIndexed { index, row -> if (row.sortOrder != index) dao.setSortOrder(row.id, index) }
        }
    }

    /**
     * The first start: when there is no adult yet, creates the owner's profile with [ownerId] (the id
     * phase 2 kept the history, progress and watch later under). Returns the first adult.
     */
    suspend fun ensureOwner(name: String, ownerId: String = ADULT_PROFILE_ID): Profile = db.withTransaction {
        all().firstOrNull { it.isAdult } ?: Profile.newAdult(name, id = ownerId).also { write(it) }
    }

    /** Inside a transaction. */
    private suspend fun write(profile: Profile) {
        val existing = dao.get(profile.id)
        if (profile.isChild) {
            val others = all().filter { it.id != profile.id }
            val wasAdult = existing?.let(::decode)?.isAdult == true
            require(!wasAdult || others.any { it.isAdult }) { "The last adult profile cannot become a child" }
        }
        val order = existing?.sortOrder ?: ((dao.maxSortOrder() ?: -1) + 1)
        dao.upsert(ProfileEntity(profile.id, order, PolicyJson.json.encodeToString(Profile.serializer(), profile)))
    }

    private fun decode(row: ProfileEntity): Profile? =
        PolicyJson.decodeOrNull(Profile.serializer(), row.json)?.takeIf { it.id == row.id }
}
