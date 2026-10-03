package com.ahmadnayfeh.shasha.youtube.newpipe

/**
 * What a caller wants to see of YouTube's InnerTube "browse" answers during one [BrowseTaps.watch] call: [lastAnswer] is the
 * latest one as it arrived, and [reshape] turns each answer into what the extractor is handed (the identity unless the
 * caller has to dress an answer the extractor cannot read as one it can).
 */
internal class BrowseTap(val reshape: (String) -> String = { it }) {
    var lastAnswer: String? = null
        internal set
}

/**
 * Lets one blocking extractor call be watched: the HTTP layer ([NewPipeDownloader]) hands every answer to the "browse"
 * endpoint (channel pages, their sections and their next pages) to [answered], and the [BrowseTap] that is watching this
 * thread sees it, and may reshape it, before the extractor does. Requests to anything else, and every other thread, are
 * left alone; the tap ends with the call.
 *
 * NewPipeExtractor reads a channel's sections in YouTube's own order only; the answers carry more (the order chips,
 * see [ChannelChips]) and the extractor offers no door to them but this.
 */
internal class BrowseTaps {
    private val current = ThreadLocal<BrowseTap?>()

    /** Runs [block] (blocking extractor code, on this thread) with [tap] watching the browse answers its requests get. */
    fun <T> watch(tap: BrowseTap, block: () -> T): T {
        current.set(tap)
        try {
            return block()
        } finally {
            current.remove()
        }
    }

    /** The answer for the extractor: [body] of a request to [url], seen by the tap watching this thread, if any. */
    fun answered(url: String, body: String): String {
        val tap = current.get()
        if (tap == null || BROWSE_PATH !in url) return body
        tap.lastAnswer = body
        return tap.reshape(body)
    }

    private companion object {
        /** InnerTube's browse endpoint. */
        const val BROWSE_PATH = "/youtubei/v1/browse"
    }
}
