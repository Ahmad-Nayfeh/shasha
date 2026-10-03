package com.ahmadnayfeh.shasha.youtube

import kotlin.math.pow

/**
 * «مقترحة لك» from the watch history (owner's design: the newer a viewing, the more it counts). What YouTube suggests
 * beside each watched video ([VideoSource.related]) is ranked here, each list weighted by how recent its viewing is: a
 * video watched today counts fully, one watched [halfLifeDays] ago half, twice that a quarter. A suggestion that comes
 * back beside several watched videos adds their weights, and YouTube's own order counts too (its first suggestions
 * weigh more than its last).
 *
 * Pure: the apps fetch and keep the related lists (the TV's HomeSuggestions, the phone's own), and pass every list
 * through their content rules afterwards. Shared by both apps so their homes suggest the same way.
 */
object Recommendations {

    /** A watched video and when it was watched (the history's newest viewing of it). */
    data class Seed(val videoId: String, val watchedAtMs: Long)

    const val DEFAULT_HALF_LIFE_DAYS = 7.0
    const val DEFAULT_PER_CHANNEL = 3
    private const val DAY_MS = 24 * 60 * 60 * 1000.0

    /** How much one viewing counts [ageMs] after it: 1 now, ½ after [halfLifeDays], never below 0 or above 1. */
    fun recencyWeight(ageMs: Long, halfLifeDays: Double = DEFAULT_HALF_LIFE_DAYS): Double =
        0.5.pow(ageMs.coerceAtLeast(0) / DAY_MS / halfLifeDays)

    /** How much YouTube's [index]-th suggestion beside one video counts: 1 for its first, ⅓ around its 8th. */
    fun positionWeight(index: Int): Double = 1.0 / (1.0 + index / 4.0)

    /**
     * The suggestions of [related] (watched video id → YouTube's list beside it, in YouTube's order), best first.
     *
     * A suggestion's score is the sum, over the watched videos that suggest it, of
     * [recencyWeight] (that viewing) × [positionWeight] (its place in that list). Then:
     * - watched videos ([exclude]) and the seeds themselves never come back;
     * - Shorts ([VideoItem.isShortForm]) only when [shorts] is true (the profile shows them), live streams always;
     * - at most [perChannel] videos of one channel, so one channel does not fill the row;
     * - ties keep the order of first appearance (seeds newest first, then YouTube's order), so the row is stable.
     */
    fun rank(
        seeds: List<Seed>,
        related: Map<String, List<VideoItem>>,
        nowMs: Long,
        exclude: Set<String> = emptySet(),
        shorts: Boolean = true,
        limit: Int = 60,
        halfLifeDays: Double = DEFAULT_HALF_LIFE_DAYS,
        perChannel: Int = DEFAULT_PER_CHANNEL,
    ): List<VideoItem> {
        val seedIds = seeds.mapTo(HashSet()) { it.videoId }
        val scores = LinkedHashMap<String, Double>()
        val items = HashMap<String, VideoItem>()
        for (seed in seeds.sortedByDescending { it.watchedAtMs }) {
            val list = related[seed.videoId] ?: continue
            val recency = recencyWeight(nowMs - seed.watchedAtMs, halfLifeDays)
            list.forEachIndexed { index, item ->
                val id = item.videoId
                if (id in seedIds || id in exclude) return@forEachIndexed
                if (item.isShortForm && !shorts) return@forEachIndexed
                items.putIfAbsent(id, item)
                scores[id] = (scores[id] ?: 0.0) + recency * positionWeight(index)
            }
        }
        val order = scores.keys.withIndex().associate { (i, id) -> id to i }
        val perChannelCount = HashMap<String, Int>()
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { order.getValue(it.key) })
            .asSequence()
            .map { items.getValue(it.key) }
            .filter { item ->
                val channel = item.channelId ?: return@filter true
                val n = perChannelCount.getOrDefault(channel, 0)
                if (n >= perChannel) false else true.also { perChannelCount[channel] = n + 1 }
            }
            .take(limit)
            .toList()
    }
}
