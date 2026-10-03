package com.ahmadnayfeh.shasha.mobile

import com.ahmadnayfeh.shasha.mobile.data.db.DownloadDao
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadEntity
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadStatus
import com.ahmadnayfeh.shasha.mobile.data.db.FeedDao
import com.ahmadnayfeh.shasha.mobile.data.db.FeedItemEntity
import com.ahmadnayfeh.shasha.mobile.data.db.HistoryDao
import com.ahmadnayfeh.shasha.mobile.data.db.HistoryEntity
import com.ahmadnayfeh.shasha.mobile.data.db.ProgressDao
import com.ahmadnayfeh.shasha.mobile.data.db.SearchHistoryDao
import com.ahmadnayfeh.shasha.mobile.data.db.SearchHistoryEntity
import com.ahmadnayfeh.shasha.mobile.data.db.SubscriptionDao
import com.ahmadnayfeh.shasha.mobile.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.mobile.data.db.WatchLaterDao
import com.ahmadnayfeh.shasha.mobile.data.db.WatchLaterEntity
import com.ahmadnayfeh.shasha.mobile.data.db.WatchProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/*
 * The DAOs in memory, answering at once, so a view model's test needs no Room and no other thread. The queries are
 * Room's own in the app; RepositoriesTest runs them on a real database.
 */

class FakeSubscriptionDao : SubscriptionDao {
    val rows = MutableStateFlow<List<SubscriptionEntity>>(emptyList())
    override fun observeAll(): Flow<List<SubscriptionEntity>> = rows.map { list -> list.sortedBy { it.name.lowercase() } }
    override suspend fun all(): List<SubscriptionEntity> = rows.value
    override fun observeIsSubscribed(channelId: String): Flow<Boolean> = rows.map { list -> list.any { it.channelId == channelId } }
    override suspend fun upsert(subscription: SubscriptionEntity) {
        rows.value = rows.value.filterNot { it.channelId == subscription.channelId } + subscription
    }

    override suspend fun delete(channelId: String) {
        rows.value = rows.value.filterNot { it.channelId == channelId }
    }
}

class FakeFeedDao : FeedDao {
    val rows = MutableStateFlow<List<FeedItemEntity>>(emptyList())
    override fun observe(limit: Int): Flow<List<FeedItemEntity>> =
        rows.map { list -> list.sortedWith(compareBy<FeedItemEntity> { it.publishedAt == null }.thenByDescending { it.publishedAt ?: 0 }).take(limit) }

    override suspend fun upsertAll(items: List<FeedItemEntity>) {
        rows.value = rows.value.filterNot { old -> items.any { it.videoId == old.videoId } } + items
    }

    override suspend fun deleteForChannel(channelId: String) {
        rows.value = rows.value.filterNot { it.channelId == channelId }
    }

    override suspend fun deleteOfUnsubscribed() = Unit
    override suspend fun clear() {
        rows.value = emptyList()
    }
}

class FakeProgressDao : ProgressDao {
    val rows = MutableStateFlow<List<WatchProgressEntity>>(emptyList())
    override suspend fun get(videoId: String): WatchProgressEntity? = rows.value.firstOrNull { it.videoId == videoId }
    override fun observeRecent(): Flow<List<WatchProgressEntity>> = rows
    override suspend fun upsert(progress: WatchProgressEntity) {
        rows.value = rows.value.filterNot { it.videoId == progress.videoId } + progress
    }

    override suspend fun delete(videoId: String) {
        rows.value = rows.value.filterNot { it.videoId == videoId }
    }

    override suspend fun clear() {
        rows.value = emptyList()
    }
}

class FakeHistoryDao : HistoryDao {
    val rows = MutableStateFlow<List<HistoryEntity>>(emptyList())
    override fun observe(limit: Int): Flow<List<HistoryEntity>> = rows.map { list -> list.sortedByDescending { it.watchedAt }.take(limit) }
    override suspend fun upsert(entry: HistoryEntity) {
        rows.value = rows.value.filterNot { it.videoId == entry.videoId } + entry
    }

    override suspend fun delete(videoId: String) {
        rows.value = rows.value.filterNot { it.videoId == videoId }
    }

    override suspend fun clear() {
        rows.value = emptyList()
    }

    override suspend fun trim(keep: Int) {
        rows.value = rows.value.sortedByDescending { it.watchedAt }.take(keep)
    }
}

class FakeWatchLaterDao : WatchLaterDao {
    val rows = MutableStateFlow<List<WatchLaterEntity>>(emptyList())
    override fun observeAll(): Flow<List<WatchLaterEntity>> = rows.map { list -> list.sortedByDescending { it.addedAt } }
    override fun observeContains(videoId: String): Flow<Boolean> = rows.map { list -> list.any { it.videoId == videoId } }
    override suspend fun contains(videoId: String): Boolean = rows.value.any { it.videoId == videoId }
    override suspend fun upsert(entry: WatchLaterEntity) {
        rows.value = rows.value.filterNot { it.videoId == entry.videoId } + entry
    }

    override suspend fun delete(videoId: String) {
        rows.value = rows.value.filterNot { it.videoId == videoId }
    }

    override suspend fun clear() {
        rows.value = emptyList()
    }
}

class FakeSearchHistoryDao : SearchHistoryDao {
    val rows = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())
    override fun observe(limit: Int): Flow<List<SearchHistoryEntity>> = rows.map { list -> list.sortedByDescending { it.searchedAt }.take(limit) }
    override suspend fun upsert(entry: SearchHistoryEntity) {
        rows.value = rows.value.filterNot { it.query == entry.query } + entry
    }

    override suspend fun delete(query: String) {
        rows.value = rows.value.filterNot { it.query == query }
    }

    override suspend fun clear() {
        rows.value = emptyList()
    }

    override suspend fun trim(keep: Int) {
        rows.value = rows.value.sortedByDescending { it.searchedAt }.take(keep)
    }
}

/** The in-memory table of downloads. */
class FakeDownloadDao : DownloadDao {
    val rows = MutableStateFlow<List<DownloadEntity>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<DownloadEntity>> = rows
    override suspend fun all(): List<DownloadEntity> =
        rows.value.sortedWith(compareByDescending<DownloadEntity> { it.createdAt }.thenByDescending { it.id })

    override suspend fun get(id: Long): DownloadEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun nextQueued(): DownloadEntity? =
        rows.value.filter { it.status == DownloadStatus.QUEUED }.minWithOrNull(compareBy({ it.createdAt }, { it.id }))

    override suspend fun insert(entity: DownloadEntity): Long {
        val id = nextId++
        rows.value = rows.value + entity.copy(id = id)
        return id
    }

    private fun update(id: Long, change: (DownloadEntity) -> DownloadEntity) {
        rows.value = rows.value.map { if (it.id == id) change(it) else it }
    }

    override suspend fun setStatus(id: Long, status: DownloadStatus, error: String?) = update(id) { it.copy(status = status, error = error) }

    override suspend fun markDone(id: Long, height: Int, bytes: Long, uri: String, name: String, completedAt: Long) = update(id) {
        it.copy(status = DownloadStatus.DONE, height = height, fileBytes = bytes, uri = uri, displayName = name, error = null, completedAt = completedAt)
    }

    override suspend fun requeueRunning() {
        rows.value = rows.value.map { if (it.status == DownloadStatus.RUNNING) it.copy(status = DownloadStatus.QUEUED) else it }
    }

    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun pendingCount(): Int = rows.value.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
}
