package com.ahmadnayfeh.shasha.ui.format

import com.ahmadnayfeh.shasha.ui.format.Elapsed.TimeUnit
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArabicTimeAndPluralTest {
    private val minutes = arrayOf("منذ دقيقة", "منذ دقيقتين", "منذ %1\$s دقائق", "منذ %1\$s دقيقة", "منذ %1\$s دقيقة")

    @Test
    fun pluralFormsFollowCldrArabic() {
        val forms = mapOf(
            1L to PluralForm.ONE, 2L to PluralForm.TWO,
            3L to PluralForm.FEW, 10L to PluralForm.FEW, 103L to PluralForm.FEW,
            11L to PluralForm.MANY, 99L to PluralForm.MANY, 111L to PluralForm.MANY,
            0L to PluralForm.OTHER, 100L to PluralForm.OTHER, 101L to PluralForm.OTHER, 102L to PluralForm.OTHER,
        )
        forms.forEach { (n, form) -> assertThat(ArabicPlural.formOf(n)).isEqualTo(form) }
    }

    @Test
    fun numbersAreWesternDigitsWhateverTheDefaultLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-SA"))
            assertThat(ArabicPlural.format(minutes, 1)).isEqualTo("منذ دقيقة")
            assertThat(ArabicPlural.format(minutes, 2)).isEqualTo("منذ دقيقتين")
            assertThat(ArabicPlural.format(minutes, 5)).isEqualTo("منذ 5 دقائق")
            assertThat(ArabicPlural.format(minutes, 45)).isEqualTo("منذ 45 دقيقة")
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun largestWholeUnit() {
        val now = 1_790_596_800_000L
        fun at(ms: Long) = Elapsed.between(now - ms, now)
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        assertThat(at(30_000)).isNull()
        assertThat(at(-5 * minute)).isNull() // a clock ahead of ours
        assertThat(at(minute)).isEqualTo(Elapsed(TimeUnit.MINUTES, 1))
        assertThat(at(59 * minute)).isEqualTo(Elapsed(TimeUnit.MINUTES, 59))
        assertThat(at(3 * hour + 20 * minute)).isEqualTo(Elapsed(TimeUnit.HOURS, 3))
        assertThat(at(23 * hour)).isEqualTo(Elapsed(TimeUnit.HOURS, 23))
        assertThat(at(day)).isEqualTo(Elapsed(TimeUnit.DAYS, 1))
        assertThat(at(6 * day)).isEqualTo(Elapsed(TimeUnit.DAYS, 6))
        assertThat(at(7 * day)).isEqualTo(Elapsed(TimeUnit.WEEKS, 1))
        assertThat(at(29 * day)).isEqualTo(Elapsed(TimeUnit.WEEKS, 4))
        assertThat(at(30 * day)).isEqualTo(Elapsed(TimeUnit.MONTHS, 1))
        assertThat(at(359 * day)).isEqualTo(Elapsed(TimeUnit.MONTHS, 11))
        assertThat(at(362 * day)).isEqualTo(Elapsed(TimeUnit.YEARS, 1))
        assertThat(at(4 * 365 * day)).isEqualTo(Elapsed(TimeUnit.YEARS, 4))
    }

    @Test
    fun thumbnailsDependOnlyOnTheVideo() {
        assertThat(Thumbnails.video("dQw4w9WgXcQ")).isEqualTo("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg")
        assertThat(Thumbnails.video("dQw4w9WgXcQ", Thumbnails.Size.SMALL)).isEqualTo("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg")
    }
}
