package com.ahmadnayfeh.shasha.youtube.newpipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.schabi.newpipe.extractor.Page

class PageStoreTest {

    @Test
    fun noNextPageGivesNoKey() {
        val store = PageStore()
        assertThat(store.put("search:a", null)).isNull()
        assertThat(store.put("search:a", Page(""))).isNull()
    }

    @Test
    fun keysAreScopedToTheirOwner() {
        val store = PageStore()
        val page = Page("https://www.youtube.com/next")
        val key = store.put("search:a", page, "ctx")!!

        assertThat(store.get("search:a", key)).isEqualTo(page to "ctx")
        assertThat(store.get("search:b", key)).isNull()
    }

    @Test
    fun oldestEntriesAreEvicted() {
        val store = PageStore(maxEntries = 2)
        val first = store.put("o", Page("https://1"))!!
        val second = store.put("o", Page("https://2"))!!
        store.put("o", Page("https://3"))

        assertThat(store.get("o", first)).isNull()
        assertThat(store.get("o", second)).isNotNull()
    }
}
