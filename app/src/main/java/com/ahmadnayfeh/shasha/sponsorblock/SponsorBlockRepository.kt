package com.ahmadnayfeh.shasha.sponsorblock

import android.util.Log
import com.ahmadnayfeh.shasha.data.db.SponsorSegmentDao
import com.ahmadnayfeh.shasha.data.db.SponsorSegmentsEntity
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A video's SponsorBlock segments, fetched once and kept in Room ([SponsorSegmentDao]) for [ttlMs] (24 hours):
 * opening the video again in that time asks nobody. "No segments" is an answer and is kept too.
 *
 * Never fails: a video whose segments cannot be had plays without skipping. When SponsorBlock cannot be reached
 * an older answer (up to [keepMs]) is used rather than none, and nothing new is stored, so the next start asks
 * again.
 */
class SponsorBlockRepository(
    private val fetcher: SegmentFetcher,
    private val dao: SponsorSegmentDao,
    /** The TV's wall clock: what is stored has to survive restarts. */
    private val now: () -> Long,
    private val ttlMs: Long = TTL_MS,
    private val keepMs: Long = KEEP_MS,
) {
    /** How a lookup went, for the log and the tests. */
    sealed interface Lookup {
        val segments: List<SponsorSegment>

        /** Answered from the cache, [ageMs] old. */
        data class Cached(override val segments: List<SponsorSegment>, val ageMs: Long) : Lookup

        /** Asked SponsorBlock now (and stored). */
        data class Fetched(override val segments: List<SponsorSegment>) : Lookup

        /** SponsorBlock could not be asked; [segments] are an older answer, or none. */
        data class Failed(override val segments: List<SponsorSegment>, val error: Exception) : Lookup
    }

    suspend fun segments(videoId: String): List<SponsorSegment> = lookup(videoId).segments

    suspend fun lookup(videoId: String): Lookup {
        val stored = try {
            dao.get(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reading the cache failed", e)
            null
        }
        val cached = stored?.let { SegmentCache.decode(it.segments) }
        val asked = now()
        val age = stored?.let { asked - it.fetchedAtMs }
        // An answer "from the future" means the clock went back: it is not trusted to be fresh.
        if (cached != null && age != null && age in 0 until ttlMs) return Lookup.Cached(cached, age)

        val fetched = try {
            fetcher.fetch(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "SponsorBlock unavailable for this video: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            val fallback = cached?.takeIf { age != null && age in 0 until keepMs }.orEmpty()
            return Lookup.Failed(fallback, e)
        }
        try {
            dao.put(SponsorSegmentsEntity(videoId, asked, SegmentCache.encode(fetched)))
            dao.prune(oldestMs = asked - keepMs, newestMs = asked + ttlMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Writing the cache failed", e)
        }
        return Lookup.Fetched(fetched)
    }

    companion object {
        /** SPEC: reuse for 24 hours. */
        const val TTL_MS = 24 * 60 * 60 * 1000L

        /** An older answer still beats none when SponsorBlock is down; older rows are deleted. */
        const val KEEP_MS = 7 * TTL_MS

        private const val TAG = "SponsorBlock"
    }
}

/** The cache's own JSON for a list of segments: stable keys, categories and actions by their API names. */
internal object SegmentCache {
    @Serializable
    private data class Entry(val id: String, val category: String, val action: String, val startMs: Long, val endMs: Long, val videoDurationMs: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Entry.serializer())

    fun encode(segments: List<SponsorSegment>): String =
        json.encodeToString(serializer, segments.map { Entry(it.id, it.category.apiKey, it.action.apiKey, it.startMs, it.endMs, it.videoDurationMs) })

    /** Null when [text] cannot be read (then SponsorBlock is asked again); entries of unknown kinds are skipped. */
    fun decode(text: String): List<SponsorSegment>? = try {
        json.decodeFromString(serializer, text).mapNotNull { entry ->
            val category = SponsorCategory.fromApiKey(entry.category) ?: return@mapNotNull null
            val action = SegmentAction.fromApiKey(entry.action) ?: return@mapNotNull null
            SponsorSegment(entry.id, category, action, entry.startMs, entry.endMs, entry.videoDurationMs).takeIf { it.endMs > it.startMs }
        }
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
