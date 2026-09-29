package com.ahmadnayfeh.shasha.data

import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncErrorsTest {
    @Test
    fun aStoredErrorReadsBackAsItsKindAndName() {
        val stored = SyncErrors.format(SourceException(SourceErrorKind.BLOCKED_403, "HTTP 403", "refused", null))
        assertThat(stored).isEqualTo("BLOCKED_403: HTTP 403")
        val read = SyncErrors.parse(stored)
        assertThat(read.kind).isEqualTo(SourceErrorKind.BLOCKED_403)
        assertThat(read.technicalName).isEqualTo("HTTP 403")
    }

    @Test
    fun anUnknownKind_isABrokenExtraction() {
        val read = SyncErrors.parse("SOMETHING_NEW: WeirdException")
        assertThat(read.kind).isEqualTo(SourceErrorKind.EXTRACTION_BROKEN)
        assertThat(read.technicalName).isEqualTo("WeirdException")
    }
}
