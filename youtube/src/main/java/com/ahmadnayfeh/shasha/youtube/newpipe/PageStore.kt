package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.PageKey
import org.schabi.newpipe.extractor.Page
import java.util.UUID

/**
 * Keeps NewPipeExtractor continuation pages in memory behind opaque [PageKey]s.
 * Bounded (LRU) so endless scrolling cannot grow it forever.
 *
 * Each entry records its owner (e.g. "search:videos:cats") and is only returned for that owner, so a
 * key cannot be replayed against another query. `context` holds what the continuation needs besides
 * the Page (query handler, playlist details, …).
 */
internal class PageStore(private val maxEntries: Int = 256) {

    private data class Entry(val owner: String, val page: Page, val context: Any?)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
    }

    /** Returns null when there is no next page. */
    @Synchronized
    fun put(owner: String, page: Page?, context: Any? = null): PageKey? {
        if (!Page.isValid(page)) return null
        requireNotNull(page)
        val id = UUID.randomUUID().toString()
        entries[id] = Entry(owner, page, context)
        return PageKey(id)
    }

    /** The page and its context, or null when the key is unknown, evicted or belongs to another owner. */
    @Synchronized
    fun get(owner: String, key: PageKey): Pair<Page, Any?>? =
        entries[key.id]?.takeIf { it.owner == owner }?.let { it.page to it.context }
}
