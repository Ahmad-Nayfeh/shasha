package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.ChannelSort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The order chips of a channel's section («Latest», «Popular», «Oldest») in YouTube's own answers, which NewPipeExtractor
 * does not read. Everything here is pure text in, text out; nothing fails: an answer that has no chips (or does not look
 * as expected) simply offers no sort, and the section stays in YouTube's own order.
 *
 * What YouTube sends. A channel section's first answer (browse with the tab's `params`) has the chip bar in the header of
 * the selected tab's grid: `chipBarViewModel.chips[].chipViewModel` (older: `feedFilterChipBarRenderer.contents[]
 * .chipCloudChipRenderer`), each chip with a text, whether it is selected and, in its tap command, a continuation token.
 * Posting a chip's token to browse answers the section again in that order, as two `reloadContinuationItemsCommand`
 * actions: the header slot (the chip bar, the chosen chip now selected) and the body slot (the items, then the token of
 * their next page). NewPipeExtractor reads the next pages of such a list well (`appendContinuationItemsAction`), so the
 * body is handed to it dressed as that ([reloadAsAppend]). The text of the chips is YouTube's English: the extractor's
 * only supported language is en-GB (see [NewPipeInit]).
 */
internal object ChannelChips {

    /** One chip of the bar. [token] is null where it has no tap command. */
    data class Chip(val sort: ChannelSort, val selected: Boolean, val token: String?)

    /**
     * The orders a section offers: [offered] (always starts with [ChannelSort.LATEST]) and the [tokens] that ask for the
     * others. [NONE] where the answer had no chip bar (very few items; the playlists tab).
     */
    class Sorts(val offered: List<ChannelSort>, val tokens: Map<ChannelSort, String>) {
        companion object {
            val NONE = Sorts(listOf(ChannelSort.LATEST), emptyMap())
        }
    }

    private const val BODY_SLOT = "RELOAD_CONTINUATION_SLOT_BODY"
    private const val HEADER_SLOT = "RELOAD_CONTINUATION_SLOT_HEADER"

    /** The sorts the selected tab of a channel's first [answer] offers: [Sorts.NONE] when it has no usable chips. */
    fun sortsOf(answer: String?): Sorts = sortsOf(chipsOfFirstAnswer(answer))

    fun sortsOf(chips: List<Chip>): Sorts {
        // A chip bar always starts at YouTube's own order, which needs no token (it is the section's plain first page).
        val withToken = chips.filter { it.sort != ChannelSort.LATEST && !it.token.isNullOrEmpty() }.associate { it.sort to it.token!! }
        if (withToken.isEmpty()) return Sorts.NONE
        val offered = ChannelSort.entries.filter { it == ChannelSort.LATEST || it in withToken }
        return Sorts(offered, withToken)
    }

    /** The chips in the header of the selected tab's grid in a channel section's first [answer]; none when it has no chip bar. */
    fun chipsOfFirstAnswer(answer: String?): List<Chip> {
        // A section's answer is about a megabyte: most have no chip bar (either shape has "hipBar" in its name), and say so at once.
        if (answer == null || "hipBar" !in answer) return emptyList()
        val root = parse(answer) ?: return emptyList()
        val tabs = root.obj("contents").obj("twoColumnBrowseResultsRenderer").arr("tabs") ?: return emptyList()
        val selected = tabs.firstNotNullOfOrNull { tab -> tab.obj("tabRenderer")?.takeIf { it.flag("selected") } } ?: return emptyList()
        return chipsOf(selected.obj("content").obj("richGridRenderer").obj("header"))
    }

    /** What the answer to a chip's token says of itself: [hasList] (the body slot's items are there) and which chip it says is [selected] now (null: it says none). */
    class SortedAnswer(val hasList: Boolean, val selected: ChannelSort?)

    /** Reads the answer to a chip's token once: whether it holds the sorted list, and which chip its header slot marks selected. */
    fun inspectSorted(answer: String?): SortedAnswer {
        val root = parse(answer) ?: return SortedAnswer(hasList = false, selected = null)
        val header = root.arr("onResponseReceivedActions")?.firstNotNullOfOrNull { action ->
            action.obj("reloadContinuationItemsCommand")?.takeIf { it.text("slot") == HEADER_SLOT }
        }
        val chips = header?.arr("continuationItems")?.firstNotNullOfOrNull { item -> chipsOf(item as? JsonObject).takeIf { it.isNotEmpty() } }
        return SortedAnswer(hasList = bodyItems(root) != null, selected = chips?.firstOrNull { it.selected }?.sort)
    }

    /**
     * The answer to a chip's token as an `appendContinuationItemsAction` answer, which NewPipeExtractor reads like any later
     * page of the list: only the body slot's items, in that shape. Any other answer (the next pages, which already are
     * that; or one that does not look as expected) comes back unchanged.
     */
    fun reloadAsAppend(answer: String): String {
        val items = bodyItems(parse(answer)) ?: return answer
        return buildJsonObject {
            putJsonArray("onResponseReceivedActions") {
                add(
                    buildJsonObject {
                        putJsonObject("appendContinuationItemsAction") { put("continuationItems", items) }
                    },
                )
            }
        }.toString()
    }

    /**
     * The body of a request that posts a chip's [token] to browse, as NewPipeExtractor builds its continuation requests
     * (InnerTube's WEB client: [language], [country] and the [clientVersion] the extractor reads from YouTube).
     */
    fun continuationBody(token: String, language: String, country: String, clientVersion: String): ByteArray = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("hl", language)
                put("gl", country)
                put("clientName", "WEB")
                put("clientVersion", clientVersion)
                put("originalUrl", "https://www.youtube.com")
                put("platform", "DESKTOP")
                put("utcOffsetMinutes", 0)
            }
            putJsonObject("request") {
                put("internalExperimentFlags", buildJsonArray { })
                put("useSsl", true)
            }
            putJsonObject("user") { put("lockedSafetyMode", false) }
        }
        put("continuation", token)
    }.toString().toByteArray(Charsets.UTF_8)

    /** What a chip's [text] orders by, in YouTube's English; null for anything else (a topic chip, a filter). */
    fun sortOf(text: String?): ChannelSort? = when (text?.trim()?.lowercase()) {
        "latest", "newest", "date added (newest)" -> ChannelSort.LATEST
        "popular", "most popular", "most viewed" -> ChannelSort.POPULAR
        "oldest", "date added (oldest)" -> ChannelSort.OLDEST
        else -> null
    }

    // ---- The two shapes of a chip bar ------------------------------------------------------------------------------

    private fun chipsOf(header: JsonObject?): List<Chip> {
        if (header == null) return emptyList()
        val chips = header.obj("chipBarViewModel").arr("chips")
        if (chips != null) {
            return chips.mapNotNull { it.obj("chipViewModel") }.mapNotNull { chip ->
                val sort = sortOf(chip.text("text")) ?: return@mapNotNull null
                val token = chip.obj("tapCommand").obj("innertubeCommand").obj("continuationCommand").text("token")
                Chip(sort, chip.flag("selected"), token)
            }
        }
        val old = header.obj("feedFilterChipBarRenderer").arr("contents")
        return old.orEmpty().mapNotNull { it.obj("chipCloudChipRenderer") }.mapNotNull { chip ->
            val label = chip.obj("text")
            val sort = sortOf(label.text("simpleText") ?: label.arr("runs")?.firstOrNull().text("text")) ?: return@mapNotNull null
            val token = (chip.obj("navigationEndpoint") ?: chip.obj("onTap")).obj("continuationCommand").text("token")
            Chip(sort, chip.flag("isSelected"), token)
        }
    }

    /** The items of the body slot of a sorted answer (a section in the new order); null where there is no such slot. */
    private fun bodyItems(root: JsonObject?): JsonArray? =
        root.arr("onResponseReceivedActions")?.firstNotNullOfOrNull { action ->
            action.obj("reloadContinuationItemsCommand")?.takeIf { it.text("slot") == BODY_SLOT }?.arr("continuationItems")
        }

    // ---- JSON helpers: every step may be missing, so every step is nullable -------------------------------------------

    private fun parse(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        return try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

    private fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray

    private fun JsonElement?.text(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.flag(key: String): Boolean = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull == true
}
