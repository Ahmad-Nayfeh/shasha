package com.ahmadnayfeh.shasha.mobile.download

import com.ahmadnayfeh.shasha.mobile.data.db.DownloadDao
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadEntity
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadKind
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadStatus
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.download.DownloadOptions
import com.ahmadnayfeh.shasha.youtube.download.DownloadStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Where a running download is: finding the streams, fetching the files, or merging them into one. */
enum class DownloadStage { PREPARING, DOWNLOADING, MERGING }

/** The download that is running now; [fraction] is 0..1, or -1 while nobody knows the total. */
data class ActiveDownload(
    val id: Long,
    val title: String,
    val stage: DownloadStage,
    val fraction: Float,
    val bytesDone: Long,
    val bytesTotal: Long,
)

/** What a video can be downloaded as: the streams' addresses come from a fresh extraction every time (they expire). */
fun interface OptionsSource {
    /** @throws com.ahmadnayfeh.shasha.youtube.SourceException */
    suspend fun options(videoId: String): DownloadOptions
}

/** Fetches one remote file into [target] (resuming), reporting the bytes the file holds. */
fun interface StreamFetcher {
    suspend fun fetch(stream: DownloadStream, target: File, onProgress: (Long) -> Unit): Long
}

/** What the download tells the viewer when it is over (notifications; a fake in tests). */
interface DownloadNotifications {
    fun completed(download: DownloadEntity)

    fun failed(download: DownloadEntity)
}

/**
 * Downloads, one at a time, in a queue that survives restarts (the rows are in Room). A video is fetched as DASH
 * video-only MP4 (H.264) plus M4A audio, then [Remuxer] merges them into one MP4 in the gallery without re-encoding;
 * audio only is the M4A, rewritten as a clean file in the music library. The streams are looked up again when the
 * download starts (the URLs of a queued one would have expired), and once more if googlevideo refuses them midway.
 *
 * A failure keeps its row ([DownloadStatus.FAILED]) for «try again»; cancelling removes it. The work happens on [scope]
 * (the app's), so it goes on when the screen is left; the [DownloadService] only keeps the process alive and shows [active].
 */
class DownloadManager(
    private val dao: DownloadDao,
    private val source: OptionsSource,
    private val fetcher: StreamFetcher,
    private val remuxer: Remuxer,
    private val saver: MediaSaver,
    private val workDir: File,
    private val scope: CoroutineScope,
    private val notifications: DownloadNotifications,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val downloads: Flow<List<DownloadEntity>> = dao.observeAll()

    private val _active = MutableStateFlow<ActiveDownload?>(null)
    val active: StateFlow<ActiveDownload?> = _active.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** True from the moment a download is asked for until the queue is empty again. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val lock = Mutex()
    private var workerActive = false

    @Volatile
    private var running: Pair<Long, Job>? = null

    /** Adds a download to the queue and starts the queue. [requestedHeight] is ignored for audio. */
    suspend fun enqueue(video: VideoItem, kind: DownloadKind, requestedHeight: Int): Long {
        val id = dao.insert(
            DownloadEntity(
                videoId = video.videoId,
                title = video.title,
                channelName = video.channelName,
                thumbnailUrl = video.thumbnailUrl,
                durationSeconds = video.durationSeconds,
                kind = kind,
                requestedHeight = if (kind == DownloadKind.AUDIO) 0 else requestedHeight,
                height = 0,
                status = DownloadStatus.QUEUED,
                fileBytes = 0,
                uri = null,
                displayName = null,
                error = null,
                createdAt = now(),
                completedAt = null,
            ),
        )
        kick()
        return id
    }

    /** [enqueue] on the manager's own scope: leaving the screen that asked does not cancel it. */
    fun requestInBackground(video: VideoItem, kind: DownloadKind, requestedHeight: Int) {
        scope.launch { enqueue(video, kind, requestedHeight) }
    }

    /** After a restart: what was running goes back in the queue, and the queue starts. */
    suspend fun recover() {
        dao.requeueRunning()
        if (dao.pendingCount() > 0) kick()
    }

    /** Forgets the finished downloads whose file the viewer removed elsewhere. */
    suspend fun pruneMissing() {
        for (row in dao.all()) {
            val uri = row.uri
            if (row.status == DownloadStatus.DONE && uri != null && !saver.exists(uri)) dao.delete(row.id)
        }
    }

    /** Tries a failed download again. */
    suspend fun retry(id: Long) {
        val row = dao.get(id) ?: return
        if (row.status != DownloadStatus.FAILED) return
        dao.setStatus(id, DownloadStatus.QUEUED, null)
        kick()
    }

    /** Stops a queued or running download and removes it. */
    suspend fun cancel(id: Long) {
        val current = running
        if (current != null && current.first == id) {
            current.second.cancel()
            current.second.join()
        }
        dao.delete(id)
    }

    /** Removes a download and its file; [DeleteOutcome.NeedsConsent] means the system must ask first ([forget] after). */
    suspend fun delete(id: Long): DeleteOutcome {
        val row = dao.get(id) ?: return DeleteOutcome.Missing
        if (row.status == DownloadStatus.QUEUED || row.status == DownloadStatus.RUNNING) {
            cancel(id)
            return DeleteOutcome.Deleted
        }
        val uri = row.uri
        val outcome = if (row.status == DownloadStatus.DONE && uri != null) saver.delete(uri) else DeleteOutcome.Missing
        if (outcome is DeleteOutcome.Deleted || outcome is DeleteOutcome.Missing) dao.delete(id)
        return outcome
    }

    /** Drops the row of a download whose file the system has just deleted with the viewer's consent. */
    suspend fun forget(id: Long) = dao.delete(id)

    suspend fun get(id: Long): DownloadEntity? = dao.get(id)

    /**
     * Starts the queue if it is not running. The check that finds the queue empty and the mark "not running" happen under
     * the same lock as the check in here, so a download added at that moment is never left waiting.
     */
    private suspend fun kick() {
        val start = lock.withLock {
            if (workerActive) {
                false
            } else {
                workerActive = true
                _busy.value = true
                true
            }
        }
        if (!start) return
        scope.launch {
            try {
                while (true) {
                    val next = lock.withLock {
                        dao.nextQueued().also {
                            if (it == null) {
                                workerActive = false
                                _busy.value = false
                            }
                        }
                    } ?: break
                    val job = launch { process(next) }
                    running = next.id to job
                    job.join()
                    running = null
                }
            } finally {
                running = null
            }
        }
    }

    private class Plan(val video: DownloadStream?, val audio: DownloadStream, val height: Int)

    private suspend fun process(entity: DownloadEntity) {
        dao.setStatus(entity.id, DownloadStatus.RUNNING, null)
        _active.value = ActiveDownload(entity.id, entity.title, DownloadStage.PREPARING, -1f, 0, 0)
        val dir = File(workDir, entity.id.toString())
        var pending: PendingMedia? = null
        try {
            var plan = planFor(entity)
            val videoFile = File(dir, "video.mp4")
            val audioFile = File(dir, "audio.m4a")
            plan = fetchAll(entity, plan, videoFile, audioFile)

            _active.update { it?.copy(stage = DownloadStage.MERGING, fraction = DOWNLOAD_SHARE) }
            val target = saver.create(entity.kind, FileNames.displayName(entity.title, entity.videoId, entity.kind, plan.height))
            pending = target
            target.open().use { descriptor ->
                remuxer.mux(videoFile.takeIf { plan.video != null }, audioFile, descriptor.fileDescriptor) { part ->
                    _active.update { it?.copy(fraction = DOWNLOAD_SHARE + (1f - DOWNLOAD_SHARE) * part) }
                }
            }
            val saved = target.commit()
            pending = null
            dao.markDone(entity.id, plan.height, saved.bytes, saved.uri, target.displayName, now())
            dao.get(entity.id)?.let(notifications::completed)
        } catch (e: CancellationException) {
            pending?.abort()
            throw e
        } catch (e: Throwable) {
            pending?.abort()
            dao.setStatus(entity.id, DownloadStatus.FAILED, describe(e))
            dao.get(entity.id)?.let(notifications::failed)
        } finally {
            dir.deleteRecursively()
            _active.value = null
        }
    }

    /** The streams for this download, from a fresh extraction. */
    private suspend fun planFor(entity: DownloadEntity): Plan {
        val options = source.options(entity.videoId)
        return when (entity.kind) {
            DownloadKind.AUDIO -> Plan(null, options.audio ?: throw noStreams(entity), 0)
            DownloadKind.VIDEO -> {
                val option = options.videoFor(entity.requestedHeight) ?: throw noStreams(entity)
                Plan(option.video, option.audio, option.height)
            }
        }
    }

    /** Fetches the files; googlevideo refusing the URLs midway (they expired) is cured once by extracting again. */
    private suspend fun fetchAll(entity: DownloadEntity, first: Plan, videoFile: File, audioFile: File): Plan {
        var plan = first
        var refreshed = false
        while (true) {
            try {
                val videoSize = plan.video?.sizeBytes
                val audioSize = plan.audio.sizeBytes
                val expected = when {
                    plan.video == null -> audioSize ?: -1L
                    videoSize != null && audioSize != null -> videoSize + audioSize
                    else -> -1L // a size nobody knows: the notification shows no percentage
                }
                var videoDone = 0L
                var audioDone = 0L
                fun report() {
                    val done = videoDone + audioDone
                    val fraction = if (expected > 0) (done.toFloat() / expected).coerceIn(0f, 1f) * DOWNLOAD_SHARE else -1f
                    _active.update { it?.copy(stage = DownloadStage.DOWNLOADING, fraction = fraction, bytesDone = done, bytesTotal = maxOf(expected, 0)) }
                }
                plan.video?.let { fetcher.fetch(it, videoFile) { bytes -> videoDone = bytes; report() } }
                fetcher.fetch(plan.audio, audioFile) { bytes -> audioDone = bytes; report() }
                return plan
            } catch (e: SourceException) {
                if (e.kind != SourceErrorKind.BLOCKED_403 || refreshed) throw e
                refreshed = true
                val renewed = planFor(entity)
                // The same streams continue where they stopped; other ones (another itag) would be a different file.
                if (renewed.video?.itag != plan.video?.itag) videoFile.delete()
                if (renewed.audio.itag != plan.audio.itag) audioFile.delete()
                plan = renewed
            }
        }
    }

    private fun noStreams(entity: DownloadEntity) = SourceException(
        SourceErrorKind.EXTRACTION_BROKEN,
        "NoDownloadableStreams",
        "No MP4/M4A streams to download for ${entity.videoId}",
        null,
    )

    private fun describe(error: Throwable): String = when (error) {
        is SourceException -> "${error.technicalName}: ${error.message.orEmpty()}".trim()
        else -> "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim()
    }

    companion object {
        /** Fetching is 90% of a video download's progress; merging the rest. */
        const val DOWNLOAD_SHARE = 0.9f
    }
}
