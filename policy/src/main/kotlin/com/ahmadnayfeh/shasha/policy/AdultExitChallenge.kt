package com.ahmadnayfeh.shasha.policy

import kotlin.random.Random

/**
 * SPEC section 4, adult profile: leaving a video before it ends needs a 10 second long press and
 * then typing a short phrase with the remote; completing it is recorded as a [SurrenderEvent].
 *
 * The phrases only use [KEYBOARD_LETTERS] (the 28 basic letters, no hamza on a carrier, no ة/ى,
 * no tashkeel) so the custom on-screen keyboard can stay small.
 */
object AdultExitChallenge {

    const val HOLD_MILLIS = 10_000L

    /** Letters the on-screen keyboard needs, in alphabetical order. */
    const val KEYBOARD_LETTERS = "ابتثجحخدذرزسشصضطظعغفقكلمنهوي"

    val phrases: List<String> = listOf(
        "الوقت ثمين",
        "يكفي لهذا اليوم",
        "حان وقت النوم",
        "قم وتحرك قليلا",
        "الوقت لا يعود",
        "وقتك من ذهب",
        "الصبر مفتاح الفرج",
        "من جد وجد",
        "النجاح يحتاج صبرا",
        "لدي عمل مهم",
    )

    /** Only the adult profile, and only when leaving before the video has finished. */
    fun isRequired(profile: Profile, videoFinished: Boolean): Boolean = profile.isAdult && !videoFinished

    fun isHoldComplete(heldMillis: Long): Boolean = heldMillis >= HOLD_MILLIS

    /** 0.0 to 1.0, for the progress ring while the button is held. */
    fun holdProgress(heldMillis: Long): Float = (heldMillis.toFloat() / HOLD_MILLIS).coerceIn(0f, 1f)

    fun randomPhrase(random: Random): String = phrases.random(random)

    /** Same folding as keyword matching, plus trimmed/collapsed spaces; empty input never matches. */
    fun matches(expected: String, typed: String): Boolean {
        val normalizedTyped = TextNormalizer.normalize(typed)
        return normalizedTyped.isNotEmpty() && normalizedTyped == TextNormalizer.normalize(expected)
    }
}
