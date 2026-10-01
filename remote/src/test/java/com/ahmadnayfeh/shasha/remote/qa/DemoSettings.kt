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
 * The TV's settings as the demo shows them: the same groups, keys, kinds, texts and checks as the app's PhoneSettings
 * (a change over a value that changed meanwhile is refused, «عبارتي الخاصة» is written before it can be chosen, a port
 * another program holds is refused). Only for visual QA of the phone page.
 */
internal class DemoSettings(private val portTaken: (Int) -> Boolean = { false }) {
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
        n == 60 -> "ساعة"
        n == 120 -> "ساعتان"
        n >= 60 && n % 60 == 0 -> "${n / 60} ساعات"
        else -> "$n دقيقة"
    }

    private fun ints(values: List<Int>, label: (Int) -> String) = values.map { JsonPrimitive(it) to label(it) }

    private val phrasesDefault = listOf("انا اخترت الخروج", "سأكمل لاحقا", "لا اريد الاكمال الان", "الوقت انتهى عندي")
    private val phraseNote = "حروف عربية ومسافات فقط، حتى 30 حرفاً: تُكتب بلوحة المفاتيح التي تظهر عند الخروج."

    private val defs = listOf(
        Def("serious", "serious_mode", "الوضع الجاد", RemoteSettingKind.SWITCH, on,
            help = "الطفل يخرج من الفيديو برمز الوالدين، والكبير بالضغط المطوّل ثم كتابة عبارة. عند الإيقاف يخرج الجميع بزر «رجوع».",
            warning = "الوضع الجاد متوقف: زر «رجوع» يُخرج من أي فيديو في كل الملفات."),
        Def("serious", "adult_escape", "السماح للكبير بالخروج قبل النهاية", RemoteSettingKind.SWITCH, on, visibleWhen = serious,
            help = "بالضغط المطوّل ثم كتابة عبارة، ويُسجَّل استسلاماً. عند الإيقاف لا يُنهى الفيديو قبل نهايته إلا من قسم الأب في الجوال."),
        Def("serious", "escape_hold_seconds", "مدة الضغط المطوّل", RemoteSettingKind.CHOICE, JsonPrimitive(10),
            help = "كم ثانية يُضغط على «رجوع» أو «⁨OK⁩» قبل ظهور العبارة",
            choices = ints(listOf(3, 5, 10, 15, 20, 30), ::seconds), visibleWhen = escape),
        Def("serious", "phrase_mode", "عبارة الخروج", RemoteSettingKind.CHOICE, JsonPrimitive("RANDOM"),
            choices = listOf(JsonPrimitive("RANDOM") to "عبارة عشوائية من القائمة", JsonPrimitive("CUSTOM") to "عبارتي الخاصة"), visibleWhen = escape),
        Def("serious", "phrases", "عبارات الخروج", RemoteSettingKind.TEXT_LIST, JsonArray(phrasesDefault.map(::JsonPrimitive)),
            help = phraseNote, minCount = 1, maxCount = 30, maxLength = 30,
            visibleWhen = escape + ("phrase_mode" to JsonPrimitive("RANDOM"))),
        Def("serious", "custom_phrase", "عبارتي الخاصة", RemoteSettingKind.TEXT, JsonPrimitive(""), maxLength = 30,
            help = "حفظها يجعلها العبارة المطلوبة عند الخروج. $phraseNote", visibleWhen = escape),
        Def("serious", "skip_allowance_seconds", "التخطي المسموح", RemoteSettingKind.CHOICE, JsonPrimitive(30),
            help = "ما يُتخطى بالتقديم حتى هذا القدر لا يمنع اعتبار الفيديو منتهياً وحده",
            choices = ints(listOf(0, 10, 30, 60, 120)) { if (it == 0) "بلا تخطٍّ" else seconds(it) }, visibleWhen = serious),
        Def("serious", "played_percent", "أو مشاهدة جزء من الفيديو", RemoteSettingKind.CHOICE, JsonPrimitive(50),
            help = "وينتهي الفيديو وحده أيضاً إذا شوهد هذا القدر مما بقي منه",
            choices = ints(listOf(30, 50, 75, 90, 100)) { if (it == 100) "الفيديو كله" else "⁦$it%⁩" }, visibleWhen = serious),
        Def("playback", "warning_minutes", "التنبيه قبل انتهاء الوقت", RemoteSettingKind.CHOICE, JsonPrimitive(5),
            help = "تنبيه هادئ قبل أن تتوقف المشاهدة",
            choices = ints(listOf(0, 1, 2, 3, 5, 10, 15)) { if (it == 0) "متوقف" else "قبل ${if (it == 2) "دقيقتين" else minutes(it)}" }),
        Def("playback", "warning_banner_seconds", "مدة ظهور التنبيه", RemoteSettingKind.CHOICE, JsonPrimitive(12),
            help = "كم يبقى التنبيه على الشاشة", choices = ints(listOf(5, 8, 12, 20, 30), ::seconds)),
        Def("playback", "extra_minutes", "خيارات الوقت الإضافي", RemoteSettingKind.MULTI, JsonArray(listOf(15, 30, 60).map(::JsonPrimitive)),
            help = "ما يظهر بعد الرمز على شاشة «انتهى الوقت»، مع «بقية اليوم». اختر من خيار واحد إلى أربعة.",
            choices = ints(listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)) { "+" + minutes(it) }, minCount = 1, maxCount = 4),
        Def("playback", "default_speed", "السرعة عند بدء الفيديو", RemoteSettingKind.CHOICE, JsonPrimitive(1f),
            help = "وتبقى قائمة السرعة في المشغّل حتى 4×",
            choices = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f).map { JsonPrimitive(it) to if (it == 1f) "عادية (⁦1×⁩)" else "⁦${if (it % 1f == 0f) it.toInt() else it}×⁩" }),
        Def("playback", "seek_step_seconds", "خطوة التقديم والإرجاع", RemoteSettingKind.CHOICE, JsonPrimitive(10),
            help = "كم يتقدّم الفيديو أو يرجع بضغطة يمين أو يسار", choices = ints(listOf(5, 10, 15, 20, 30, 60), ::seconds)),
        Def("playback", "controls_hide_seconds", "إخفاء أزرار التحكم", RemoteSettingKind.CHOICE, JsonPrimitive(3),
            help = "بعد كم ثانية بلا ضغط تختفي الأزرار أثناء التشغيل", choices = ints(listOf(2, 3, 5, 8, 10), ::seconds)),
        Def("playback", "up_next_seconds", "العدّ قبل الفيديو التالي", RemoteSettingKind.CHOICE, JsonPrimitive(5),
            help = "داخل قائمة التشغيل. عند الإيقاف ينتظر التالي ضغطة «تشغيل الآن»",
            choices = ints(listOf(0, 3, 5, 10, 15)) { if (it == 0) "متوقف" else seconds(it) }),
        Def("playback", "kids_home_seconds", "عودة الطفل إلى الرئيسية", RemoteSettingKind.CHOICE, JsonPrimitive(10),
            help = "بعد انتهاء فيديو الطفل: كم ثانية قبل أن تعود الشاشة إلى رئيسيته وحدها", choices = ints(listOf(5, 10, 15, 30, 60), ::seconds)),
        Def("playback", "sponsor_undo_seconds", "مهلة «تراجع عن التخطي»", RemoteSettingKind.CHOICE, JsonPrimitive(5),
            help = "في ملف الكبار: كم تبقى فرصة التراجع بعد كل تخطٍّ", choices = ints(listOf(3, 5, 8, 10, 15), ::seconds)),
        Def("playback", "sponsor_notice_seconds", "مدة رسالة التخطي", RemoteSettingKind.CHOICE, JsonPrimitive(3),
            help = "في ملف الطفل: كم تبقى رسالة «تم تخطي»", choices = ints(listOf(2, 3, 5, 8), ::seconds)),
        Def("playback", "shorts_max_seconds", "حدّ الفيديو القصير (⁨Shorts⁩)", RemoteSettingKind.CHOICE, JsonPrimitive(180),
            help = "ما كانت مدته هذا القدر أو أقل يُعدّ قصيراً ويُخفى حيث تُخفى القصيرة",
            choices = ints(listOf(60, 90, 120, 180, 240, 300, 600)) { "حتى " + if (it % 60 == 0) minutes(it / 60) else seconds(it) }),
        Def("playback", "voice_language", "لغة البحث الصوتي", RemoteSettingKind.CHOICE, JsonPrimitive("ar-SA"),
            help = "اللغة التي يسمعها الميكروفون في شاشة البحث",
            choices = listOf(JsonPrimitive("ar-SA") to "العربية", JsonPrimitive("en-US") to "الإنجليزية")),
        Def("pin", "pin_max_failures", "المحاولات قبل الإيقاف", RemoteSettingKind.CHOICE, JsonPrimitive(5),
            help = "كم رمزاً خاطئاً متتالياً قبل أن يتوقف قبول الرمز", choices = ints(listOf(3, 4, 5, 6, 8, 10)) { "$it محاولات" }),
        Def("pin", "pin_first_lockout_minutes", "مدة الإيقاف الأولى", RemoteSettingKind.CHOICE, JsonPrimitive(5),
            help = "وتطول كل مرة حتى يُدخل الرمز الصحيح: ثلاثة أضعافها، ثم 12 ضعفاً، ثم 48 ضعفاً", choices = ints(listOf(1, 2, 5, 10, 15, 30, 60), ::minutes)),
        Def("pin", "parent_unlock_minutes", "بقاء الإعدادات مفتوحة", RemoteSettingKind.CHOICE, JsonPrimitive(5),
            help = "بعد إدخال الرمز في ملفك، قبل أن يُطلب من جديد", choices = ints(listOf(1, 2, 5, 10, 15, 30), ::minutes)),
        Def("pin", "relock_seconds", "طلب الرمز بعد مغادرة التطبيق", RemoteSettingKind.CHOICE, JsonPrimitive(15),
            help = "إذا غاب التطبيق عن الشاشة هذا القدر يُسأل من يشاهد من جديد",
            choices = ints(listOf(5, 10, 15, 30, 60, 120, 300)) { if (it >= 60) minutes(it / 60) else seconds(it) }),
        Def("pin", "adult_entry_pin", "طلب رمز الوالدين عند الدخول إلى ملف الكبار", RemoteSettingKind.SWITCH, on,
            help = "عند الإيقاف يُفتح ملف الكبار دون رمز، من التلفاز ومن الجوال. تبقى الإعدادات مقفولة بالرمز دائماً، فلا يستطيع طفل تعطيل الحماية، والخروج من ملف طفل يحتاج الرمز كما هو.",
            warning = "تنبيه: ما دام هذا متوقفاً يستطيع الأطفال الدخول إلى ملف الكبار ومشاهدة ما فيه."),
        Def("sponsorblock", "sponsor_categories", "فئات ⁨SponsorBlock⁩", RemoteSettingKind.MULTI,
            JsonArray(listOf("sponsor", "selfpromo", "interaction", "intro", "outro").map(::JsonPrimitive)),
            help = "تتخطى «شاشة» هذه الأجزاء تلقائياً في كل الملفات، مع رسالة صغيرة.",
            choices = listOf(
                "sponsor" to "إعلان مموَّل: إعلان مدفوع يقرؤه صاحب القناة داخل الفيديو.",
                "selfpromo" to "ترويج ذاتي: ترويج صاحب القناة لمنتجاته أو متجره أو قنواته الأخرى.",
                "interaction" to "تذكير بالتفاعل: طلب الإعجاب والاشتراك والتعليق في وسط الفيديو.",
                "intro" to "المقدمة: شارة البداية أو فاصل متكرر بلا محتوى.",
                "outro" to "الخاتمة: شارة النهاية والشاشات الختامية.",
                "preview" to "معاينة أو تلخيص: لقطات مما سيأتي في الفيديو، أو ملخص لما سبق.",
                "hook" to "تحية أو تشويق: تحيات ولقطات تشويقية في البداية لا تحمل معلومة.",
                "filler" to "استطراد: حديث جانبي أو مزاح خارج موضوع الفيديو.",
                "music_offtopic" to "جزء غير موسيقي: في مقاطع الموسيقى: الأجزاء التي ليست موسيقى.",
            ).map { JsonPrimitive(it.first) to it.second }, minCount = 0, maxCount = 9),
        Def("quality", "max_quality", "الجودة الافتراضية", RemoteSettingKind.CHOICE, JsonPrimitive(1080),
            help = "تسري من الفيديو التالي. ولا يتجاوز المشغّل ما تفكّ الشاشة ترميزه بسلاسة، وتبقى قائمة «الجودة» في المشغّل لاختيار جودة ثابتة حتى هذا الحد.",
            choices = ints(listOf(2160, 1440, 1080, 720, 480)) { "تلقائي حتى ${it}p" }),
        Def("remote", "remote_port", "منفذ الخادم", RemoteSettingKind.NUMBER, JsonPrimitive(8765), min = 1024, max = 65535,
            help = "من 1024 حتى 65535. بعد تغييره يعمل الخادم عليه من جديد، فامسح رمز الاقتران مرة أخرى"),
    )

    private val titles = linkedMapOf(
        "serious" to "الوضع الجاد", "playback" to "التشغيل والتنبيهات", "pin" to "رمز الوالدين",
        "sponsorblock" to "فئات ⁨SponsorBlock⁩", "quality" to "الجودة الافتراضية", "remote" to "الخادم المحلي",
    )

    private val values = HashMap<String, JsonElement>()

    private fun valueOf(d: Def) = values[d.key] ?: d.default

    fun page() = RemoteSettingsPage(
        titles.map { (id, title) ->
            RemoteSettingGroup(id, title, defs.filter { it.group == id }.map { d ->
                RemoteSetting(d.key, d.label, d.kind, valueOf(d), d.default, d.help,
                    d.choices.map { RemoteChoice(it.first, it.second, disabledReason(d, it.first)) }, d.min, d.max, d.minCount, d.maxCount, d.maxLength, d.visibleWhen, d.warning)
            }, note = if (id == "serious") "الفيديو الذي يبدأ يُشاهَد حتى نهايته" else null)
        },
    )

    private fun customPhrase() = (values["custom_phrase"] as? JsonPrimitive)?.content.orEmpty()

    private fun disabledReason(d: Def, choice: JsonElement): String? =
        if (d.key == "phrase_mode" && choice == JsonPrimitive("CUSTOM") && customPhrase().isBlank()) CUSTOM_FIRST else null

    /** [port] receives a new port (the page must follow it). */
    fun change(key: String, value: JsonElement, base: JsonElement?, port: (Int) -> Unit): RemoteSettingsResult {
        val d = defs.firstOrNull { it.key == key } ?: return RemoteSettingsResult(false, "هذا الإعداد غير موجود على الشاشة. حدّث الصفحة.", page())
        if (base != null && !same(base, valueOf(d))) {
            return RemoteSettingsResult(false, "تغيّر «⁨${d.label}⁩» على الشاشة أو من جوال آخر قبل لحظات، فلم يُحفظ تغييرك. هذه قيمته الآن.", page())
        }
        val valid = when (d.kind) {
            RemoteSettingKind.SWITCH -> (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != null
            RemoteSettingKind.CHOICE -> d.choices.any { same(it.first, value) }
            RemoteSettingKind.MULTI -> (value as? JsonArray)?.let { arr ->
                arr.size in (d.minCount ?: 0)..(d.maxCount ?: 99) && arr.all { v -> d.choices.any { same(it.first, v) } }
            } == true
            RemoteSettingKind.NUMBER -> (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.let { it in d.min!!..d.max!! } == true
            RemoteSettingKind.TEXT -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { it.isNotBlank() && it.length <= d.maxLength!! && it.all { c -> c == ' ' || c in 'ء'..'ي' } } == true
            RemoteSettingKind.TEXT_LIST -> (value as? JsonArray)?.let { arr -> arr.size in 1..d.maxCount!! && arr.all { (it as? JsonPrimitive)?.content?.let { t -> t.isNotBlank() && t.length <= 30 && t.all { c -> c == ' ' || c in 'ء'..'ي' } } == true } } == true
        }
        if (!valid) return RemoteSettingsResult(false, "لم يُحفظ «⁨${d.label}⁩»: هذه القيمة غير متاحة على الشاشة.", page())
        if (key == "phrase_mode" && same(value, JsonPrimitive("CUSTOM")) && customPhrase().isBlank()) return RemoteSettingsResult(false, CUSTOM_FIRST, page())
        if (key == "remote_port") {
            val p = (value as JsonPrimitive).intOrNull!!
            if (same(value, valueOf(d))) return RemoteSettingsResult(true, "حُفظ: «⁨${d.label}⁩».", page())
            if (portTaken(p)) return RemoteSettingsResult(false, "المنفذ $p يستخدمه تطبيق آخر على التلفاز. اختر رقماً غيره.", page())
            values[key] = value
            revision++
            port(p)
            return RemoteSettingsResult(true, "سينتقل الخادم إلى المنفذ $p خلال لحظة، وتتبعه الصفحة وحدها.", page(), reconnectPort = p)
        }
        values[key] = value
        if (key == "custom_phrase") values["phrase_mode"] = JsonPrimitive("CUSTOM")
        revision++
        return RemoteSettingsResult(true, "حُفظ: «⁨${d.label}⁩».", page())
    }

    fun reset(group: String?, port: (Int) -> Unit): RemoteSettingsResult {
        if (group != null && group !in titles) return RemoteSettingsResult(false, "هذا الإعداد غير موجود على الشاشة. حدّث الصفحة.", page())
        val portBefore = (values["remote_port"] as? JsonPrimitive)?.intOrNull ?: 8765
        defs.filter { group == null || it.group == group }.forEach { values.remove(it.key) }
        revision++
        val moved = (values["remote_port"] as? JsonPrimitive)?.intOrNull ?: 8765
        val msg = if (group == null) "عادت كل الإعدادات إلى وضعها الأصلي." else "عادت إعدادات «⁨${titles[group]}⁩» إلى وضعها الأصلي."
        if (moved != portBefore) {
            port(moved)
            return RemoteSettingsResult(true, "$msg سينتقل الخادم إلى المنفذ $moved خلال لحظة، وتتبعه الصفحة وحدها.", page(), reconnectPort = moved)
        }
        return RemoteSettingsResult(true, msg, page())
    }

    /** Moves with every change (the demo's part of [com.ahmadnayfeh.shasha.remote.RemoteStatus.rev]). */
    var revision = 0L
        private set

    val seriousMode: Boolean get() = (values["serious_mode"] as? JsonPrimitive)?.booleanOrNull ?: true
    val adultEntryPin: Boolean get() = (values["adult_entry_pin"] as? JsonPrimitive)?.booleanOrNull ?: true
    val extraMinutes: List<Int> get() = ((values["extra_minutes"] ?: defs.first { it.key == "extra_minutes" }.default) as JsonArray).map { (it as JsonPrimitive).intOrNull!! }

    private companion object {
        const val CUSTOM_FIRST = "اكتب عبارتك الخاصة أدناه واحفظها أولاً، فتُختار وحدها."

        /** Numbers by value (the page sends 1 for 1.0), texts and lists as they are. */
        fun same(a: JsonElement, b: JsonElement): Boolean = when {
            a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
            a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString ->
                a.content.toDoubleOrNull()?.let { it == b.content.toDoubleOrNull() } ?: (a.content == b.content)
            else -> a == b
        }
    }
}
