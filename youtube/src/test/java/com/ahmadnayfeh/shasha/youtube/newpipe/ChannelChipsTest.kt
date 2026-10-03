package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.ChannelSort
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The order chips of a channel's section, read from answers shaped as YouTube's really are (captured 2026-10 for
 * Rick Astley's channel, cut down to what matters): the chip bar in the header of the selected tab, the two reload
 * actions of a chip's answer.
 */
class ChannelChipsTest {

    // ---- the answers ---------------------------------------------------------------------------------------------------

    private fun chip(text: String, selected: Boolean, token: String?) = """
        {"chipViewModel":{"text":"$text","selected":$selected,"displayType":"CHIP_VIEW_MODEL_DISPLAY_TYPE_UNSPECIFIED",
        "tapCommand":{"innertubeCommand":{"clickTrackingParams":"x","continuationCommand":${
        if (token == null) "null" else """{"token":"$token","request":"CONTINUATION_REQUEST_TYPE_BROWSE"}"""
    }}},"accessibilityLabel":"$text"}}
    """.trimIndent()

    private fun chipBar(vararg chips: String) = """{"chipBarViewModel":{"chips":[${chips.joinToString(",")}]}}"""

    private val threeChips = chipBar(chip("Latest", true, "tok-latest"), chip("Popular", false, "tok-popular"), chip("Oldest", false, "tok-oldest"))

    /** A channel section's first answer: the selected tab's grid with [header] (null: none) and one video. */
    private fun firstAnswer(header: String?, selectedTab: String = "Videos", otherTabHeader: String? = null) = """
        {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[
          {"tabRenderer":{"title":"Home","content":{"richGridRenderer":{"contents":[]}}}},
          {"tabRenderer":{"title":"Shorts","content":{"richGridRenderer":{${if (otherTabHeader != null) "\"header\":$otherTabHeader," else ""}"contents":[]}}}},
          {"tabRenderer":{"title":"$selectedTab","selected":true,"content":{"richGridRenderer":{${if (header != null) "\"header\":$header," else ""}
            "contents":[{"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"dQw4w9WgXcQ"}}}},{"continuationItemRenderer":{}}]}}}}
        ]}}}
    """.trimIndent()

    private fun reloadCommand(slot: String, items: String) =
        """{"reloadContinuationItemsCommand":{"targetId":"t","slot":"$slot","continuationItems":$items}}"""

    private fun sortedAnswer(selected: String): String {
        val bar = chipBar(
            chip("Latest", selected == "Latest", "tok-latest"),
            chip("Popular", selected == "Popular", "tok-popular"),
            chip("Oldest", selected == "Oldest", "tok-oldest"),
        )
        val items = """[{"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"abc"}}}},{"continuationItemRenderer":{"trigger":"x"}}]"""
        return """{"responseContext":{},"onResponseReceivedActions":[${reloadCommand("RELOAD_CONTINUATION_SLOT_HEADER", "[$bar]")},${
        reloadCommand("RELOAD_CONTINUATION_SLOT_BODY", items)}]}"""
    }

    // ---- the first answer ----------------------------------------------------------------------------------------------

    @Test
    fun aSectionWithAChipBarOffersTheOtherOrders_andKeepsTheirTokens() {
        val sorts = ChannelChips.sortsOf(firstAnswer(threeChips))
        assertThat(sorts.offered).containsExactly(ChannelSort.LATEST, ChannelSort.POPULAR, ChannelSort.OLDEST).inOrder()
        assertThat(sorts.tokens).containsExactly(ChannelSort.POPULAR, "tok-popular", ChannelSort.OLDEST, "tok-oldest")
    }

    @Test
    fun theOffersFollowTheFixedOrder_whateverTheOrderOfTheChips() {
        val sorts = ChannelChips.sortsOf(firstAnswer(chipBar(chip("Oldest", false, "o"), chip("Latest", true, "l"), chip("Popular", false, "p"))))
        assertThat(sorts.offered).containsExactly(ChannelSort.LATEST, ChannelSort.POPULAR, ChannelSort.OLDEST).inOrder()
    }

    @Test
    fun aChipWithoutATokenIsNotOffered() {
        val sorts = ChannelChips.sortsOf(firstAnswer(chipBar(chip("Latest", true, "l"), chip("Popular", false, null), chip("Oldest", false, "o"))))
        assertThat(sorts.offered).containsExactly(ChannelSort.LATEST, ChannelSort.OLDEST).inOrder()
        assertThat(sorts.tokens).containsExactly(ChannelSort.OLDEST, "o")
    }

    @Test
    fun onlyOneOrderIsNotAChoice() {
        // A bar of «Latest» alone, or of chips that are not orders, offers nothing to choose.
        assertThat(ChannelChips.sortsOf(firstAnswer(chipBar(chip("Latest", true, "l"))))).isSameInstanceAs(ChannelChips.Sorts.NONE)
        assertThat(ChannelChips.sortsOf(firstAnswer(chipBar(chip("All", true, "a"), chip("Live", false, "b"))))).isSameInstanceAs(ChannelChips.Sorts.NONE)
    }

    @Test
    fun noChipBar_orNoAnswer_offersNothing() {
        assertThat(ChannelChips.sortsOf(firstAnswer(null)).offered).containsExactly(ChannelSort.LATEST)
        assertThat(ChannelChips.sortsOf(null)).isSameInstanceAs(ChannelChips.Sorts.NONE)
        assertThat(ChannelChips.sortsOf("")).isSameInstanceAs(ChannelChips.Sorts.NONE)
        assertThat(ChannelChips.sortsOf("<html>not json")).isSameInstanceAs(ChannelChips.Sorts.NONE)
        assertThat(ChannelChips.sortsOf("[1,2]")).isSameInstanceAs(ChannelChips.Sorts.NONE)
        assertThat(ChannelChips.sortsOf("{}")).isSameInstanceAs(ChannelChips.Sorts.NONE)
    }

    @Test
    fun onlyTheSelectedTabsChipsCount() {
        // Another tab's grid with chips (it should not be there, but the page may carry its header) is not this section's.
        val sorts = ChannelChips.sortsOf(firstAnswer(header = null, otherTabHeader = threeChips))
        assertThat(sorts).isSameInstanceAs(ChannelChips.Sorts.NONE)
    }

    @Test
    fun theOlderChipBarShapeIsRead_too() {
        fun old(text: String, selected: Boolean, token: String) =
            """{"chipCloudChipRenderer":{"text":{"simpleText":"$text"},"isSelected":$selected,"navigationEndpoint":{"continuationCommand":{"token":"$token"}}}}"""
        val header = """{"feedFilterChipBarRenderer":{"contents":[${old("Latest", true, "l")},${old("Popular", false, "p")},${old("Oldest", false, "o")}]}}"""
        val sorts = ChannelChips.sortsOf(firstAnswer(header))
        assertThat(sorts.offered).containsExactly(ChannelSort.LATEST, ChannelSort.POPULAR, ChannelSort.OLDEST).inOrder()
        assertThat(sorts.tokens[ChannelSort.POPULAR]).isEqualTo("p")
    }

    @Test
    fun chipsTellWhichIsSelected() {
        val chips = ChannelChips.chipsOfFirstAnswer(firstAnswer(threeChips))
        assertThat(chips.map { it.sort to it.selected }).containsExactly(
            ChannelSort.LATEST to true,
            ChannelSort.POPULAR to false,
            ChannelSort.OLDEST to false,
        ).inOrder()
    }

    @Test
    fun chipTextsAreYouTubesEnglish_inAnyCase() {
        assertThat(ChannelChips.sortOf("Latest")).isEqualTo(ChannelSort.LATEST)
        assertThat(ChannelChips.sortOf(" popular ")).isEqualTo(ChannelSort.POPULAR)
        assertThat(ChannelChips.sortOf("OLDEST")).isEqualTo(ChannelSort.OLDEST)
        assertThat(ChannelChips.sortOf("Date added (oldest)")).isEqualTo(ChannelSort.OLDEST)
        assertThat(ChannelChips.sortOf("Most popular")).isEqualTo(ChannelSort.POPULAR)
        // Anything else is not an order: never guessed from a translation or a topic.
        assertThat(ChannelChips.sortOf("الأحدث")).isNull()
        assertThat(ChannelChips.sortOf("Live")).isNull()
        assertThat(ChannelChips.sortOf("")).isNull()
        assertThat(ChannelChips.sortOf(null)).isNull()
    }

    // ---- the answer to a chip --------------------------------------------------------------------------------------------

    @Test
    fun aSortedAnswer_tellsWhichChipIsSelectedNow_andThatItHoldsTheList() {
        val popular = ChannelChips.inspectSorted(sortedAnswer("Popular"))
        assertThat(popular.selected).isEqualTo(ChannelSort.POPULAR)
        assertThat(popular.hasList).isTrue()
        assertThat(ChannelChips.inspectSorted(sortedAnswer("Oldest")).selected).isEqualTo(ChannelSort.OLDEST)
        // No header slot (or no answer): nothing to say of the chips, and no list.
        val empty = ChannelChips.inspectSorted("""{"onResponseReceivedActions":[]}""")
        assertThat(empty.selected).isNull()
        assertThat(empty.hasList).isFalse()
        assertThat(ChannelChips.inspectSorted(null).hasList).isFalse()
        assertThat(ChannelChips.inspectSorted("<html>").selected).isNull()
    }

    @Test
    fun theBodyOfASortedAnswerIsHandedOverAsANextPage() {
        val items = Json.parseToJsonElement(ChannelChips.reloadAsAppend(sortedAnswer("Popular"))).jsonObject
            .getValue("onResponseReceivedActions").jsonArray.single().jsonObject
            .getValue("appendContinuationItemsAction").jsonObject
            .getValue("continuationItems").jsonArray
        // Only the body's items: the chip bar of the header slot is not among them, the next page's token ends them.
        assertThat(items).hasSize(2)
        assertThat(items[0].jsonObject.keys).containsExactly("richItemRenderer")
        assertThat(items[1].jsonObject.keys).containsExactly("continuationItemRenderer")
    }

    @Test
    fun anAnswerThatIsNotASortedOneIsHandedOverAsItIs() {
        val next = """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[{"continuationItemRenderer":{}}]}}]}"""
        assertThat(ChannelChips.reloadAsAppend(next)).isEqualTo(next)
        assertThat(ChannelChips.reloadAsAppend("not json")).isEqualTo("not json")
        // A sorted answer without a body slot cannot be turned into a list: left alone, and said not to hold one.
        val headerOnly = """{"onResponseReceivedActions":[${reloadCommand("RELOAD_CONTINUATION_SLOT_HEADER", "[${chipBar(chip("Latest", true, "l"))}]")}]}"""
        assertThat(ChannelChips.reloadAsAppend(headerOnly)).isEqualTo(headerOnly)
        assertThat(ChannelChips.inspectSorted(headerOnly).hasList).isFalse()
        assertThat(ChannelChips.inspectSorted(headerOnly).selected).isEqualTo(ChannelSort.LATEST)
        assertThat(ChannelChips.inspectSorted(sortedAnswer("Oldest")).hasList).isTrue()
        assertThat(ChannelChips.inspectSorted(next).hasList).isFalse()
    }

    // ---- the request -------------------------------------------------------------------------------------------------

    @Test
    fun theTokenIsPostedAsTheExtractorPostsItsNextPages() {
        val body = Json.parseToJsonElement(
            String(ChannelChips.continuationBody("4qmFsgJk%3D", language = "en-GB", country = "SA", clientVersion = "2.20261002.01.00"), Charsets.UTF_8),
        ).jsonObject
        assertThat(body.getValue("continuation").jsonPrimitive.content).isEqualTo("4qmFsgJk%3D")
        val client: JsonObject = body.getValue("context").jsonObject.getValue("client").jsonObject
        assertThat(client.getValue("hl").jsonPrimitive.content).isEqualTo("en-GB")
        assertThat(client.getValue("gl").jsonPrimitive.content).isEqualTo("SA")
        assertThat(client.getValue("clientName").jsonPrimitive.content).isEqualTo("WEB")
        assertThat(client.getValue("clientVersion").jsonPrimitive.content).isEqualTo("2.20261002.01.00")
        assertThat(client.getValue("platform").jsonPrimitive.content).isEqualTo("DESKTOP")
        assertThat(body.getValue("context").jsonObject.keys).containsExactly("client", "request", "user")
    }
}
