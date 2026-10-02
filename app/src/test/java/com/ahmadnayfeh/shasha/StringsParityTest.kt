package com.ahmadnayfeh.shasha

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every user-facing text exists in Arabic (res/values) and in English (res/values-en), with the same placeholders:
 * what lint reports as MissingTranslation, ExtraTranslation and StringFormatMatches, checked here too so that a test
 * run says it without a lint run. Texts that are never translated carry translatable="false" in the Arabic file.
 */
class StringsParityTest {
    private class Entry(val kind: String, val items: List<String>, val translatable: Boolean)

    private fun load(folder: String): Map<String, Entry> {
        val res = File("src/main/res/$folder")
        assertWithMessage("res folder $res (the tests run in the app module)").that(res.isDirectory).isTrue()
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val out = LinkedHashMap<String, Entry>()
        for (file in res.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }!!.sortedBy { it.name }) {
            val root = builder.parse(file).documentElement
            for (i in 0 until root.childNodes.length) {
                val node = root.childNodes.item(i) as? Element ?: continue
                val kind = node.tagName
                if (kind != "string" && kind != "string-array" && kind != "plurals") continue
                val name = node.getAttribute("name")
                val items = if (kind == "string") listOf(node.textContent) else (0 until node.childNodes.length).mapNotNull {
                    (node.childNodes.item(it) as? Element)?.takeIf { item -> item.tagName == "item" }?.textContent
                }
                assertWithMessage("$file: $kind $name is defined twice").that(out.containsKey("$kind:$name")).isFalse()
                out["$kind:$name"] = Entry(kind, items, node.getAttribute("translatable") != "false")
            }
        }
        return out
    }

    private val arabic = load("values")
    private val english = load("values-en")

    /** `%1$s`, `%2$d`, `%%`: the placeholders of one text, in a comparable form. */
    private fun placeholders(text: String): List<String> =
        Regex("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z%])").findAll(text).map { it.groupValues[1] + "$" + it.groupValues[2] }.sorted().toList()

    @Test
    fun everyArabicText_hasItsEnglishOne() {
        val missing = arabic.filter { (key, entry) -> entry.translatable && key !in english }.keys
        assertWithMessage("in values/ but not in values-en/").that(missing).isEmpty()
    }

    @Test
    fun noEnglishText_isWithoutItsArabicOne_orTranslatesWhatIsNotTranslatable() {
        val extra = english.keys.filter { it !in arabic }
        assertWithMessage("in values-en/ but not in values/").that(extra).isEmpty()
        val needless = english.filter { (key, _) -> arabic[key]?.translatable == false }.keys
        assertWithMessage("translatable=\"false\" in values/, yet translated").that(needless).isEmpty()
    }

    @Test
    fun theTwoLanguages_takeTheSamePlaceholders_andTheSameNumberOfItems() {
        for ((key, base) in arabic) {
            val other = english[key] ?: continue
            assertWithMessage("$key: number of items").that(other.items.size).isEqualTo(base.items.size)
            base.items.zip(other.items).forEachIndexed { index, (ar, en) ->
                assertWithMessage("$key[$index]: placeholders of «$ar» and «$en»").that(placeholders(en)).isEqualTo(placeholders(ar))
            }
        }
    }

    @Test
    fun noEnglishText_isEmpty_orLeftInArabic() {
        val arabicLetters = Regex("[\\u0600-\\u06FF]")
        // The one text that names the Arabic language in its own letters, on purpose: «English or العربية, dark or light».
        val allowed = setOf("string:hub_appearance_summary")
        for ((key, entry) in english) {
            entry.items.forEachIndexed { index, text ->
                assertWithMessage("$key[$index] is empty").that(text.isBlank()).isFalse()
                if (key !in allowed) assertWithMessage("$key[$index] has Arabic letters: $text").that(arabicLetters.containsMatchIn(text)).isFalse()
            }
        }
        assertThat(english.keys).containsAtLeast("string:voice_language_short_ar", "string:voice_language_short_en")
    }
}
