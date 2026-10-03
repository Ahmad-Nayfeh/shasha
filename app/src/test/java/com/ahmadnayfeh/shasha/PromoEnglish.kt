package com.ahmadnayfeh.shasha

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.ahmadnayfeh.shasha.data.AppLanguage
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assume.assumeTrue
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The English recording of the explainer video's screens (promo/remotion, the English version). The video draws real
 * captures of the app, and its English version needs them in the English interface, with English video titles and
 * channel names. The screenshot tests that make those captures can record them:
 *
 *     ./gradlew :app:recordRoborazziDebug -PshashaPromoEnglish --tests '*ScreenshotTest'
 *
 * Then, for the [SCREENS] only (every other golden test is skipped), the tests run on an English TV (values-en, left to
 * right: [composeRule], [language], [mirror]), read English data ([en], [pick]) and write promo/screens-en/<golden name>.png
 * instead of the golden ([capture]). promo/remotion/sync-screens.sh copies them into the video's project.
 *
 * Without the switch nothing here changes anything: the goldens in src/test/screenshots come out byte for byte the same.
 */
object PromoEnglish {
    /** `-PshashaPromoEnglish` (app/build.gradle.kts hands it to the tests as this system property). */
    val on: Boolean = System.getProperty("shasha.promoEnglish") == "true"

    /** The goldens the video's captures are made from (promo/remotion/src/screens.json, "source"); the others are not recorded. */
    private val SCREENS = setOf(
        "home", "search_results", "player_osd", "player_loading", "player_osd_quality_menu", "player_osd_watch_later_resume",
        "sponsor_skip_undo", "profile_picker", "pin_pad", "kids_home", "profile_edit", "time_warning", "time_up",
        "player_subtitle_controls_hidden", "lock_child_pin", "lock_hold_ring", "lock_phrase_keyboard", "protection_ok",
        "remote_pairing", "setup_phone", "stats", "setup_pin", "setup_child", "error_blocked_403", "updates_downloading",
        "behavior_serious", "channel", "channel_shorts", "search_idle_mic", "search_listening", "profile_edit_shorts",
        "home_suggestions", "settings_hub_updates", "updates_checking", "updates_installing",
    )

    /**
     * Arabic in the English interface too, because the app is: the keyboard that lets an adult out of Serious Mode
     * (ExitPhraseChallenge) only has Arabic letters, and its phrases are Arabic.
     */
    private val ARABIC_BY_DESIGN = setOf("lock_phrase_keyboard")

    /** Where the English captures go, from the app module (the directory the tests run in). */
    private val OUT = File("../promo/screens-en")

    /** `createComposeRule()`, on the English TV (the configuration of `@Config(qualifiers = TV_1080P_EN)`) when recording. */
    fun composeRule(): ComposeContentTestRule {
        // Before the rule starts its activity: an activity reads the configuration it is started with.
        if (on) RuntimeEnvironment.setQualifiers(TV_1080P_EN)
        return createComposeRule()
    }

    /** Skips (does not fail) the test when recording the English video and the golden [name] is not one of its screens. */
    fun assumeRecorded(name: String) = assumeTrue("not one of the English video's screens", !on || name in SCREENS)

    /** [language], or English when recording: what `ShashaTheme(language = …)` is given. */
    fun language(language: AppLanguage = AppLanguage.ARABIC): AppLanguage = if (on) AppLanguage.ENGLISH else language

    /** [arabic], or [english] when recording. */
    fun <T> pick(arabic: T, english: T): T = if (on) english else arabic

    /** [key] for the other side of the screen when recording: the layout runs left to right, so LEFT and RIGHT swap. */
    fun mirror(key: Key): Key = when {
        !on -> key
        key == Key.DirectionLeft -> Key.DirectionRight
        key == Key.DirectionRight -> Key.DirectionLeft
        else -> key
    }

    /**
     * [content] with its text at [scale] of the TV's size when recording, as it is otherwise. English runs longer than
     * Arabic: a screen that does not fit the 1080p TV in English is shown as it is on a TV set to a smaller text size.
     */
    @Composable
    fun smallerText(scale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        if (on) CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * scale), content) else content()
    }

    /**
     * The English of [arabic], Arabic test data (a video title, a channel or profile name, a search), when recording; [arabic]
     * itself otherwise, and for text that has no English here (a Latin title stays as it is). The channels' names are the
     * video's canonical ones.
     */
    fun en(arabic: String): String = if (on) ENGLISH[arabic] ?: arabic else arabic

    /**
     * The golden [name] from [compose]'s screen: src/test/screenshots/<name>.png as always, or, when recording the English
     * video, promo/screens-en/<name>.png (nothing for a golden that is not one of its screens), once it is certain that
     * the capture has no Arabic text left on it. Its elements' bounds go to build/promo-english/<name>.json, to measure the
     * focus rectangles of promo/remotion/src/screens-en.json from.
     */
    fun capture(compose: ComposeTestRule, name: String) {
        if (!on) {
            compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
        } else if (name in SCREENS) {
            val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            if (name !in ARABIC_BY_DESIGN) {
                val arabic = shownTexts(root, root.boundsInRoot).filter(::hasArabic).toList()
                check(arabic.isEmpty()) { "Arabic text on the English capture $name (give it an English in PromoEnglish): $arabic" }
            }
            writeBounds(name, root)
            OUT.mkdirs()
            compose.onRoot().captureRoboImage(File(OUT, "$name.png").path)
        }
    }

    // ---- What is on an English capture ------------------------------------------------------------------------------

    /** The texts of [node] and its elements that are on the [screen] (a list's rows beyond its edge are not on the capture). */
    private fun shownTexts(node: SemanticsNode, screen: Rect): Sequence<String> = sequence {
        if (!node.boundsInRoot.overlaps(screen)) return@sequence
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { yield(it.text) }
        node.config.getOrNull(SemanticsProperties.EditableText)?.let { yield(it.text) }
        node.children.forEach { yieldAll(shownTexts(it, screen)) }
    }

    private fun hasArabic(text: String) = text.any { it in '؀'..'ۿ' || it in 'ﭐ'..'﷿' || it in 'ﹰ'..'﻾' }

    /** Every element of the screen with its bounds in the capture's pixels, and its test tag, text and focus. */
    private fun writeBounds(name: String, root: SemanticsNode) {
        val elements = buildJsonArray {
            fun walk(node: SemanticsNode, depth: Int) {
                val b = node.boundsInRoot
                add(
                    buildJsonObject {
                        put("depth", depth)
                        node.config.getOrNull(SemanticsProperties.TestTag)?.let { put("tag", it) }
                        node.config.getOrNull(SemanticsProperties.Text)?.let { lines -> put("text", lines.joinToString("\n") { it.text }) }
                        node.config.getOrNull(SemanticsProperties.Focused)?.let { put("focused", it) }
                        putJsonArray("bounds") { listOf(b.left, b.top, b.right, b.bottom).forEach { add(JsonPrimitive(it)) } }
                    },
                )
                node.children.forEach { walk(it, depth + 1) }
            }
            walk(root, 0)
        }
        File("build/promo-english", "$name.json").apply { parentFile?.mkdirs() }.writeText(elements.toString())
    }

    // ---- The English of the test data -------------------------------------------------------------------------------

    private val ENGLISH = mapOf(
        // Channels (Space Lab, Math Corner, Woodland Craft... are English already)
        "عالم الطبيعة" to "Nature World",
        "مطبخ سلمى" to "Salma's Kitchen",
        "قناة القرآن الكريم" to "Holy Quran Channel",
        "فنون الخط" to "Calligraphy Arts",
        "رياضة اليوم" to "Sports Today",
        "رحّالة" to "The Traveler",
        "تقنية ببساطة" to "Tech Made Simple",
        "عالم البحار" to "Ocean World",
        "مدرسة الخط" to "Calligraphy School",
        "حكايات قبل النوم" to "Bedtime Stories",
        // The family
        "أحمد" to "Ahmad",
        "سارة" to "Sara",
        "عمر" to "Omar",
        // Videos: a card's title is two lines of about 45 characters
        "رحلة إلى أعماق المحيط: كائنات لم ترها من قبل" to "Journey to the Ocean Depths: Creatures Unseen",
        "بث مباشر: تلاوة خاشعة من الحرم المكي" to "Live: Soulful Recitation from Makkah",
        "وصفة الكنافة النابلسية في البيت خطوة بخطوة" to "Nabulsi Kunafa Recipe at Home, Step by Step",
        "أسرار الخط العربي: الثلث والديواني وكيف تبدأ" to "Calligraphy Secrets: Thuluth & Diwani",
        "ملخص مباراة الأمس: الأهداف والتحليل الكامل" to "Last Night's Match: All the Goals and Full Analysis",
        "حرف الضاد بخط الثلث في 30 ثانية" to "The Letter Dad in Thuluth in 30 Seconds",
        "أعماق المحيط الهادئ: رحلة الغواصة إلى خندق ماريانا" to "Pacific Depths: A Dive to the Mariana Trench",
        "قنديل البحر الذي لا يموت" to "The Jellyfish That Never Dies",
        "تعلّم خط الديواني: الحروف المنفردة" to "Learn Diwani Script: The Isolated Letters",
        "كيف تختار القلم والحبر المناسبين للخط" to "How to Choose the Right Pen and Ink for Calligraphy",
        "خط الرقعة في عشر دقائق" to "Ruq'ah Script in Ten Minutes",
        "لوحة «بسم الله» بخط الثلث من البداية إلى النهاية" to "A “Bismillah” Panel in Thuluth, Start to Finish",
        "تمرين يومي لعشر دقائق يحسّن خطك" to "A Ten-Minute Daily Exercise to Improve Your Script",
        "حكاية الأرنب الشجاع" to "The Story of the Brave Rabbit",
        "لماذا تتغير أشكال القمر؟" to "Why Does the Moon Change Shape?",
        "الدب القطبي وصغاره في الثلج" to "The Polar Bear and Her Cubs in the Snow",
        "حكاية النجمة التي أرادت أن تنام" to "The Star Who Wanted to Sleep",
        "Arabic Calligraphy for Beginners: Your First Letters" to "Arabic Calligraphy for Beginners: First Letters",
        // Playlists
        "دروس الخط العربي للمبتدئين" to "Arabic Calligraphy Lessons for Beginners",
        "تعلم الحروف مع أرنوب" to "Learn Letters with Bunny",
        "تجارب علمية للصغار" to "Kids' Science Experiments",
        // Searches, voice and subtitles
        "الخط العربي" to "Arabic calligraphy",
        "وثائقي عن أعماق" to "a documentary about the deep",
        "العربية" to "Arabic",
        "هذه الكائنات تعيش على عمق أربعة آلاف متر\nحيث لا يصل ضوء الشمس أبداً" to "These creatures live four thousand meters down,\nwhere sunlight never reaches",
        "والآن نعود إلى رحلتنا في أعماق المحيط" to "And now, back to our journey into the ocean depths",
    )
}
