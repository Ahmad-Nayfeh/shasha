package com.ahmadnayfeh.shasha.youtube

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class YouTubeLinkParserTest {

    private val id = "dQw4w9WgXcQ"
    private val channelId = "UCuAXFkgsw1L7xaCfnd5JJOw"
    private val playlistId = "PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"

    private fun parse(text: String) = YouTubeLinkParser.parse(text)

    @Test
    fun watchUrls() {
        assertThat(parse("https://www.youtube.com/watch?v=$id")).isEqualTo(YouTubeLink.Video(id, null, null))
        assertThat(parse("http://youtube.com/watch?v=$id&feature=youtu.be")).isEqualTo(YouTubeLink.Video(id, null, null))
        assertThat(parse("https://www.youtube.com/watch?app=desktop&v=$id")).isEqualTo(YouTubeLink.Video(id, null, null))
        assertThat(parse("https://m.youtube.com/watch?v=$id&list=$playlistId&index=3"))
            .isEqualTo(YouTubeLink.Video(id, null, playlistId))
        assertThat(parse("https://music.youtube.com/watch?v=$id")).isEqualTo(YouTubeLink.Video(id, null, null))
        assertThat(parse("HTTPS://WWW.YOUTUBE.COM/watch?v=$id")).isEqualTo(YouTubeLink.Video(id, null, null))
        assertThat(parse("www.youtube.com/watch?v=$id")).isEqualTo(YouTubeLink.Video(id, null, null))
    }

    @Test
    fun startTimeFormats() {
        assertThat(parse("https://www.youtube.com/watch?v=$id&t=90")).isEqualTo(YouTubeLink.Video(id, 90, null))
        assertThat(parse("https://www.youtube.com/watch?v=$id&t=90s")).isEqualTo(YouTubeLink.Video(id, 90, null))
        assertThat(parse("https://youtu.be/$id?t=1m30s")).isEqualTo(YouTubeLink.Video(id, 90, null))
        assertThat(parse("https://youtu.be/$id?t=1h2m3s")).isEqualTo(YouTubeLink.Video(id, 3723, null))
        assertThat(parse("https://www.youtube.com/watch?v=$id#t=45")).isEqualTo(YouTubeLink.Video(id, 45, null))
        assertThat(parse("https://www.youtube.com/embed/$id?start=30")).isEqualTo(YouTubeLink.Video(id, 30, null))
        assertThat(YouTubeLinkParser.parseStartSeconds("1:30")).isEqualTo(90)
        assertThat(YouTubeLinkParser.parseStartSeconds("01:02:03")).isEqualTo(3723)
        assertThat(YouTubeLinkParser.parseStartSeconds("0")).isNull()
        assertThat(YouTubeLinkParser.parseStartSeconds("abc")).isNull()
        assertThat(YouTubeLinkParser.parseStartSeconds("")).isNull()
        assertThat(YouTubeLinkParser.parseStartSeconds(null)).isNull()
    }

    @Test
    fun shortLinksAndSpecialPaths() {
        assertThat(parse("https://youtu.be/$id")).isEqualTo(YouTubeLink.Video(id, null, null))
        assertThat(parse("https://youtu.be/$id?si=AbCdEf&list=$playlistId")).isEqualTo(YouTubeLink.Video(id, null, playlistId))
        assertThat(parse("https://www.youtube.com/shorts/$id?feature=share")).isEqualTo(YouTubeLink.Video(id, null, null))
        assertThat(parse("https://youtube.com/live/$id?si=xyz")).isEqualTo(YouTubeLink.Video(id, null, null))
        assertThat(parse("https://www.youtube.com/embed/$id")).isEqualTo(YouTubeLink.Video(id, null, null))
        assertThat(parse("https://www.youtube-nocookie.com/embed/$id?list=$playlistId"))
            .isEqualTo(YouTubeLink.Video(id, null, playlistId))
        assertThat(parse("https://www.youtube.com/v/$id")).isEqualTo(YouTubeLink.Video(id, null, null))
        assertThat(parse("https://www.youtube.com/attribution_link?a=x&u=/watch%3Fv%3D$id%26feature%3Dshare"))
            .isEqualTo(YouTubeLink.Video(id, null, null))
    }

    @Test
    fun playlists() {
        assertThat(parse("https://www.youtube.com/playlist?list=$playlistId")).isEqualTo(YouTubeLink.Playlist(playlistId))
        assertThat(parse("https://www.youtube.com/watch?list=$playlistId")).isEqualTo(YouTubeLink.Playlist(playlistId))
        assertThat(parse("https://www.youtube.com/embed/videoseries?list=$playlistId")).isEqualTo(YouTubeLink.Playlist(playlistId))
        assertThat(parse("https://music.youtube.com/playlist?list=OLAK5uy_kJZyU9PNg")).isEqualTo(YouTubeLink.Playlist("OLAK5uy_kJZyU9PNg"))
    }

    @Test
    fun algorithmicAndAccountListsAreDropped() {
        assertThat(parse("https://www.youtube.com/watch?v=$id&list=RD$id&start_radio=1")).isEqualTo(YouTubeLink.Video(id, null, null))
        assertThat(parse("https://www.youtube.com/playlist?list=RDCLAK5uy_abc")).isNull()
        assertThat(parse("https://www.youtube.com/playlist?list=WL")).isNull()
        assertThat(parse("https://www.youtube.com/playlist?list=LL")).isNull()
    }

    @Test
    fun channels() {
        assertThat(parse("https://www.youtube.com/channel/$channelId")).isEqualTo(YouTubeLink.Channel(channelId))
        assertThat(parse("https://www.youtube.com/channel/$channelId/videos")).isEqualTo(YouTubeLink.Channel(channelId))
        assertThat(parse("https://www.youtube.com/@RickAstleyYT")).isEqualTo(YouTubeLink.Channel("@RickAstleyYT"))
        assertThat(parse("https://m.youtube.com/@RickAstleyYT/videos?view=0")).isEqualTo(YouTubeLink.Channel("@RickAstleyYT"))
        assertThat(parse("https://www.youtube.com/@%D9%82%D9%86%D8%A7%D8%A9")).isEqualTo(YouTubeLink.Channel("@قناة"))
        assertThat(parse("https://www.youtube.com/c/RickAstleyVEVO")).isEqualTo(YouTubeLink.Channel("c/RickAstleyVEVO"))
        assertThat(parse("https://www.youtube.com/user/RickAstleyVEVO/featured")).isEqualTo(YouTubeLink.Channel("user/RickAstleyVEVO"))
        assertThat(parse("https://www.youtube.com/channel/not-a-channel-id")).isNull()
    }

    @Test
    fun bareIds() {
        assertThat(parse(id)).isEqualTo(YouTubeLink.Video(id, null, null))
        assertThat(parse("  $id \n")).isEqualTo(YouTubeLink.Video(id, null, null))
        assertThat(parse("_PL2HJKxnOM")).isEqualTo(YouTubeLink.Video("_PL2HJKxnOM", null, null))
        assertThat(parse(channelId)).isEqualTo(YouTubeLink.Channel(channelId))
        assertThat(parse("@RickAstleyYT")).isEqualTo(YouTubeLink.Channel("@RickAstleyYT"))
        assertThat(parse(playlistId)).isEqualTo(YouTubeLink.Playlist(playlistId))
    }

    @Test
    fun ordinaryWordsAreNotVideoIds() {
        assertThat(parse("programming")).isNull()
        assertThat(parse("Programming")).isNull()
        assertThat(parse("ABCDEFGHIJK")).isNull()
        assertThat(parse("two words")).isNull()
    }

    @Test
    fun linkInsideSharedText() {
        assertThat(parse("شاهد هذا https://youtu.be/$id?si=abc!")).isEqualTo(YouTubeLink.Video(id, null, null))
        assertThat(parse("(https://www.youtube.com/watch?v=$id).")).isEqualTo(YouTubeLink.Video(id, null, null))
        assertThat(parse("Check this:\nhttps://www.youtube.com/shorts/$id")).isEqualTo(YouTubeLink.Video(id, null, null))
    }

    @Test
    fun directionMarksAndNoBreakSpacesAroundLinks() {
        val rlm = "‏"
        val lrm = "‎"
        val nbsp = " "
        val video = YouTubeLink.Video(id, null, null)
        // Arabic chat apps wrap Latin links in direction marks and put no-break spaces next to them.
        assertThat(parse("https://youtu.be/$id$rlm")).isEqualTo(video)
        assertThat(parse("${rlm}https://youtu.be/$id$rlm")).isEqualTo(video)
        assertThat(parse("https://youtu.be/$id${nbsp}شاهد")).isEqualTo(video)
        assertThat(parse("شاهد${nbsp}https://www.youtube.com/watch?v=$id$lrm")).isEqualTo(video)
        assertThat(parse("⁧https://www.youtube.com/watch?v=$id&t=90⁩")).isEqualTo(YouTubeLink.Video(id, 90, null))
        assertThat(parse("‫https://www.youtube.com/playlist?list=$playlistId‬")).isEqualTo(YouTubeLink.Playlist(playlistId))
        assertThat(parse("انظر: https://youtu.be/$id؛ جميل")).isEqualTo(video)
        assertThat(parse("$rlm$id$rlm")).isEqualTo(video)
        assertThat(parse("$lrm@RickAstleyYT$lrm")).isEqualTo(YouTubeLink.Channel("@RickAstleyYT"))
    }

    @Test
    fun rejectsNonYouTubeAndIncompleteLinks() {
        assertThat(parse("")).isNull()
        assertThat(parse("   ")).isNull()
        assertThat(parse("https://example.com/watch?v=$id")).isNull()
        assertThat(parse("https://notyoutube.com/watch?v=$id")).isNull()
        assertThat(parse("https://www.youtube.com/")).isNull()
        assertThat(parse("https://www.youtube.com/watch?v=short")).isNull()
        assertThat(parse("https://www.youtube.com/results?search_query=cats")).isNull()
        assertThat(parse("https://youtu.be/")).isNull()
    }

    @Test
    fun urlHelpers() {
        assertThat(YouTubeLinkParser.videoIdFromUrl("https://www.youtube.com/shorts/$id")).isEqualTo(id)
        assertThat(YouTubeLinkParser.channelIdFromUrl("https://www.youtube.com/channel/$channelId")).isEqualTo(channelId)
        assertThat(YouTubeLinkParser.channelIdFromUrl("https://www.youtube.com/@handle")).isNull()
        assertThat(YouTubeLinkParser.playlistIdFromUrl("https://www.youtube.com/playlist?list=$playlistId")).isEqualTo(playlistId)
        assertThat(YouTubeLinkParser.isVideoId(id)).isTrue()
        assertThat(YouTubeLinkParser.isChannelId(channelId)).isTrue()
        assertThat(YouTubeLinkParser.isChannelId("@handle")).isFalse()
    }
}
