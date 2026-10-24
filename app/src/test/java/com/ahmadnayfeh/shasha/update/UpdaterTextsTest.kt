package com.ahmadnayfeh.shasha.update

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What the updater says, in both languages: plain words for people, never the name of the service the releases come from,
 * never a command, a key or a file type; and it says what is about to happen (the app closes for a moment, then opens
 * again). Nobody is ever sent to where the files are kept: the app fetches them itself.
 */
class UpdaterTextsTest {
    private fun texts(folder: String): Map<String, String> {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val out = LinkedHashMap<String, String>()
        val files = File("src/main/res/$folder").listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }!!
        for (file in files.sortedBy { it.name }) {
            val root = builder.parse(file).documentElement
            for (i in 0 until root.childNodes.length) {
                val node = root.childNodes.item(i) as? Element ?: continue
                if (node.tagName == "string") out[node.getAttribute("name")] = node.textContent
            }
        }
        return out
    }

    private val arabic = texts("values")
    private val english = texts("values-en")

    private fun updater(all: Map<String, String>) = all.filterKeys { it.startsWith("updates_") || it.startsWith("hub_updates_") || it.startsWith("hub_tech_") }

    @Test
    fun noTextSendsAnyoneToGitHub() {
        // The only mention is the label under a QR code that leads to the developer's other apps (not translated).
        for ((language, all) in listOf("ar" to arabic, "en" to english)) {
            val mentions = all.filter { (_, text) -> text.contains("github", ignoreCase = true) }.keys
            assertWithMessage("$language texts that name GitHub").that(mentions - "about_qr_github").isEmpty()
        }
    }

    @Test
    fun theUpdatersTexts_haveNoCommandsKeysOrFileTypes() {
        val banned = listOf("adb", "apk", "token", "secret", "releases", "UPDATE_", "appops")
        for ((language, all) in listOf("ar" to arabic, "en" to english)) {
            for ((name, text) in updater(all)) {
                for (word in banned) {
                    assertWithMessage("$language $name: «$text» has «$word»").that(text.contains(word, ignoreCase = true)).isFalse()
                }
            }
        }
    }

    @Test
    fun beforeTheHandOver_theAppSaysItClosesForAMomentAndOpensAgain() {
        for (key in listOf("updates_downloading_body", "updates_installing_body", "updates_asked_body")) {
            assertWithMessage("ar $key").that(arabic.getValue(key)).contains("يُغلق التطبيق لحظة ثم يُفتح من جديد")
            assertWithMessage("en $key").that(english.getValue(key)).containsMatch("clos\\w* for a moment, then open\\w* again")
        }
    }

    @Test
    fun theMissingPermission_sendsToTheInstallerKit_inPlainWords() {
        assertThat(arabic.getValue("updates_not_allowed_body")).contains("حقيبة التثبيت")
        assertThat(english.getValue("updates_not_allowed_body")).contains("installer kit")
        assertThat(arabic.getValue("updates_up_to_date_title")).isEqualTo("لديك أحدث إصدار")
    }

    @Test
    fun everyErrorHasItsRetryInThePlainTexts_andNoneIsEmpty() {
        val kinds = UpdateErrorKind.entries.map { it.name.lowercase() }
        val keyOf = mapOf(
            "network" to "network", "forbidden" to "forbidden", "not_found" to "not_found",
            "no_apk" to "no_apk", "bad_answer" to "bad_answer", "download_failed" to "download",
            "bad_apk" to "bad_apk", "wrong_package" to "wrong_package", "not_newer" to "not_newer",
            "signature_mismatch" to "signature", "install_failed" to "install",
        )
        assertThat(keyOf.keys).containsExactlyElementsIn(kinds)
        for (stem in keyOf.values) {
            for (all in listOf(arabic, english)) {
                assertThat(all.getValue("updates_error_${stem}_title")).isNotEmpty()
                assertThat(all.getValue("updates_error_${stem}_body")).isNotEmpty()
            }
        }
    }
}
