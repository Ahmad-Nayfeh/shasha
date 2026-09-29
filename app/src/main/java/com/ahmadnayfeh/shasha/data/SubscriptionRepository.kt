package com.ahmadnayfeh.shasha.data

import androidx.room.withTransaction
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.data.takeout.TakeoutCsvParser
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.YouTubeLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** @property invalid rows of the file that held no channel id (the header is not counted). */
data class ImportResult(val added: Int, val alreadyPresent: Int, val invalid: Int)

sealed interface AddResult {
    data class Added(val channel: SubscriptionEntity) : AddResult

    data class AlreadySubscribed(val channel: SubscriptionEntity) : AddResult

    /** The text is not a channel link or id (e.g. a video or playlist link). */
    data object NotAChannel : AddResult
}

/**
 * The local subscriptions (SPEC §2: no account; imported once from Takeout, then added and removed on
 * the TV or from the phone). Main-safe: Room runs the queries on its own threads.
 *
 * @param onChannelsAdded called after channels were added, to fetch their videos (AppContainer wires
 *   it to FeedSyncScheduler.syncNow()).
 */
class SubscriptionRepository(
    private val db: ShashaDatabase,
    private val source: VideoSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChannelsAdded: () -> Unit = {},
) {
    private val dao = db.subscriptions()

    /** By name. */
    fun observe(): Flow<List<SubscriptionEntity>> = dao.observeAll()

    fun observeIsSubscribed(channelId: String): Flow<Boolean> = dao.observeIsSubscribed(channelId)

    /**
     * Subscribes to a channel given as a link (`/channel/UC…`, `/@handle`, `/c/…`, `/user/…`) or a bare
     * `UC…` id or `@handle`. Looks the channel up for its name and avatar.
     * @throws SourceException when YouTube cannot be reached or has no such channel.
     */
    suspend fun add(linkOrChannelId: String): AddResult {
        val link = source.parseLink(linkOrChannelId) as? YouTubeLink.Channel ?: return AddResult.NotAChannel
        val channelId = source.resolveChannelId(link)
        dao.get(channelId)?.let { return AddResult.AlreadySubscribed(it) }
        return add(source.channel(channelId).channel)
    }

    /** Subscribes to a channel the app already has the details of (a search result, a channel page). */
    suspend fun add(channel: ChannelItem): AddResult {
        val entity = SubscriptionEntity(
            channelId = channel.channelId,
            name = channel.name.trim().ifEmpty { channel.channelId },
            avatarUrl = channel.avatarUrl,
            addedAtMs = clock(),
        )
        if (dao.insertAll(listOf(entity)).single() == -1L) {
            return AddResult.AlreadySubscribed(dao.get(channel.channelId) ?: entity)
        }
        onChannelsAdded()
        return AddResult.Added(entity)
    }

    /** Unsubscribes and drops the channel's videos from the home feed. */
    suspend fun remove(channelId: String) {
        db.withTransaction {
            db.feed().deleteChannel(channelId)
            dao.delete(channelId)
        }
    }

    /**
     * Imports Google Takeout's subscriptions.csv, or the Takeout .zip that holds it. Channels already
     * subscribed are left as they are.
     * @throws com.ahmadnayfeh.shasha.data.takeout.TakeoutException when the file cannot be read at all.
     */
    suspend fun importTakeout(fileName: String, bytes: ByteArray): ImportResult {
        val parsed = withContext(Dispatchers.Default) { TakeoutCsvParser.parse(fileName, bytes) }
        val now = clock()
        val rows = parsed.channels.map {
            // A row without a title gets the id as its name until the first sync reads the real one.
            SubscriptionEntity(channelId = it.channelId, name = it.name.ifEmpty { it.channelId }, avatarUrl = null, addedAtMs = now)
        }
        val added = if (rows.isEmpty()) 0 else dao.insertAll(rows).count { it != -1L }
        if (added > 0) onChannelsAdded()
        return ImportResult(added = added, alreadyPresent = rows.size - added, invalid = parsed.invalidRows)
    }

    /** Keeps a subscribed channel's name and avatar current, e.g. after its page was loaded. No-op otherwise. */
    suspend fun updateDetails(channel: ChannelItem) {
        val name = channel.name.trim()
        if (name.isNotEmpty()) dao.updateDetails(channel.channelId, name, channel.avatarUrl)
    }
}
