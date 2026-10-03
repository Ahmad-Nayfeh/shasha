package com.ahmadnayfeh.shasha.mobile.ui

import com.ahmadnayfeh.shasha.mobile.ui.components.initialOf
import com.ahmadnayfeh.shasha.mobile.ui.nav.ChannelRoute
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The letter that stands in for a channel's picture where it is missing (a subscription saved before its picture was known). */
class AvatarInitialTest {
    @Test
    fun anArabicNameGivesItsFirstLetter() {
        assertThat(initialOf("أحمد السيد")).isEqualTo("أ")
        assertThat(initialOf("عبدالله العجيري")).isEqualTo("ع")
    }

    @Test
    fun aLatinNameGivesItsFirstLetterInCapitals() {
        assertThat(initialOf("kurzgesagt")).isEqualTo("K")
        assertThat(initialOf("  Veritasium ")).isEqualTo("V")
    }

    @Test
    fun aDigitIsAnInitialToo() {
        assertThat(initialOf("3Blue1Brown")).isEqualTo("3")
    }

    @Test
    fun whatIsNotALetterIsSkippedAHandleSignAndAnEmojiAmongThem() {
        assertThat(initialOf("@handle")).isEqualTo("H")
        assertThat(initialOf("🎵 Music")).isEqualTo("M")
        assertThat(initialOf("«قناة»")).isEqualTo("ق")
    }

    @Test
    fun aLetterOutsideTheBasicPlaneIsNotCutInHalf() {
        assertThat(initialOf("𠮷野家")).isEqualTo("𠮷")
    }

    @Test
    fun noLetterAtAllMeansNoInitialTheScreenShowsAPersonIcon() {
        assertThat(initialOf(null)).isNull()
        assertThat(initialOf("")).isNull()
        assertThat(initialOf("   ")).isNull()
        assertThat(initialOf("🎵🎶 !!!")).isNull()
    }
}

/** What a screen passes to a channel's page, through the navigation route. */
class ChannelRouteTest {
    @Test
    fun theRouteCarriesTheNameAndThePictureTheOpenerKnows() {
        val route = ChannelRoute.of(ChannelItem("UCabc", "عبدالله العجيري", "https://yt3.ggpht.com/a", subscriberCount = 5))

        assertThat(route).isEqualTo(ChannelRoute("UCabc", "عبدالله العجيري", "https://yt3.ggpht.com/a"))
    }

    @Test
    fun whatTheOpenerDoesNotKnowIsLeftOut() {
        val route = ChannelRoute.of(ChannelItem("UCabc", "  ", avatarUrl = null, subscriberCount = null))

        assertThat(route).isEqualTo(ChannelRoute("UCabc"))
        assertThat(route.name).isNull()
        assertThat(route.avatarUrl).isNull()
    }

    @Test
    fun aLinkOpensAChannelWithNoHints() {
        assertThat(ChannelRoute("@handle").name).isNull()
        assertThat(ChannelRoute("@handle").avatarUrl).isNull()
    }
}
