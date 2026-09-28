package com.ahmadnayfeh.shasha.youtube.newpipe

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.search.SearchExtractor

/** "No results" versus a broken extraction, for search, channel and playlist lists. */
class ListResultsTest {

    private val nothingFound = SearchExtractor.NothingFoundException("Try different keywords or remove search filters")

    @Test
    fun youtubesNoResultsAnswerIsAnEmptyPage() {
        val page = NewPipeVideoSource.noResultsIsEmpty<InfoItem> { throw nothingFound }
        assertThat(page.items).isEmpty()
        assertThat(page.nextPage).isNull()
        assertThat(page.errors).isEmpty()
        // And it is not treated as broken afterwards.
        NewPipeVideoSource.failIfBroken(page.items, page.errors)
        NewPipeVideoSource.failIfBroken(emptyList<Any>(), listOf(nothingFound))
    }

    @Test
    fun otherFailuresStillPropagate() {
        val broken = ParsingException("Could not get search results")
        val thrown = assertThrows(ParsingException::class.java) {
            NewPipeVideoSource.noResultsIsEmpty<InfoItem> { throw broken }
        }
        assertThat(thrown).isSameInstanceAs(broken)
        val page = InfoItemsPage<InfoItem>(emptyList(), null, emptyList())
        assertThat(NewPipeVideoSource.noResultsIsEmpty { page }).isSameInstanceAs(page)
    }

    @Test
    fun anEmptyListWithRealErrorsIsBroken() {
        val itemError = ParsingException("Could not get video title")
        // The real error is reported, even when a "nothing found" is logged too.
        val thrown = assertThrows(ParsingException::class.java) {
            NewPipeVideoSource.failIfBroken(emptyList<Any>(), listOf(nothingFound, itemError))
        }
        assertThat(thrown).isSameInstanceAs(itemError)
        // Items that did parse win over errors of the others.
        NewPipeVideoSource.failIfBroken(listOf("video"), listOf(itemError))
        NewPipeVideoSource.failIfBroken(emptyList<Any>(), null)
        NewPipeVideoSource.failIfBroken(emptyList<Any>(), emptyList())
    }

    @Test
    fun errorNamesDoNotDependOnInnerClassMetadata() {
        assertThat(ErrorMapper.technicalName(nothingFound)).isEqualTo("NothingFoundException")
        assertThat(ErrorMapper.technicalName(ParsingException("x"))).isEqualTo("ParsingException")
        // Anonymous classes have no name of their own: the binary name is kept.
        val anonymous = ErrorMapper.technicalName(object : RuntimeException("anonymous") {})
        assertThat(anonymous).startsWith("ListResultsTest$")
        assertThat(anonymous).doesNotContain(".")
    }
}
