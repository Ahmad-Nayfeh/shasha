package com.ahmadnayfeh.shasha.youtube.newpipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelPathTest {

    @Test
    fun channelPathsAreEncoded() {
        assertThat(NewPipeVideoSource.channelPath("UCuAXFkgsw1L7xaCfnd5JJOw")).isEqualTo("channel/UCuAXFkgsw1L7xaCfnd5JJOw")
        assertThat(NewPipeVideoSource.channelPath("@RickAstleyYT")).isEqualTo("@RickAstleyYT")
        assertThat(NewPipeVideoSource.channelPath("c/Some Name")).isEqualTo("c/Some%20Name")
        assertThat(NewPipeVideoSource.channelPath("@قناة")).isEqualTo("@%D9%82%D9%86%D8%A7%D8%A9")
    }
}
