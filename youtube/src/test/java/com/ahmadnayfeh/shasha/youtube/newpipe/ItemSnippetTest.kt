package com.ahmadnayfeh.shasha.youtube.newpipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** The description fragment that search results carry reaches the items, for the filter to judge. */
class ItemSnippetTest {

    private fun stream(snippet: String?) = StreamInfoItem(0, "https://www.youtube.com/watch?v=abcdefghijk", "A video", StreamType.VIDEO_STREAM).apply {
        shortDescription = snippet
    }

    private fun channel(snippet: String?) = ChannelInfoItem(0, "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv", "A channel").apply {
        description = snippet
    }

    @Test
    fun aVideoKeepsItsSnippet() {
        assertThat(ItemMapper.toVideo(stream("the part of the description that matches"))!!.snippet).isEqualTo("the part of the description that matches")
    }

    @Test
    fun noSnippetOrABlankOneIsNull() {
        assertThat(ItemMapper.toVideo(stream(null))!!.snippet).isNull()
        assertThat(ItemMapper.toVideo(stream("   "))!!.snippet).isNull()
        assertThat(ItemMapper.toChannel(channel(null))!!.snippet).isNull()
        assertThat(ItemMapper.toChannel(channel(""))!!.snippet).isNull()
    }

    @Test
    fun aChannelKeepsItsSnippet() {
        assertThat(ItemMapper.toChannel(channel("Songs for kids"))!!.snippet).isEqualTo("Songs for kids")
    }
}
