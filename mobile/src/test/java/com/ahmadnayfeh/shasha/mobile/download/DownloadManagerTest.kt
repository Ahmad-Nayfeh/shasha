package com.ahmadnayfeh.shasha.mobile.download

import android.app.Application
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.FakeDownloadDao
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadEntity
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadKind
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadStatus
import com.ahmadnayfeh.shasha.mobile.failure
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.download.DownloadOptions
import com.ahmadnayfeh.shasha.youtube.download.DownloadStream
import com.ahmadnayfeh.shasha.youtube.download.VideoDownloadOption
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private class FakeNotifications : DownloadNotifications {
    val completed = ArrayList<DownloadEntity>()
    val failed = ArrayList<DownloadEntity>()
    override fun completed(download: DownloadEntity) {
        completed += download
    }

    override fun failed(download: DownloadEntity) {
        failed += download
    }
}

/** Saves into a temporary folder; remembers what was committed, aborted or deleted. */
private class FakeSaver(private val folder: File) : MediaSaver {
    val committed = ArrayList<String>()
    val aborted = ArrayList<String>()
    val deleted = ArrayList<String>()
    val names = ArrayList<Pair<DownloadKind, String>>()
    var deleteOutcome: DeleteOutcome = DeleteOutcome.Deleted
    val missing = HashSet<String>()

    override fun create(kind: DownloadKind, displayName: String): PendingMedia {
        names += kind to displayName
        val file = File(folder, "out-${names.size}-$displayName")
        return object : PendingMedia {
            override val displayName: String = displayName
            override fun open(): ParcelFileDescriptor = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
            )

            override fun commit(): SavedMedia {
                committed += file.name
                return SavedMedia("file://${file.path}", file.length())
            }

            override fun abort() {
                aborted += file.name
                file.delete()
            }
        }
    }

    override fun delete(uri: String): DeleteOutcome {
        deleted += uri
        return deleteOutcome
    }

    override fun exists(uri: String): Boolean = uri !in missing
}

/** Writes "MUXED(video+audio)" to the output; remembers what it was given. */
private class FakeRemuxer : Remuxer {
    val calls = ArrayList<Pair<String?, String>>()
    var failWith: Exception? = null

    override suspend fun mux(video: File?, audio: File, output: java.io.FileDescriptor, onProgress: (Float) -> Unit) {
        calls += video?.readText() to audio.readText()
        failWith?.let { throw it }
        onProgress(0.5f)
        FileOutputStream(output).use { it.write("MUXED(${video?.readText()}+${audio.readText()})".toByteArray()) }
        onProgress(1f)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DownloadManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dao: FakeDownloadDao
    private lateinit var saver: FakeSaver
    private lateinit var remuxer: FakeRemuxer
    private lateinit var notifications: FakeNotifications
    private val fetched = ArrayList<DownloadStream>()
    private var optionsCalls = 0
    private var optionsError: Exception? = null
    private var fetchBehaviour: suspend (DownloadStream, File) -> Unit = { _, _ -> }
    private var clock = 1_000L

    private fun stream(name: String, mime: String, itag: Int, size: Long) = DownloadStream("https://example/$name", mime, "codec", 1, size, itag)

    private val options = DownloadOptions(
        videos = listOf(1080, 720, 360).map { height ->
            VideoDownloadOption(height, 30, stream("v$height", "video/mp4", height, 1_000L + height), stream("a", "audio/mp4", 140, 500))
        },
        audio = stream("a", "audio/mp4", 140, 500),
        durationSeconds = 100,
    )

    @Before
    fun setUp() {
        dao = FakeDownloadDao()
        saver = FakeSaver(folder.newFolder("media"))
        remuxer = FakeRemuxer()
        notifications = FakeNotifications()
    }

    private fun TestScope.manager(): DownloadManager = DownloadManager(
        dao = dao,
        source = {
            optionsCalls++
            optionsError?.let { throw it }
            options
        },
        fetcher = { stream, target, progress ->
            fetched += stream
            fetchBehaviour(stream, target)
            target.parentFile?.mkdirs()
            target.writeText(stream.url.substringAfterLast('/'))
            progress(stream.sizeBytes ?: 0)
            stream.sizeBytes ?: 0
        },
        remuxer = remuxer,
        saver = saver,
        workDir = File(folder.root, "work"),
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        notifications = notifications,
        now = { clock++ },
    )

    @Test
    fun aVideoIsFetchedMergedSavedAndRecorded() = runTest {
        val manager = manager()

        val id = manager.enqueue(video("abc", title = "My: Title"), DownloadKind.VIDEO, requestedHeight = 720)

        val row = dao.get(id)!!
        assertThat(row.status).isEqualTo(DownloadStatus.DONE)
        assertThat(row.height).isEqualTo(720)
        assertThat(row.displayName).isEqualTo("My Title [720p].mp4")
        assertThat(row.uri).startsWith("file://")
        assertThat(row.fileBytes).isGreaterThan(0)
        assertThat(row.error).isNull()
        // The 720p picture and the audio were fetched, then merged into one file.
        assertThat(fetched.map { it.itag }).containsExactly(720, 140).inOrder()
        assertThat(remuxer.calls).containsExactly("v720" to "a")
        assertThat(saver.names).containsExactly(DownloadKind.VIDEO to "My Title [720p].mp4")
        assertThat(saver.committed).hasSize(1)
        assertThat(saver.aborted).isEmpty()
        assertThat(notifications.completed.map { it.id }).containsExactly(id)
        // Nothing of the work stays on the phone's own storage.
        assertThat(File(folder.root, "work/$id").exists()).isFalse()
        assertThat(manager.active.value).isNull()
        assertThat(manager.busy.value).isFalse()
    }

    @Test
    fun audioIsOnlyTheAudioFileRewrittenAsM4a() = runTest {
        val manager = manager()

        val id = manager.enqueue(video("abc", title = "Song"), DownloadKind.AUDIO, requestedHeight = 1080)

        val row = dao.get(id)!!
        assertThat(row.status).isEqualTo(DownloadStatus.DONE)
        assertThat(row.displayName).isEqualTo("Song.m4a")
        assertThat(row.requestedHeight).isEqualTo(0)
        assertThat(fetched.map { it.itag }).containsExactly(140)
        assertThat(remuxer.calls).containsExactly(null to "a")
    }

    @Test
    fun aQualityTheVideoLacksFallsBackToTheNearestBelow() = runTest {
        val manager = manager()

        val id = manager.enqueue(video("abc"), DownloadKind.VIDEO, requestedHeight = 900)

        assertThat(dao.get(id)!!.height).isEqualTo(720)
    }

    @Test
    fun theStreamsAreLookedUpWhenTheDownloadStartsNotWhenItIsAsked() = runTest {
        val manager = manager()
        manager.enqueue(video("a"), DownloadKind.AUDIO, 0)
        manager.enqueue(video("b"), DownloadKind.AUDIO, 0)

        assertThat(optionsCalls).isEqualTo(2)
    }

    @Test
    fun aFailedFetchKeepsItsRowForTryAgainAndSaysWhy() = runTest {
        fetchBehaviour = { _, _ -> throw IOException("connection reset") }
        val manager = manager()

        val id = manager.enqueue(video("abc"), DownloadKind.VIDEO, 720)

        val row = dao.get(id)!!
        assertThat(row.status).isEqualTo(DownloadStatus.FAILED)
        assertThat(row.error).contains("IOException")
        assertThat(row.error).contains("connection reset")
        assertThat(notifications.failed.map { it.id }).containsExactly(id)
        assertThat(saver.committed).isEmpty()
        assertThat(manager.busy.value).isFalse()
    }

    @Test
    fun aFailedMergeThrowsTheUnfinishedFileAway() = runTest {
        remuxer.failWith = IOException("muxer broke")
        val manager = manager()

        val id = manager.enqueue(video("abc"), DownloadKind.VIDEO, 720)

        assertThat(dao.get(id)!!.status).isEqualTo(DownloadStatus.FAILED)
        assertThat(saver.committed).isEmpty()
        assertThat(saver.aborted).hasSize(1)
    }

    @Test
    fun aVideoWithNothingToDownloadFails() = runTest {
        val manager = DownloadManager(
            dao, { DownloadOptions(emptyList(), null, 10) }, { _, _, _ -> 0 }, remuxer, saver, File(folder.root, "work"),
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)), notifications,
        )

        val id = manager.enqueue(video("abc"), DownloadKind.VIDEO, 720)

        assertThat(dao.get(id)!!.status).isEqualTo(DownloadStatus.FAILED)
        assertThat(dao.get(id)!!.error).contains("NoDownloadableStreams")
    }

    @Test
    fun anExpiredUrlIsCuredOnceByLookingTheStreamsUpAgain() = runTest {
        var refused = false
        fetchBehaviour = { stream, _ ->
            if (stream.itag == 720 && !refused) {
                refused = true
                throw failure(SourceErrorKind.BLOCKED_403, "HTTP 403")
            }
        }
        val manager = manager()

        val id = manager.enqueue(video("abc"), DownloadKind.VIDEO, 720)

        assertThat(dao.get(id)!!.status).isEqualTo(DownloadStatus.DONE)
        assertThat(optionsCalls).isEqualTo(2)
    }

    @Test
    fun aSecondRefusalIsAFailure() = runTest {
        fetchBehaviour = { _, _ -> throw failure(SourceErrorKind.BLOCKED_403, "HTTP 403") }
        val manager = manager()

        val id = manager.enqueue(video("abc"), DownloadKind.VIDEO, 720)

        assertThat(dao.get(id)!!.status).isEqualTo(DownloadStatus.FAILED)
        assertThat(dao.get(id)!!.error).contains("HTTP 403")
        assertThat(optionsCalls).isEqualTo(2)
    }

    @Test
    fun anExtractionFailureIsAFailure() = runTest {
        optionsError = failure(SourceErrorKind.NETWORK, "UnknownHostException")
        val manager = manager()

        val id = manager.enqueue(video("abc"), DownloadKind.VIDEO, 720)

        assertThat(dao.get(id)!!.status).isEqualTo(DownloadStatus.FAILED)
        assertThat(dao.get(id)!!.error).contains("UnknownHostException")
    }

    @Test
    fun downloadsRunOneAtATimeInTheOrderAsked() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fetchBehaviour = { stream, _ -> if (stream.itag == 140 && fetched.count { it.itag == 140 } == 1) gate.await() }
        val manager = manager()

        val first = manager.enqueue(video("a", title = "First"), DownloadKind.AUDIO, 0)
        val second = manager.enqueue(video("b", title = "Second"), DownloadKind.AUDIO, 0)

        // The first is fetching (held at the gate); the second waits.
        assertThat(dao.get(first)!!.status).isEqualTo(DownloadStatus.RUNNING)
        assertThat(dao.get(second)!!.status).isEqualTo(DownloadStatus.QUEUED)
        assertThat(manager.busy.value).isTrue()
        assertThat(manager.active.value!!.id).isEqualTo(first)

        gate.complete(Unit)

        assertThat(dao.get(first)!!.status).isEqualTo(DownloadStatus.DONE)
        assertThat(dao.get(second)!!.status).isEqualTo(DownloadStatus.DONE)
        assertThat(notifications.completed.map { it.id }).containsExactly(first, second).inOrder()
        assertThat(manager.busy.value).isFalse()
    }

    @Test
    fun cancellingTheRunningDownloadRemovesItAndTheNextOneGoesOn() = runTest {
        var holdFirst = true
        fetchBehaviour = { _, _ -> if (holdFirst) awaitCancellation() }
        val manager = manager()
        val first = manager.enqueue(video("a"), DownloadKind.AUDIO, 0)
        val second = manager.enqueue(video("b"), DownloadKind.AUDIO, 0)
        assertThat(dao.get(first)!!.status).isEqualTo(DownloadStatus.RUNNING)

        holdFirst = false
        manager.cancel(first)

        assertThat(dao.get(first)).isNull()
        assertThat(dao.get(second)!!.status).isEqualTo(DownloadStatus.DONE)
        assertThat(notifications.failed).isEmpty()
        assertThat(manager.busy.value).isFalse()
    }

    @Test
    fun cancellingAQueuedDownloadJustRemovesIt() = runTest {
        fetchBehaviour = { _, _ -> awaitCancellation() }
        val manager = manager()
        manager.enqueue(video("a"), DownloadKind.AUDIO, 0)
        val queued = manager.enqueue(video("b"), DownloadKind.AUDIO, 0)

        manager.cancel(queued)

        assertThat(dao.get(queued)).isNull()
    }

    @Test
    fun retryQueuesAFailedDownloadAgain() = runTest {
        var broken = true
        fetchBehaviour = { _, _ -> if (broken) throw IOException("offline") }
        val manager = manager()
        val id = manager.enqueue(video("abc"), DownloadKind.AUDIO, 0)
        assertThat(dao.get(id)!!.status).isEqualTo(DownloadStatus.FAILED)

        broken = false
        manager.retry(id)

        assertThat(dao.get(id)!!.status).isEqualTo(DownloadStatus.DONE)
        assertThat(dao.get(id)!!.error).isNull()
    }

    @Test
    fun afterARestartWhatWasRunningIsQueuedAgainAndTheQueueStarts() = runTest {
        val id = dao.insert(row(DownloadStatus.RUNNING))
        val manager = manager()

        manager.recover()

        assertThat(dao.get(id)!!.status).isEqualTo(DownloadStatus.DONE)
    }

    @Test
    fun recoverWithNothingPendingStartsNothing() = runTest {
        dao.insert(row(DownloadStatus.DONE))
        val manager = manager()

        manager.recover()

        assertThat(manager.busy.value).isFalse()
        assertThat(optionsCalls).isEqualTo(0)
    }

    @Test
    fun aFileTheViewerRemovedElsewhereIsNoLongerADownload() = runTest {
        val gone = dao.insert(row(DownloadStatus.DONE, uri = "content://gone"))
        val kept = dao.insert(row(DownloadStatus.DONE, uri = "content://kept"))
        val failed = dao.insert(row(DownloadStatus.FAILED))
        saver.missing += "content://gone"
        val manager = manager()

        manager.pruneMissing()

        assertThat(dao.get(gone)).isNull()
        assertThat(dao.get(kept)).isNotNull()
        assertThat(dao.get(failed)).isNotNull()
    }

    @Test
    fun deletingAFinishedDownloadRemovesItsFileAndItsRow() = runTest {
        val id = dao.insert(row(DownloadStatus.DONE, uri = "content://file"))
        val manager = manager()

        val outcome = manager.delete(id)

        assertThat(outcome).isEqualTo(DeleteOutcome.Deleted)
        assertThat(saver.deleted).containsExactly("content://file")
        assertThat(dao.get(id)).isNull()
    }

    @Test
    fun aFileAnEarlierInstallMadeNeedsTheSystemsConsentFirst() = runTest {
        val id = dao.insert(row(DownloadStatus.DONE, uri = "content://old"))
        saver.deleteOutcome = DeleteOutcome.Failed("SecurityException")
        val manager = manager()

        val outcome = manager.delete(id)

        // A refusal keeps the row (the file is still there); only the consent flow ends in forget().
        assertThat(outcome).isEqualTo(DeleteOutcome.Failed("SecurityException"))
        assertThat(dao.get(id)).isNotNull()

        manager.forget(id)
        assertThat(dao.get(id)).isNull()
    }

    @Test
    fun aMissingFileStillClearsTheRow() = runTest {
        val id = dao.insert(row(DownloadStatus.DONE, uri = "content://x"))
        saver.deleteOutcome = DeleteOutcome.Missing
        val manager = manager()

        manager.delete(id)

        assertThat(dao.get(id)).isNull()
    }

    private fun row(status: DownloadStatus, uri: String? = null) = DownloadEntity(
        videoId = "abc", title = "T", channelName = null, thumbnailUrl = null, durationSeconds = 100, kind = DownloadKind.AUDIO,
        requestedHeight = 0, height = 0, status = status, fileBytes = 0, uri = uri, displayName = null, error = null, createdAt = 1, completedAt = null,
    )
}
