package com.ahmadnayfeh.shasha.ui.format

import java.util.Locale

/**
 * Arabic plural categories (CLDR), chosen in code: the app is Arabic whatever the TV's language, and
 * Android's plurals resources would follow the system locale's rules (English has only one/other).
 *
 * Every counted phrase is a string-array in strings.xml with one item per form, in this order.
 */
enum class PluralForm {
    /** 1: «دقيقة» */
    ONE,

    /** 2: «دقيقتين» */
    TWO,

    /** n % 100 in 3..10: «3 دقائق» */
    FEW,

    /** n % 100 in 11..99: «11 دقيقة», «11 يوماً» */
    MANY,

    /** Everything else (0, 100, 101, 102, …): «100 يوم» */
    OTHER,
}

object ArabicPlural {
    fun formOf(n: Long): PluralForm = when {
        n == 1L -> PluralForm.ONE
        n == 2L -> PluralForm.TWO
        n % 100 in 3..10 -> PluralForm.FEW
        n % 100 in 11..99 -> PluralForm.MANY
        else -> PluralForm.OTHER
    }

    /**
     * Picks the item of [forms] (a string-array in [PluralForm] order) for [n] and fills in the number,
     * always with Western digits.
     */
    fun format(forms: Array<String>, n: Long, number: String = n.toString()): String {
        require(forms.size == PluralForm.entries.size) { "Expected ${PluralForm.entries.size} plural forms, got ${forms.size}" }
        return String.format(Locale.ROOT, forms[formOf(n).ordinal], number)
    }
}
