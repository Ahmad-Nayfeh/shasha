package com.ahmadnayfeh.shasha.mobile.download

import com.ahmadnayfeh.shasha.mobile.data.db.DownloadKind

/** The names a download gets in the gallery and the music library. */
object FileNames {
    private const val MAX_BASE_LENGTH = 100
    private val forbidden = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")
    private val spaces = Regex("""\s+""")

    /** A title as a file name: no path or reserved characters, one line, not too long, never empty. */
    fun sanitize(title: String, fallback: String): String {
        val cleaned = title.replace(forbidden, " ").replace(spaces, " ").trim().trim('.', ' ')
        val shortened = if (cleaned.length > MAX_BASE_LENGTH) cleaned.take(MAX_BASE_LENGTH).trimEnd('.', ' ') else cleaned
        return shortened.ifBlank { fallback }
    }

    /** "Title [720p].mp4" for a video, "Title.m4a" for audio. */
    fun displayName(title: String, videoId: String, kind: DownloadKind, height: Int): String {
        val base = sanitize(title, videoId)
        return when (kind) {
            DownloadKind.VIDEO -> "$base [${height}p].mp4"
            DownloadKind.AUDIO -> "$base.m4a"
        }
    }

    fun mimeType(kind: DownloadKind): String = when (kind) {
        DownloadKind.VIDEO -> "video/mp4"
        DownloadKind.AUDIO -> "audio/mp4"
    }

    /** MediaStore folder (relative to the shared storage root). */
    fun relativeFolder(kind: DownloadKind): String = when (kind) {
        DownloadKind.VIDEO -> "Movies/Shasha"
        DownloadKind.AUDIO -> "Music/Shasha"
    }

    /** "name.ext" → "name (2).ext": the next free name when [taken] says a name is in use. */
    fun unique(displayName: String, taken: (String) -> Boolean): String {
        if (!taken(displayName)) return displayName
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        var n = 2
        while (taken("$base ($n)$ext")) n++
        return "$base ($n)$ext"
    }
}
