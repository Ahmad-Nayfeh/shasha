package com.ahmadnayfeh.shasha.mobile.download

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadKind
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** A file being written to its place in the gallery or the music library; not visible to other apps until [commit]. */
interface PendingMedia {
    /** The name it will have (the system may add " (1)" when the name is taken). */
    val displayName: String

    /** The destination, open for reading and writing (a muxer needs to seek in it). */
    fun open(): ParcelFileDescriptor

    /** Makes the file visible and returns where it is. */
    fun commit(): SavedMedia

    /** Throws the unfinished file away. */
    fun abort()
}

/** A finished download: [uri] is a MediaStore `content://` uri (a `file://` one before Android 10). */
data class SavedMedia(val uri: String, val bytes: Long)

sealed interface DeleteOutcome {
    data object Deleted : DeleteOutcome

    /** It was not there any more (the viewer removed it elsewhere). */
    data object Missing : DeleteOutcome

    /** The system must ask the viewer first (the file was made by an earlier install of the app). */
    data class NeedsConsent(val sender: IntentSender) : DeleteOutcome

    data class Failed(val reason: String) : DeleteOutcome
}

/** Where downloads are saved, and how they are removed. */
interface MediaSaver {
    fun create(kind: DownloadKind, displayName: String): PendingMedia

    fun delete(uri: String): DeleteOutcome

    /** Whether the file is still there. */
    fun exists(uri: String): Boolean
}

/**
 * Saves into shared storage through MediaStore: videos in Movies/Shasha, audio in Music/Shasha. From Android 10 no
 * permission is needed for files the app creates (the file is "pending" until it is complete, so no other app sees half of
 * it). Before that it is a plain file in the same folders (WRITE_EXTERNAL_STORAGE) that the media scanner is told about.
 */
class MediaStoreSaver(private val context: Context) : MediaSaver {
    private val resolver: ContentResolver get() = context.contentResolver

    override fun create(kind: DownloadKind, displayName: String): PendingMedia =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) scopedFile(kind, displayName) else legacyFile(kind, displayName)

    // --- Android 10+ ----------------------------------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun scopedFile(kind: DownloadKind, displayName: String): PendingMedia {
        val collection = when (kind) {
            DownloadKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            DownloadKind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, FileNames.mimeType(kind))
            put(MediaStore.MediaColumns.RELATIVE_PATH, FileNames.relativeFolder(kind))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $displayName")
        return object : PendingMedia {
            override val displayName: String = displayName

            override fun open(): ParcelFileDescriptor =
                resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Cannot open $uri")

            override fun commit(): SavedMedia {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                return SavedMedia(uri.toString(), sizeOf(uri))
            }

            override fun abort() {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    private fun sizeOf(uri: Uri): Long = try {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
    } catch (e: IOException) {
        0L
    }

    // --- before Android 10 ----------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun legacyFile(kind: DownloadKind, displayName: String): PendingMedia {
        val root = Environment.getExternalStoragePublicDirectory(
            if (kind == DownloadKind.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC,
        )
        val folder = File(root, "Shasha")
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create ${folder.path}")
        val name = FileNames.unique(displayName) { File(folder, it).exists() }
        val file = File(folder, name)
        return object : PendingMedia {
            override val displayName: String = name

            override fun open(): ParcelFileDescriptor = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
            )

            override fun commit(): SavedMedia {
                MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(FileNames.mimeType(kind)), null)
                return SavedMedia(Uri.fromFile(file).toString(), file.length())
            }

            override fun abort() {
                file.delete()
            }
        }
    }

    // --- removing and checking ------------------------------------------------------------------------------------

    override fun delete(uri: String): DeleteOutcome {
        val parsed = uri.toUri()
        if (parsed.scheme == "file") {
            val file = File(parsed.path.orEmpty())
            return when {
                !file.exists() -> DeleteOutcome.Missing
                file.delete() -> {
                    MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
                    DeleteOutcome.Deleted
                }
                else -> DeleteOutcome.Failed("Cannot delete ${file.name}")
            }
        }
        if (!exists(uri)) return DeleteOutcome.Missing
        return try {
            if (resolver.delete(parsed, null, null) > 0) DeleteOutcome.Deleted else DeleteOutcome.Missing
        } catch (e: SecurityException) {
            consentFor(parsed, e) ?: DeleteOutcome.Failed(e.javaClass.simpleName)
        }
    }

    /** A file another install of the app made: the system asks the viewer before it goes (Android 10: per file). */
    private fun consentFor(uri: Uri, e: SecurityException): DeleteOutcome? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            DeleteOutcome.NeedsConsent(MediaStore.createDeleteRequest(resolver, listOf(uri)).intentSender)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException ->
            DeleteOutcome.NeedsConsent(e.userAction.actionIntent.intentSender)
        else -> null
    }

    override fun exists(uri: String): Boolean {
        val parsed = uri.toUri()
        if (parsed.scheme == "file") return File(parsed.path.orEmpty()).exists()
        return try {
            resolver.openAssetFileDescriptor(parsed, "r")?.use { true } ?: false
        } catch (e: FileNotFoundException) {
            false
        } catch (e: SecurityException) {
            true // it exists, we may just not read it
        } catch (e: IOException) {
            false
        }
    }
}
