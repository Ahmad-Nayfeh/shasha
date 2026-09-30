package com.ahmadnayfeh.shasha.remote.qa

import com.ahmadnayfeh.shasha.remote.RemoteChoice
import com.ahmadnayfeh.shasha.remote.RemoteSetting
import com.ahmadnayfeh.shasha.remote.RemoteSettingGroup
import com.ahmadnayfeh.shasha.remote.RemoteSettingKind
import com.ahmadnayfeh.shasha.remote.RemoteSettingsPage
import com.ahmadnayfeh.shasha.remote.RemoteSettingsResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The TV's settings as the demo shows them: the same groups, keys, kinds and checks as the app's PhoneSettings (the
 * labels abridged). Only for visual QA of the phone page.
 */
internal class DemoSettings {
    private class Def(
        val group: String,
        val key: String,
        val label: String,
        val kind: RemoteSettingKind,
        val default: JsonElement,
        val help: String? = null,
        val choices: List<Pair<JsonElement, String>> = emptyList(),
        val min: Int? = null,
        val max: Int? = null,
        val minCount: Int? = null,
        val maxCount: Int? = null,
        val maxLength: Int? = null,
        val visibleWhen: Map<String, JsonElement> = emptyMap(),
        val warning: String? = null,
    )

    private val on = JsonPrimitive(true)
    private val serious = mapOf("serious_mode" to on)
    private val escape = serious + ("adult_escape" to on)

    private fun seconds(n: Int) = when {
        n == 1 -> "ثانية واحدة"
        n == 2 -> "ثانيتان"
        n in 3..10 -> "$n ثوانٍ"
        else -> "$n ثانية"
    }

    private fun minutes(n: Int) = when {
        n == 1 -> "دقيقة واحدة"
        n == 2 -> "دقيقتان"
        n in 3..10 -> "$n دقائق"
        n >= 60 && n % 60 == 0 -> if (n == 60) "ساعة" else "${n / 60} ساعات"
        else -> "$n دقيقة"
    }

    private fun ints(values: List<Int>, label: (Int) -> String) = values.map { JsonPrimitive(it) to label(it) }

    private val phrasesDefault = listOf("انا اخترت الخروج", "سأكمل لاحقا", "لا اريد الاكمال الان")

    private val defs = listOf(
        Def("serious", "serious_mode", "الوضع الجاد", RemoteSettingKind.SWITCH, on,
            help = "الطفل يخرج من الفيديو برمز الوالدين، والكبير بالضغط المطوّل ثم كتابة عبارة. عند الإيقاف يخرج الجميع بزر «رجوع».",
            warning = "الوضع الجاد متوقف: زر «رجوع» يُخرج من أي فيديو في كل الملفات."),
        Def("serious", "adult_escape", "السماح للكبير بالخروج قبل النهاية", RemoteSettingKind.SWITCH, on, visibleWhen = serious,
            help = "بالضغط المطوّل ثم كتابة عبارة، ويُسجَّل استسلاماً."),
        Def("serious", "escape_hold_seconds", "مدة الضغط المطوّل", RemoteSettingKind.CHOICE, JsonPrimitive(10),
            choices = ints(listOf(3, 5, 10, 15, 20, 30), ::seconds), visibleWhen = escape),
        Def("serious", "phrase_mode", "عبارة الخروج", RemoteSettingKind.CHOICE, JsonPrimitive("RANDOM"),
            choices = listOf(JsonPrimitive("RANDOM") to "عبارة عشوائية من القائمة", JsonPrimitive("CUSTOM") to "عبارتي الخاصة"), visibleWhen = escape),
        Def("serious", "phrases", "عبارات الخروج", RemoteSettingKind.TEXT_LIST, JsonArray(phrasesDefault.map(::JsonPrimitive)),
            help = "حروف عربية ومسافات فقط، حتى 30 حرفاً.", minCount = 1, maxCount = 30, maxLength = 30,
            visibleWhen = escape + ("phrase_mode" to JsonPrimitive("RANDOM"))),
        Def("serious", "custom_phrase", "عبارتي الخاصة", RemoteSettingKind.TEXT, JsonPrimitive(""), maxLength = 30,
            help = "حروف عربية ومسافات فقط، حتى 30 حرفاً.", visibleWhen = escape + ("phrase_mode" to JsonPrimitive("CUSTOM"))),
        Def("serious", "skip_allowance_seconds", "التخطي المسموح", RemoteSettingKind.CHOICE, JsonPrimitive(30),
            choices = ints(listOf(0, 10, 30, 60, 120)) { if (it == 0) "بلا تخطٍّ" else seconds(it) }, visibleWhen = serious),
        Def("serious", "played_percent", "أو مشاهدة جزء من الفيديو", RemoteSettingKind.CHOICE, JsonPrimitive(50),
            choices = ints(listOf(30, 50, 75, 90, 100)) { if (it == 100) "الفيديو كله" else "⁦$it%⁩" }, visibleWhen = serious),
        Def("playback", "warning_minutes", "التنبيه قبل انتهاء الوقت", RemoteSettingKind.CHOICE, JsonPrimitive(5),
            choices = ints(listOf(0, 1, 2, 3, 5, 10, 15)) { if (it == 0) "متوقف" else "قبل ${minutes(it)}" }),
        Def("playback", "warning_banner_seconds", "مدة ظهور التنبيه", RemoteSettingKind.CHOICE, JsonPrimitive(12), choices = ints(listOf(5, 8, 12, 20, 30), ::seconds)),
        Def("playback", "extra_minutes", "خيارات الوقت الإضافي", RemoteSettingKind.MULTI, JsonArray(listOf(15, 30, 60).map(::JsonPrimitive)),
            help = "ما يظهر بعد الرمز على شاشة «انتهى الوقت». اختر من خيار واحد إلى أربعة.",
            choices = ints(listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)) { "+" + minutes(it) }, minCount = 1, maxCount = 4),
        Def("playback", "default_speed", "السرعة عند بدء الفيديو", RemoteSettingKind.CHOICE, JsonPrimitive(1f),
            choices = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f).map { JsonPrimitive(it) to if (it == 1f) "عادية (1×)" else "⁦$it×⁩" }),
        Def("playback", "seek_step_seconds", "خطوة التقديم والإرجاع", RemoteSettingKind.CHOICE, JsonPrimitive(10), choices = ints(listOf(5, 10, 15, 20, 30, 60), ::seconds)),
        Def("playback", "shorts_max_seconds", "حدّ الفيديو القصير (Shorts)", RemoteSettingKind.CHOICE, JsonPrimitive(180),
            choices = ints(listOf(60, 90, 120, 180, 240, 300, 600)) { "حتى " + seconds(it) }),
        Def("playback", "voice_language", "لغة البحث الصوتي", RemoteSettingKind.CHOICE, JsonPrimitive("ar-SA"),
            choices = listOf(JsonPrimitive("ar-SA") to "العربية", JsonPrimitive("en-US") to "English")),
        Def("pin", "pin_max_failures", "المحاولات قبل الإيقاف", RemoteSettingKind.CHOICE, JsonPrimitive(5), choices = ints(listOf(3, 4, 5, 6, 8, 10)) { "$it محاولات" }),
        Def("pin", "pin_first_lockout_minutes", "مدة الإيقاف الأولى", RemoteSettingKind.CHOICE, JsonPrimitive(5), choices = ints(listOf(1, 2, 5, 10, 15, 30, 60), ::minutes)),
        Def("pin", "parent_unlock_minutes", "بقاء الإعدادات مفتوحة", RemoteSettingKind.CHOICE, JsonPrimitive(5), choices = ints(listOf(1, 2, 5, 10, 15, 30), ::minutes)),
        Def("pin", "relock_seconds", "طلب الرمز بعد مغادرة التطبيق", RemoteSettingKind.CHOICE, JsonPrimitive(15), choices = ints(listOf(5, 10, 15, 30, 60, 120, 300), ::seconds)),
        Def("pin", "adult_entry_pin", "طلب رمز الوالدين عند الدخول إلى ملف الكبار", RemoteSettingKind.SWITCH, on,
            help = "عند الإيقاف يُفتح ملف الكبار دون رمز. تبقى الإعدادات مقفولة بالرمز دائماً.",
            warning = "تنبيه: ما دام هذا متوقفاً يستطيع الأطفال الدخول إلى ملف الكبار ومشاهدة ما فيه."),
        Def("sponsorblock", "sponsor_categories", "فئات SponsorBlock", RemoteSettingKind.MULTI,
            JsonArray(listOf("sponsor", "selfpromo", "interaction", "intro", "outro").map(::JsonPrimitive)),
            help = "تتخطى «شاشة» هذه الأجزاء تلقائياً في كل الملفات، مع رسالة صغيرة.",
            choices = listOf("sponsor" to "إعلان مدفوع", "selfpromo" to "ترويج ذاتي", "interaction" to "طلب تفاعل", "intro" to "مقدمة", "outro" to "خاتمة",
                "preview" to "معاينة", "filler" to "حشو").map { JsonPrimitive(it.first) to it.second }, minCount = 0, maxCount = 7),
        Def("quality", "max_quality", "الجودة الافتراضية", RemoteSettingKind.CHOICE, JsonPrimitive(1080),
            choices = ints(listOf(2160, 1440, 1080, 720, 480)) { "تلقائي حتى ${it}p" }),
        Def("remote", "remote_port", "منفذ الخادم", RemoteSettingKind.NUMBER, JsonPrimitive(8765), min = 1024, max = 65535,
            help = "من 1024 حتى 65535. بعد تغييره يعمل الخادم عليه من جديد."),
    )

    private val titles = linkedMapOf(
        "serious" to "الوضع الجاد", "playback" to "التشغيل والتنبيهات", "pin" to "رمز الوالدين",
        "sponsorblock" to "فئات SponsorBlock", "quality" to "الجودة الافتراضية", "remote" to "الخادم المحلي",
    )

    private val values = HashMap<String, JsonElement>()

    fun page() = RemoteSettingsPage(
        titles.map { (id, title) ->
            RemoteSettingGroup(id, title, defs.filter { it.group == id }.map { d ->
                RemoteSetting(d.key, d.label, d.kind, values[d.key] ?: d.default, d.default, d.help,
                    d.choices.map { RemoteChoice(it.first, it.second) }, d.min, d.max, d.minCount, d.maxCount, d.maxLength, d.visibleWhen, d.warning)
            })
        },
    )

    /** [port] receives a new port (the page must follow it). */
    fun change(key: String, value: JsonElement, port: (Int) -> Unit): RemoteSettingsResult {
        val d = defs.firstOrNull { it.key == key } ?: return RemoteSettingsResult(false, "هذا الإعداد غير موجود على الشاشة. حدّث الصفحة.", page())
        val valid = when (d.kind) {
            RemoteSettingKind.SWITCH -> (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != null
            RemoteSettingKind.CHOICE -> d.choices.any { it.first.toString() == value.toString() }
            RemoteSettingKind.MULTI -> (value as? JsonArray)?.let { arr ->
                arr.size in (d.minCount ?: 0)..(d.maxCount ?: 99) && arr.all { v -> d.choices.any { it.first.toString() == v.toString() } }
            } == true
            RemoteSettingKind.NUMBER -> (value as? JsonPrimitive)?.intOrNull?.let { it in d.min!!..d.max!! } == true
            RemoteSettingKind.TEXT -> (value as? JsonPrimitive)?.content?.let { it.isNotBlank() && it.length <= d.maxLength!! && it.all { c -> c == ' ' || c in 'ء'..'ي' } } == true
            RemoteSettingKind.TEXT_LIST -> (value as? JsonArray)?.let { arr -> arr.size in 1..d.maxCount!! && arr.all { (it as JsonPrimitive).content.let { t -> t.isNotBlank() && t.length <= 30 } } } == true
        }
        if (!valid) return RemoteSettingsResult(false, "لم يُحفظ «${d.label}»: هذه القيمة غير متاحة على الشاشة.", page())
        if (key == "phrase_mode" && value.toString() == "\"CUSTOM\"" && (values["custom_phrase"] as? JsonPrimitive)?.content.isNullOrBlank()) {
            return RemoteSettingsResult(false, "اكتب عبارتك الخاصة أولاً، ثم اخترها.", page())
        }
        values[key] = value
        if (key == "custom_phrase") values["phrase_mode"] = JsonPrimitive("CUSTOM")
        if (key == "remote_port") {
            val p = (value as JsonPrimitive).intOrNull!!
            port(p)
            return RemoteSettingsResult(true, "سينتقل الخادم إلى المنفذ $p خلال لحظة، وتتبعه الصفحة وحدها.", page(), reconnectPort = p)
        }
        return RemoteSettingsResult(true, "حُفظ: «${d.label}».", page())
    }

    fun reset(group: String?, port: (Int) -> Unit): RemoteSettingsResult {
        if (group != null && group !in titles) return RemoteSettingsResult(false, "هذا الإعداد غير موجود على الشاشة. حدّث الصفحة.", page())
        val portBefore = (values["remote_port"] as? JsonPrimitive)?.intOrNull ?: 8765
        defs.filter { group == null || it.group == group }.forEach { values.remove(it.key) }
        val moved = (values["remote_port"] as? JsonPrimitive)?.intOrNull ?: 8765
        val msg = if (group == null) "عادت كل الإعدادات إلى وضعها الأصلي." else "عادت إعدادات «${titles[group]}» إلى وضعها الأصلي."
        if (moved != portBefore) {
            port(moved)
            return RemoteSettingsResult(true, msg, page(), reconnectPort = moved)
        }
        return RemoteSettingsResult(true, msg, page())
    }

    val seriousMode: Boolean get() = (values["serious_mode"] as? JsonPrimitive)?.booleanOrNull ?: true
    val adultEntryPin: Boolean get() = (values["adult_entry_pin"] as? JsonPrimitive)?.booleanOrNull ?: true
}
