package com.ahmadnayfeh.shasha.youtube

import java.io.ByteArrayOutputStream

/**
 * Pure-Kotlin YouTube link parser (no network, no extractor), so it is cheap and fully unit tested.
 *
 * Accepts a whole shared text ("look at this https://youtu.be/…?si=…") and returns the first
 * YouTube link in it. Mixes (list=RD…) and account-only lists (WL, LL) are dropped: this app shows
 * no algorithmic content and has no login.
 */
internal object YouTubeLinkParser {

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val PLAYLIST_ID = Regex("^[A-Za-z0-9_-]{2,64}$")
    private val BARE_PLAYLIST_ID = Regex("^(PL|OLAK5uy_)[A-Za-z0-9_-]{16,}$")
    private val HANDLE = Regex("^@[^\\s/?#@]{1,100}$")
    private val CHANNEL_ID_IN_URL = Regex("/channel/(UC[A-Za-z0-9_-]{22})(?:[/?#]|$)")
    private val URL_IN_TEXT = Regex(
        // The look-behind stops "notyoutube.com" from matching as "youtube.com". A URL ends at any space,
        // NBSP included (\p{Z}), and at invisible format characters (\p{Cf}): Arabic chat apps wrap links
        // in direction marks (U+200E/U+200F, U+202A-U+202E, U+2066-U+2069).
        "(?i)(?<![a-z0-9.-])(?:https?://)?(?:[a-z0-9-]+\\.)*(?:youtube\\.com|youtu\\.be|youtube-nocookie\\.com)" +
            "(?:[/?#][^\\s\\p{Z}\\p{Cf}<>\"']*)?",
    )
    private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ')', ']', '}', '!', ';', ':', '»', '…', '،', '؛', '؟')
    private val VIDEO_PATHS = setOf("shorts", "live", "embed", "v", "e")

    fun parse(text: String): YouTubeLink? {
        val trimmed = text.trim { it.isWhitespace() || Character.getType(it) == Character.FORMAT.toInt() }
        if (trimmed.isEmpty()) return null
        URL_IN_TEXT.find(trimmed)?.let { match ->
            parseUrl(match.value.trimEnd(*TRAILING_PUNCTUATION))?.let { return it }
        }
        return if (trimmed.any { it.isWhitespace() }) null else parseBare(trimmed)
    }

    fun isVideoId(s: String) = VIDEO_ID.matches(s)

    fun isChannelId(s: String) = CHANNEL_ID.matches(s)

    fun isPlaylistId(s: String) = PLAYLIST_ID.matches(s)

    fun videoIdFromUrl(url: String?): String? = (url?.let(::parse) as? YouTubeLink.Video)?.id

    fun channelIdFromUrl(url: String?): String? = url?.let { CHANNEL_ID_IN_URL.find(it)?.groupValues?.get(1) }

    fun playlistIdFromUrl(url: String?): String? =
        url?.let { parseUrl(it) }?.let { (it as? YouTubeLink.Playlist)?.id ?: (it as? YouTubeLink.Video)?.playlistId }

    /** Bare ids typed or pasted without a URL. */
    private fun parseBare(token: String): YouTubeLink? = when {
        CHANNEL_ID.matches(token) -> YouTubeLink.Channel(token)
        HANDLE.matches(token) -> YouTubeLink.Channel(token)
        BARE_PLAYLIST_ID.matches(token) -> YouTubeLink.Playlist(token)
        VIDEO_ID.matches(token) && !looksLikeWord(token) -> YouTubeLink.Video(token, null, null)
        else -> null
    }

    /** "programming" or "Programming" are 11 characters too; real ids virtually never look like that. */
    private fun looksLikeWord(token: String) =
        token.drop(1).all { it in 'a'..'z' } && token.first().isLetter() || token.all { it in 'A'..'Z' }

    private fun parseUrl(rawUrl: String): YouTubeLink? {
        val withoutScheme = rawUrl.replaceFirst(Regex("(?i)^https?://"), "")
        val hostEnd = withoutScheme.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) withoutScheme.length else it }
        val host = withoutScheme.substring(0, hostEnd).substringBefore(':').lowercase()
        val rest = withoutScheme.substring(hostEnd)
        val fragment = rest.substringAfter('#', "")
        val beforeFragment = rest.substringBefore('#')
        val path = beforeFragment.substringBefore('?')
        val query = parseQuery(beforeFragment.substringAfter('?', ""))
        val segments = path.split('/').filter { it.isNotEmpty() }.map(::percentDecode)

        val start = parseStartSeconds(query["t"] ?: query["start"] ?: query["time_continue"] ?: parseQuery(fragment)["t"])
        val list = query["list"]?.let(::acceptablePlaylistId)

        if (host == "youtu.be" || host.endsWith(".youtu.be")) {
            val id = segments.firstOrNull()?.takeIf { VIDEO_ID.matches(it) } ?: return null
            return YouTubeLink.Video(id, start, list)
        }
        if (!(host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com"))
        ) {
            return null
        }

        val first = segments.firstOrNull()
        val second = segments.getOrNull(1)
        return when {
            first == null -> null
            first == "watch" -> {
                val id = query["v"]?.takeIf { VIDEO_ID.matches(it) }
                when {
                    id != null -> YouTubeLink.Video(id, start, list)
                    list != null -> YouTubeLink.Playlist(list)
                    else -> null
                }
            }
            first == "embed" && second == "videoseries" -> list?.let { YouTubeLink.Playlist(it) }
            first in VIDEO_PATHS -> second?.takeIf { VIDEO_ID.matches(it) }?.let { YouTubeLink.Video(it, start, list) }
            first == "playlist" -> list?.let { YouTubeLink.Playlist(it) }
            first == "channel" -> second?.takeIf { CHANNEL_ID.matches(it) }?.let { YouTubeLink.Channel(it) }
            first.startsWith("@") -> first.takeIf { HANDLE.matches(it) }?.let { YouTubeLink.Channel(it) }
            first == "c" || first == "user" -> second?.let { YouTubeLink.Channel("$first/$it") }
            first == "attribution_link" -> query["u"]?.let { parseUrl("https://www.youtube.com$it") }
            else -> null
        }
    }

    private fun acceptablePlaylistId(id: String): String? = id.takeIf {
        PLAYLIST_ID.matches(it) && !it.startsWith("RD") && it != "WL" && it != "LL"
    }

    /** "90", "90s", "1m30s", "1h2m3s", "1:30", "1:02:03" → seconds; null when absent, zero or invalid. */
    internal fun parseStartSeconds(value: String?): Long? {
        val v = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val seconds = when {
            v.all { it.isDigit() } -> v.toLongOrNull()
            ':' in v -> v.split(':').takeIf { parts -> parts.size <= 3 && parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } } }
                ?.fold(0L) { acc, part -> acc * 60 + part.toLong() }
            else -> Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").matchEntire(v)?.let { m ->
                val (h, min, s) = m.destructured
                if (h.isEmpty() && min.isEmpty() && s.isEmpty()) null
                else (h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
            }
        }
        return seconds?.takeIf { it > 0 }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&', ';')) {
            if (pair.isEmpty()) continue
            val key = percentDecode(pair.substringBefore('='))
            val value = percentDecode(pair.substringAfter('=', "").replace('+', ' '))
            result.putIfAbsent(key, value)
        }
        return result
    }

    /** Decodes %XX sequences as UTF-8; malformed sequences are kept as-is. */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length && s[i + 1].isHexDigit() && s[i + 2].isHexDigit()) {
                out.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                // Copy the literal run up to the next escape in one go (keeps surrogate pairs intact).
                val next = s.indexOf('%', i + 1).let { if (it < 0) s.length else it }
                out.write(s.substring(i, next).toByteArray(Charsets.UTF_8))
                i = next
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
