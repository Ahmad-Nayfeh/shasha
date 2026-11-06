package com.ahmadnayfeh.shasha.mobile.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorCategory
import com.ahmadnayfeh.shasha.mobile.ui.about.AboutLinks
import com.ahmadnayfeh.shasha.mobile.ui.about.Licenses
import com.ahmadnayfeh.shasha.mobile.ui.about.OtherApps
import com.ahmadnayfeh.shasha.mobile.update.UpdateErrorKind
import com.ahmadnayfeh.shasha.mobile.ui.update.updateErrorText
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** The two languages' texts, read from the files themselves (plain JVM). */
class StringsParityTest {

    private data class Entry(val name: String, val value: String, val translatable: Boolean)

    private fun read(path: String): Pair<List<Entry>, Set<String>> {
        val text = File(path).readText()
        val strings = Regex("""<string name="([a-z0-9_]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(text)
            .map { Entry(it.groupValues[1], it.groupValues[3], !it.groupValues[2].contains("translatable=\"false\"")) }.toList()
        val plurals = Regex("""<plurals name="([a-z0-9_]+)">""").findAll(text).map { it.groupValues[1] }.toSet()
        return strings to plurals
    }

    private val english = read("src/main/res/values/strings.xml")
    private val arabic = read("src/main/res/values-ar/strings.xml")

    private fun placeholders(value: String) = Regex("""%\d\$[sd]""").findAll(value).map { it.value }.sorted().toList()

    @Test
    fun everyTranslatableTextIsInBothLanguages() {
        val translatable = english.first.filter { it.translatable }.map { it.name }.toSet()

        assertThat(arabic.first.map { it.name }.toSet()).isEqualTo(translatable)
        assertThat(arabic.second).isEqualTo(english.second)
    }

    @Test
    fun theSameNumbersAreFilledInInBothLanguages() {
        val arabicByName = arabic.first.associateBy { it.name }
        for (entry in english.first) {
            val other = arabicByName[entry.name] ?: continue
            assertWithMessage(entry.name).that(placeholders(other.value)).isEqualTo(placeholders(entry.value))
        }
    }

    @Test
    fun noTextPrintsNumbersWithPercentDBecauseArabicWouldUseArabicIndicDigits() {
        for (entry in english.first + arabic.first) {
            assertWithMessage(entry.name).that(entry.value).doesNotContain("%d")
            assertWithMessage(entry.name).that(Regex("""%\d\${'$'}d""").containsMatchIn(entry.value)).isFalse()
        }
    }

    @Test
    fun noUserTextNamesWhereTheUpdatesComeFrom() {
        for (entry in english.first + arabic.first) {
            assertWithMessage(entry.name).that(entry.value.lowercase()).doesNotContain("github")
            assertWithMessage(entry.name).that(entry.value).doesNotContain("غيت هب")
        }
    }

    @Test
    fun arabicHasAllSixPluralFormsOfEveryTime() {
        val text = File("src/main/res/values-ar/strings.xml").readText()
        for (name in arabic.second) {
            val block = Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).find(text)!!.groupValues[1]
            for (quantity in listOf("zero", "one", "two", "few", "many", "other")) {
                assertWithMessage("$name $quantity").that(block).contains("quantity=\"$quantity\"")
            }
        }
    }
}

private fun appContext(): Context = ApplicationProvider.getApplicationContext()

/** About, in English: the credit, the tagline, the attribution and the links of the developer's other apps. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class AboutEnglishTest {
    @Test
    fun theDeveloperCreditAndTheTagline() {
        assertThat(appContext().getString(R.string.about_developer_name)).isEqualTo("Ahmad Nayfeh")
        assertThat(appContext().getString(R.string.about_made_with_love)).isEqualTo("Made with love for my family")
        assertThat(appContext().getString(R.string.app_name)).isEqualTo("Shasha")
    }

    @Test
    fun theAttributionSponsorBlockAsksForIsWordForWord() {
        assertThat(appContext().getString(R.string.sponsorblock_attribution_exact))
            .isEqualTo("Uses SponsorBlock data licensed used under CC BY-NC-SA 4.0 from https://sponsor.ajay.app/.")
        assertThat(appContext().getString(R.string.sponsorblock_attribution)).contains("CC BY-NC-SA 4.0")
    }

    @Test
    fun everyCategoryHasANameAndAnExplanation() {
        for (category in SponsorCategory.entries) {
            assertThat(appContext().getString(category.label)).isNotEmpty()
            assertThat(appContext().getString(category.description)).isNotEmpty()
        }
    }

    @Test
    fun everyUpdateFailureHasAText() {
        for (kind in UpdateErrorKind.entries) assertThat(appContext().getString(updateErrorText(kind))).isNotEmpty()
    }

    /** The device's own decoder failing (SourceErrorKind.DEVICE_DECODER) is not YouTube changing something, and no update helps. */
    @Test
    fun theDecoderFailureBlamesTheDeviceNotYouTube() {
        val text = appContext().getString(R.string.error_device_decoder)
        assertThat(text).contains("device")
        assertThat(text).doesNotContain("YouTube")
        assertThat(text).doesNotContain("update")
    }
}

/** About, in Arabic. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ar")
class AboutArabicTest {
    @Test
    fun theDeveloperCreditAndTheTagline() {
        assertThat(appContext().getString(R.string.about_developer_name)).isEqualTo("احمد نايفه")
        assertThat(appContext().getString(R.string.about_made_with_love)).isEqualTo("صُنع بحب لعائلتي")
        assertThat(appContext().getString(R.string.app_name)).isEqualTo("شاشة")
    }

    @Test
    fun theAttributionStaysInEnglishWhateverTheLanguage() {
        assertThat(appContext().getString(R.string.sponsorblock_attribution_exact))
            .isEqualTo("Uses SponsorBlock data licensed used under CC BY-NC-SA 4.0 from https://sponsor.ajay.app/.")
    }

    @Test
    fun theDecoderFailureBlamesTheDeviceNotYouTube() {
        val text = appContext().getString(R.string.error_device_decoder)
        assertThat(text).isEqualTo("تعذّر على جهازك تشغيل هذا الفيديو. جرّب فيديو آخر؛ وإن تكرر فأعد تشغيل الجهاز.")
        assertThat(text).doesNotContain("يوتيوب")
        assertThat(text).doesNotContain("تحديث")
    }

    @Test
    fun theSectionsAreTranslated() {
        assertThat(appContext().getString(R.string.about_title)).isEqualTo("حول")
        assertThat(appContext().getString(R.string.about_more_apps)).isEqualTo("تطبيقات أخرى من المطوّر")
        assertThat(appContext().getString(R.string.update_title)).isEqualTo("فحص التحديثات")
    }
}

/** The links of the developer's other apps are exactly these. */
class AboutLinksTest {
    @Test
    fun theOtherAppsAndTheirLinks() {
        assertThat(OtherApps.all.map { it.name }).containsExactly("Shadra", "Silah", "Masarif", "Khatwa").inOrder()
        val links = OtherApps.all.associate { it.name to it.links.map { link -> link.url } }
        assertThat(links["Shadra"]).containsExactly("https://shadhra.app/", "https://play.google.com/store/apps/details?id=app.shadhra").inOrder()
        assertThat(links["Silah"]).containsExactly("https://github.com/Ahmad-Nayfeh/silah")
        assertThat(links["Masarif"]).containsExactly("https://github.com/Ahmad-Nayfeh/masarif")
        assertThat(links["Khatwa"]).containsExactly("https://github.com/Ahmad-Nayfeh/khatwa")
        assertThat(AboutLinks.SPONSORBLOCK).isEqualTo("https://sponsor.ajay.app/")
    }

    @Test
    fun theLicencesCoverWhatTheAppIsBuiltOn() {
        val names = Licenses.libraries.map { it.name }
        assertThat(names).contains("NewPipeExtractor")
        assertThat(Licenses.libraries.first { it.name == "NewPipeExtractor" }.license).isEqualTo("GPL-3.0-or-later")
        assertThat(Licenses.libraries.map { it.license }.all { it.isNotBlank() }).isTrue()
    }
}
