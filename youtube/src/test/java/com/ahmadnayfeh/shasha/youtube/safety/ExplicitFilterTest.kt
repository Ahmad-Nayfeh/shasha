package com.ahmadnayfeh.shasha.youtube.safety

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The built-in explicit-content filter against what people really type: titles and queries that must be blocked, in every
 * category and language of [ExplicitTerms], disguised in the usual ways, and ordinary ones that must not be. Tables, so that
 * a missed line names itself: add a line for every term added to the lists, and an ordinary line next to every short or
 * common word.
 */
class ExplicitFilterTest {

    private val filter = ExplicitFilter.default

    private fun assertAllBlocked(titles: List<String>) {
        val missed = titles.filterNot { filter.isExplicit(it) }
        assertWithMessage("explicit, but not blocked").that(missed).isEmpty()
    }

    private fun assertAllPass(titles: List<String>) {
        val blocked = titles.filter { filter.isExplicit(it) }
        assertWithMessage("ordinary, but blocked").that(blocked).isEmpty()
    }

    // ================================================================ blocked ====================================================

    @Test
    fun ownerReportAndRestrictedModeLeaks() = assertAllBlocked(
        listOf(
            "Sexy twerking", "twerking", "sexy dance", "belly dance", "رقص شرقي", // the report
            "Strip pole", "Rub & Blow", "SexOnTheBeat", "TWERK CLUB", // what YouTube's Restricted Mode let through
        ),
    )

    @Test
    fun sexAndSexualTalk() = assertAllBlocked(
        listOf(
            "sex", "Sex education for teens", "sexual reproduction", "sexting tips", "sex tape", "freesex", "sexy", "sexier girls",
            "intercourse", "orgasm", "masturbation", "foreplay", "kama sutra", "threesome", "orgy", "one night stand",
            "friends with benefits", "sugar daddy", "make out", "making out in public", "makeout session", "horny", "kinky",
            "seductive", "how to seduce", "seduction", "sensual", "sensuality", "erotic", "erotica", "erotic massage", "rape scene",
            "incest", "pedophile", "prostitute", "prostitution", "brothel",
        ),
    )

    @Test
    fun pornographyAdultSitesAndRatings() = assertAllBlocked(
        listOf(
            "porn", "porno", "PORN HUB", "pornstar", "free porn videos", "xxx", "XXX movies", "xvideos", "XNXX", "xhamster",
            "redtube", "youporn", "brazzers", "onlyfans", "Only Fans", "OnlyFans leaked", "nsfw", "NSFW art", "hentai", "doujin",
            "rule 34", "rule34", "18+", "18+ only", "+18", "21+ videos", "18 plus", "adult video", "adult content", "adults only",
            "adult film", "x rated", "blue movie", "camgirl", "cam girl", "webcam girl", "playboy", "milf", "gilf", "dilf",
            "ecchi anime", "uncensored anime",
        ),
    )

    @Test
    fun nudity() = assertAllBlocked(
        listOf(
            "nude", "nudes", "naked", "naked girls", "nakid", "nekkid", "nudity", "nudist beach", "topless", "birthday suit",
            "skinny dipping", "in the buff", "full frontal", "nip slip", "wardrobe malfunction", "upskirt", "see through dress",
            "see-through haul", "transparent try on",
        ),
    )

    @Test
    fun explicitBodyTerms() = assertAllBlocked(
        listOf(
            "boobs", "boob job", "tits", "titties", "big booty", "booty shaking", "ass", "ass shaking", "asshole", "penis",
            "vagina", "pussy", "cunt", "dick pic", "big dick", "dick", "cock", "cock ring", "butt", "tit", "nipples", "cleavage",
            "clitoris", "anal", "anus", "wet pussy",
        ),
    )

    @Test
    fun fetishAndVulgarWords() = assertAllBlocked(
        listOf(
            "fetish", "foot fetish", "bdsm", "bondage", "dominatrix", "femdom", "shibari", "spanking", "feet pics", "foot worship",
            "fuck", "fucking", "motherfucker", "shit", "bullshit", "bitch", "whore", "slut", "dickhead", "f*ck",
        ),
    )

    @Test
    fun suggestiveDance() = assertAllBlocked(
        listOf(
            "twerk", "Twerking compilation", "twerk team", "strip club", "strip tease", "striptease", "stripper", "stripper dance",
            "lap dance", "pole dance", "pole dancing", "pole dancing fitness", "belly dance", "belly dancer", "Belly Dance Lesson",
            "bellydance", "oriental dance", "go go dancer", "burlesque show", "booty dance", "hot dance", "dirty dancing",
            "naughty dance", "sensual dance", "exotic dancer", "private dance", "lapdance", "poledance",
        ),
    )

    @Test
    fun suggestiveClothing() = assertAllBlocked(
        listOf(
            "lingerie", "Lingerie haul", "bikini", "bikini haul", "try on haul", "Try-On Haul 2024", "tryon haul", "thong", "panties",
            "bra", "bras", "bikini model", "swimsuit haul", "underwear haul", "transparent dress", "revealing outfit",
            "provocative dress", "skimpy bikini", "underwear try on",
            // What a live search for "swimsuit", "yoga pants" and "fashion model" still showed.
            "see through dress", "See-Through Dress Try On", "Lululemon See Thru Yoga Pants", "sheer dress", "Sheer Lingerie",
            "Swimsuit Competition", "Miss Universe 2017 - Swimsuit Competition Song", "SWIMSUIT SHOWCASE", "Preliminary Swimsuit Round",
            "Full Shows | Swimwear & Underwear", "Sports Illustrated Swimsuit 2017", "Inside the Making of SI Swimsuit 2026",
            "swimwear runway show", "underwear runway", "Butt Lifting Leggings", "Arabian curvy plus-size women fashion model",
            "Calibri Swimwear | Resort 2024 | Full Show", "Berry Beachy | Miami Swim Week 2027", "Live: Victoria's Secret Fashion Show 2025",
            "Victorias Secret", "Hooters Girls Dancing", "Arab girl dancing", "Beautiful Arabic girl's dance performance video",
            "Candidates REVEAL THEIR TOP 5 BEST SWIMSUIT Performance", "MGI2023 - Top20 in swimsuits / Highlight",
            "THE BLACK TAPE PROJECT Swimwear Art Hearts Fashion 2024 Miami",
        ),
    )

    @Test
    fun scenesAndKissing() = assertAllBlocked(
        listOf(
            "kissing scenes", "best kiss scenes", "kissing challenge", "kissing prank", "french kiss", "passionate kiss", "hot kiss",
            "how to kiss", "love scene", "bed scene", "bedroom scene", "intimate scenes", "steamy scene", "hot scene",
            "hot scenes from movies", "kiss compilation", "Girls kissing each other TikTok compilation", "girl kissing girl", "Girls Kiss Girls",
            "new best friend lesbian kiss", "lesbian", "Lesbians",
        ),
    )

    @Test
    fun hotAndSexyGirls() = assertAllBlocked(
        listOf(
            "hot girls", "Hot Girls Dancing", "hot young girls", "hot babes", "hot mom", "hot model", "hot teacher", "sexy girls",
            "sexy teacher", "hot video", "hot pics", "hot photos", "hot bikini", "hotgirls", "hot photoshoot", "spicy scenes",
            "naughty girls", "curvy girls", "thick thighs",
        ),
    )

    @Test
    fun asmrAndOtherSuggestiveVideos() = assertAllBlocked(
        listOf("ear licking", "ASMR ear licking", "ear eating asmr", "ear nibbling", "asmr kissing", "licking asmr", "nuru massage"),
    )

    @Test
    fun escortsAndCams() = assertAllBlocked(
        listOf("escort", "escort girls", "escorts", "call girl", "callgirl", "cam show", "webcam show"),
    )

    // ---- Disguises ----

    @Test
    fun leetspeak() = assertAllBlocked(
        listOf("s3x", "$" + "exy", "5exy", "s€x", "p0rn", "pr0n", "b00bs", "n4k3d", "nak3d", "n@ked", "@ss", "a55", "h0rny", "h0t g1rls", "l1ngerie"),
    )

    @Test
    fun repeatedLetters() = assertAllBlocked(
        listOf("sexxxy", "seeexy", "sexxxxxy girls", "pooorn", "assssss", "boooobs", "nuuuude", "twerrrrk", "kisssss scene", "hooot girls", "lingerieee"),
    )

    @Test
    fun spacedOutAndSeparatedLetters() = assertAllBlocked(
        listOf("s e x", "S E X Y", "s.e.x", "s-e-x", "s_e_x", "p-o-r-n", "p o r n", "n u d e", "s . e . x . y", "h o t g i r l s", "x x x"),
    )

    @Test
    fun emojiAndSymbolsBetweenLetters() = assertAllBlocked(
        listOf("s🔥e🔥x", "se🔥xy", "p😈o😈r😈n", "s~e~x", "s+e+x", "n⭐u⭐d⭐e", "t★w★e★r★k", "s|e|x", "se•xy", "🍆 sex 🍑"),
    )

    @Test
    fun letterStylesAccentsAndInvisibleCharacters() = assertAllBlocked(
        listOf(
            "ＳＥＸ", "ｓｅｘｙ", "𝐬𝐞𝐱", "𝓼𝓮𝔁", "ⓢⓔⓧ", "ṡėẋ", "sé​x" /* zero-width space */, "s​e​x", "s­e­x", "se‏x", "sex️",
            "𝗣𝗢𝗥𝗡", "ｐｏｒｎ",
        ),
    )

    @Test
    fun lookalikeLettersFromOtherAlphabets() = assertAllBlocked(
        listOf("sеx" /* Cyrillic е */, "ѕех" /* Cyrillic ѕ е х */, "ρorn" /* Greek ρ */, "nυde" /* Greek υ */, "🇸🇪🇽", "🅢🅔🅧"),
    )

    @Test
    fun camelCaseAndJoinedWords() = assertAllBlocked(
        listOf("SexOnTheBeat", "HotGirls", "StripPole", "TwerkClub", "BellyDance", "NakedGirls", "SeXy", "sExY", "sexonthebeat", "freeporn", "hotsexvideos", "xxxvideos"),
    )

    @Test
    fun ageMarks() = assertAllBlocked(listOf("videos 18+", "(18+)", "18+", "+18", "+21 only", "21+", "18 plus", "adult 18plus"))

    // ---- Arabic ----

    @Test
    fun arabicSexAndPornography() = assertAllBlocked(
        listOf(
            "سكس", "سكسي", "افلام سكس", "سيكس", "السكس", "فيديوهات سكسية", "اباحي", "إباحية", "افلام اباحيه", "الإباحية", "بورن", "بورنو",
            "جنس", "الجنس", "تعلم الجنس", "علاقة جنسية", "صور جنسية", "ممارسة الجنس", "للجنس", "جنسي", "هنتاي", "ميلف",
            "اونلي فانز", "اونليفانز", "ستربتيز", "ستريبتيز", "تويرك", "تويركينج", "ايروتيك", "إيروتيكي",
        ),
    )

    @Test
    fun arabicNudityAndBody() = assertAllBlocked(
        listOf(
            "عاري", "عارية", "عاريات", "بنات عاريات", "العاري", "عراة", "تعري", "التعري", "متعرية", "عريان", "بدون ملابس",
            "ملابس داخلية", "ملابس داخليه", "لانجري", "لنجري", "بكيني", "بيكيني", "طيز", "بزاز", "نهود",
        ),
    )

    @Test
    fun arabicDanceAndSuggestive() = assertAllBlocked(
        listOf(
            "رقص شرقي", "الرقص الشرقي", "رقص مثير", "رقص ساخن", "رقص بنات", "رقص بلدي", "رقصة شرقية", "راقصة", "راقصة شرقية", "الراقصات",
            "رقاصة", "هز الخصر", "هز الوسط", "هزي خصرك", "هز", "بدلة رقص", "بدلات رقص",
            "مثير", "مثيرة", "فيديو مثير", "بنات مثيرات", "مشاهد مثيرة", "اغراء", "إغراء", "اغواء", "شهوة", "الشهوة",
            "بنات ساخنة", "مقاطع ساخنة", "مشاهد ساخنة", "صور ساخنة", "لقطات جريئة", "مشاهد جريئة", "صور جريئة",
            "قبلات", "مشاهد القبلات", "مشهد قبلة", "قبلة ساخنة", "مشهد رومانسي", "مشاهد حميمية", "علاقة حميمية",
            "فاضح", "خليعة", "بوسة", "بوسات", "بوس",
        ),
    )

    @Test
    fun arabicVulgarWords() = assertAllBlocked(
        listOf("نيك", "كس", "كس امك", "عاهرة", "شرموطة", "منيوك", "قحبة", "دعارة", "زب", "نياكة", "متناك", "لواط", "سحاق", "مضاجعة"),
    )

    @Test
    fun arabicDisguises() = assertAllBlocked(
        listOf(
            "س ك س", "سـكـس", "ســـكــــس", "سُكْس", "سكسسسس", "س.ك.س", "عـــاري", "إ ب ا ح ي", "ك س", "ر ق ص  ش ر ق ي",
            // The long vowels stretched by two or three: what social media titles do ("رقص بناات ناار").
            "رقص بناات ناار 2026", "رقص بنااات", "نييك", "عارييية", "مثييير", "بنااات حلوات رقص",
        ),
    )

    @Test
    fun arabicBrandsAndLesbianTalk() = assertAllBlocked(
        listOf("فيكتوريا سيكريت", "عرض ازياء فيكتوريا سيكرت", "افلام سحاق", "السحاق", "سحاقيات"),
    )

    @Test
    fun francoArabicAndArabizi() = assertAllBlocked(
        listOf("sks", "seks", "sexxy", "3arya", "3aryan", "sharmouta", "ka7ba", "raks sharqi", "raqs baladi", "banat sa5na", "kos omak", "metnak"),
    )

    // ---- Other languages ----

    @Test
    fun spanishPortugueseFrenchItalian() = assertAllBlocked(
        listOf(
            "sexo", "mujeres desnudas", "desnuda", "tetas", "culo", "porno", "puta", "follar", "perreo", "danza del vientre",
            "femme nue", "seins", "fesses", "salope", "putain", "danse du ventre", "nudité", "tette", "nuda", "puttana", "scopare",
            "peitos", "buceta",
        ),
    )

    @Test
    fun turkishPersianUrduHindiIndonesian() = assertAllBlocked(
        listOf(
            "çıplak kadın", "seks", "seksi", "sikiş", "göbek dansı", "سکس", "سکسی", "پورن", "برهنه", "لخت", "ننگا", "ننگی",
            "chudai", "nangi ladki", "gaand", "madarchod", "behenchod", "bhosdike", "bokep", "telanjang", "mesum",
        ),
    )

    @Test
    fun russianGermanDutch() = assertAllBlocked(
        listOf(
            "секс", "порно", "голая девушка", "эротика", "стриптиз", "жопа", "минет", "обнажённая", "интим", "трахать", "пизда",
            "nackt", "nackte Frauen", "Titten", "Arsch", "ficken", "Fotze", "Schlampe", "naakt",
        ),
    )

    // ================================================================ ordinary ===================================================

    @Test
    fun wordsThatContainOrSoundLikeListedOnes() = assertAllPass(
        listOf(
            "Essex football", "Essex vs Surrey cricket", "Sussex coast walk", "Middlesex University open day", "Wessex Water",
            "sextant navigation tutorial", "Sexton Blake", "unisex hoodie review", "asexual reproduction in plants", "Class 10 maths",
            "Bass guitar lesson", "Grass cutting simulator", "Passport renewal guide", "Mississippi River facts for kids",
            "Assassin's Creed Valhalla", "Assassins Creed Mirage", "classic cars restoration", "massage chair review",
            "Scunthorpe United highlights", "cockpit view landing", "Peacock facts", "Hitchcock Psycho", "Shuttlecock badminton smash",
            "Cocktail recipes", "Dickens A Christmas Carol", "Moby Dick audiobook", "Dick Van Dyke show", "Dick Tracy 1990",
            "Cumin chicken recipe", "Document camera setup", "Titanic documentary", "Title sequence design", "analysis of functions",
            "analog clock", "canal tour", "banana smoothie", "therapist advice", "Cummings poems", "grape juice", "Annals of Mathematics",
            "bookkeeping basics", "Poorna movie", "Pornichet beach", "agapornis lovebirds", "hoot owl sounds", "Sensex today",
            "Kraftwerk Autobahn", "Gesamtkunstwerk", "XXXL t-shirt", "Super Bowl XXXII", "Hancock movie", "Alfred Hitchcock",
            "shiitake mushrooms", "Shih Tzu puppies", "Wankel engine", "Dickinson poems", "rapeseed oil", "rap music", "Bournemouth",
            "boulangerie in Paris", "lingering doubt", "consensual agreement", "miseducation of Lauryn Hill", "unmolested nature",
            "sclerotic arteries", "heterotic string theory", "5ks and 10ks running", "who're you", "Who're We", "jeuxvideo.com",
            "escorted by police", "pass the ball",
        ),
    )

    @Test
    fun hotKissAndStripInOrdinaryUse() = assertAllPass(
        listOf(
            "hot weather", "hot weather tips", "Hot Wheels cars", "Hot Wheels videos", "Hot Wheels track building", "hot chocolate recipe",
            "hot air balloon festival", "hot sauce challenge", "hot dog eating contest", "Hot Ones", "Red Hot Chili Peppers",
            "hot water bottle video", "hot video game of the year", "Kiss FM live", "KISS Rock and Roll All Nite", "Kiss the Girl Little Mermaid",
            "Kiss the Rain piano", "Kiss me thru the phone", "first kiss", "Gaza Strip news", "comic strip drawing",
            "how to make a comic strip", "landing strip", "strip of paper craft", "LED strip lights installation", "strip wallpaper fast",
            "strip mall tour", "Arsenal home strip 2024", "test strip", "wire stripper review", "paint stripper", "bikini bottom SpongeBob",
            "Bikini Atoll history", "SpongeBob BikiniBottom", "Pussycat Dolls", "pussy cat nursery rhyme", "Puss in Boots", "pussy willow",
            "cock a doodle doo", "Moby Dick whale", "Rape of the Lock", "naked eye comet", "naked mole rat facts", "naked cake decorating",
            "The Naked Scientists", "nude lipstick shades", "pirate booty snack", "Pirate's Booty treasure", "cum laude graduation",
            "bra-ket notation quantum", "cul de sac", "make out of cardboard", "make out what he says", "police escort motorcade",
            "Ford Escort restoration", "topless Jeep", "bottle nipple review", "mineral cleavage", "cell cleavage",
            "see through the lies", "how to see through walls with a thermal camera", "sheer cliff face climbing", "sheer volume of data",
            "Sheer Heart Attack Queen album", "swimsuit for kids buying guide", "swimwear collection for kids", "learn to swim: swimsuit sizes",
            "curvy road driving the Alps", "curvy hair tutorial", "see through the green light Gatsby", "swimwear review for kids",
            "swim lessons for beginners", "Remble - SWIMWEAR (Official Music Video)", "Lesbos island travel guide", "Victoria Falls tour",
            "Arab girl scouts", "Arabic girl names", "Arabic dance music for kids", "Arab dance Dabke", "learn to swim in a pool",
            "black tape on a guitar neck", "how to swim in cold water",
        ),
    )

    @Test
    fun everydayEnglishSearches() = assertAllPass(
        listOf(
            "Quran recitation Surah Al-Baqarah", "math lessons for kids", "football highlights Real Madrid vs Barcelona",
            "cooking pasta carbonara", "Minecraft survival episode 1", "Minecraft redstone tutorial", "Minecraft 1.18+ mods", "Roblox obby",
            "Fortnite chapter 5", "Peppa Pig full episodes", "Baby Shark dance", "Bluey full episodes", "Mr Bean holiday", "Tom and Jerry",
            "learn English grammar", "how to learn programming", "Python tutorial", "Kotlin coroutines", "Android TV review",
            "iOS 18+ features", "Windows 11 tips", "iPhone 15 review", "World Cup 2022 final", "Messi best goals", "Tesla Model 3 review",
            "car crash compilation", "space documentary", "Mars rover landing", "sperm whale", "breast cancer awareness",
            "chicken breast recipe", "Captain Underpants", "girlfriend asmr", "asmr eating", "asmr sleep", "ear cleaning", "massage therapy",
            "yoga for beginners", "swimming lessons kids", "beach day vlog", "girls football", "girls basketball", "women's World Cup",
            "mothers day gift ideas", "wedding ceremony", "love songs playlist", "romantic movies 2024", "Turkish drama series",
            "dance moms", "dance challenge", "hip hop dance tutorial", "ballet dancing for kids", "Zumba workout", "Swan Lake ballet",
            "pole vault world record", "belt and braces", "kids drawing tutorial", "slime making", "science experiments for kids",
            "history of Rome",
        ),
    )

    @Test
    fun arabicOrdinarySearches() = assertAllPass(
        listOf(
            "تعلم الرياضيات", "دروس الرياضيات للصف الخامس", "تلاوة سورة البقرة", "سورة يوسف كاملة", "وهزي اليك بجذع النخلة",
            "مباراة ريال مدريد وبرشلونة", "اهداف مباراة الاهلي", "طريقة عمل الكسكسي", "كسكس بالخضار", "كس كس مغربي", "اناشيد الاطفال",
            "قصص الانبياء", "اطباق رمضان", "كيف اتعلم البرمجة", "الجنسية السعودية", "شروط الحصول على الجنسية", "الجنسية الكندية",
            "مسلسل تركي رومانسي", "مباراة مثيرة", "ملخص مباراة مثيرة", "حقائق مثيرة عن الفضاء", "مثير للاهتمام", "مثير للجدل", "اتجاه القبلة",
            "القبلة", "ماين كرافت", "ماين كرافت بوس", "معركة البوس", "بوس بيبي", "نيك جونيور", "نيك فوجيسيك", "بيكيني بوتوم", "سبونج بوب",
            "راقصة باليه", "رقص الدبكة", "رقص الاطفال", "هز راسه", "اخبار ساخنة", "مشروبات ساخنة", "ماء ساخن", "قرار جريء", "بنات الجامعة",
            "العاب بنات", "الوان بنات", "تعليم القراءة", "سيارات", "بورنموث ضد ليفربول", "بورنيو", "الانجلوسكسونية", "سكسونيا", "ساسكس",
            "حلاقة سكسوكة", "من سيكسب المباراة", "فأضحى الامر", "فأضحك الجميع", "ملابس للجنسين", "ميلفين", "للختان", "بين الاصدقاء",
            "قراءة القرآن", "كرة القدم", "طبخ", "رسوم متحركة", "الدين الاسلامي", "حل واجبات", "اللغة العربية", "تجربة مثيرة",
            "الحياااة جميلة", "احسن اهدااااف الدوري", "ماذا يحدث في الكووون", "الله اكبر", "مباراة ممتااازة", "اسحاق", "إسحاق بن راهويه",
            "سيرة اسحاق عليه السلام",
        ),
    )

    @Test
    fun otherLanguagesOrdinary() = assertAllPass(
        listOf(
            "Das Kraftwerk", "Kunstwerk", "Ponte Vecchio Florence", "Telefon Hörer", "un baiser sur la joue", "sexta-feira",
            "la sexta", "Эссекс", "упорно работать", "спорные вопросы", "бесспорно", "дебаты", "колебания", "хохота", "канва", "голый король",
            "sexenio presidencial", "unissex", "sextante", "Sussex",
        ),
    )

    // ================================================================ behaviour ==================================================

    @Test
    fun nullBlankAndSymbolsOnlyAreNotExplicit() {
        assertThat(filter.isExplicit(null as String?)).isFalse()
        assertThat(filter.isExplicit("")).isFalse()
        assertThat(filter.isExplicit("   \t\n")).isFalse()
        assertThat(filter.isExplicit("🔥🔥🔥 !!! ...")).isFalse()
        assertThat(filter.isExplicit("12345 67890")).isFalse()
    }

    @Test
    fun anyOfSeveralTexts() {
        assertThat(filter.isExplicit("Funny cats", "Official Channel", null)).isFalse()
        assertThat(filter.isExplicit("Funny cats", "Hot Girls TV", null)).isTrue()
        assertThat(filter.isExplicit(null, null)).isFalse()
    }

    @Test
    fun explicitWordsAreFoundInLongTextsToo() {
        val long = "Welcome to my channel! ".repeat(8) + "twerking compilation"
        assertThat(filter.isExplicit(long)).isTrue()
        assertThat(filter.isExplicit("Welcome to my channel! ".repeat(8))).isFalse()
        // Only the start of a very long text is read: a pasted page must not cost a second.
        assertThat(filter.isExplicit("a".repeat(5_000) + " sex")).isFalse()
    }

    @Test
    fun theSameAnswerEveryTime_alsoFromManyThreads() {
        val titles = listOf("Sexy twerking", "Hot Wheels videos", "تعلم الرياضيات", "رقص شرقي", "Essex football", "kiss scene")
        val expected = titles.map { filter.isExplicit(it) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..400).map { i -> pool.submit<List<Boolean>> { titles.indices.map { filter.isExplicit(titles[(it + i) % titles.size]) } } }
            for ((i, future) in results.withIndex()) {
                val shifted = titles.indices.map { expected[(it + i + 1) % titles.size] }
                assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(shifted)
            }
        } finally {
            pool.shutdownNow()
        }
        assertThat(expected).containsExactly(true, false, false, true, false, true).inOrder()
    }

    @Test
    fun stretchedLettersNeedThreeToCount_insideAWord() {
        // "seeexy" is stretching; "seexy" is just another word, and so is "hoot" for "hot" ("Hoot" is an owl).
        assertThat(filter.isExplicit("seeexy")).isTrue()
        assertThat(filter.isExplicit("hoot girls")).isFalse()
        assertThat(filter.isExplicit("hooot girls")).isTrue()
        // The first and the last letter may run on: "sexxy", "ssex", "assss" are caught.
        assertThat(filter.isExplicit("sexxy")).isTrue()
        assertThat(filter.isExplicit("asssss")).isTrue()
        assertThat(filter.isExplicit("as soon as possible")).isFalse()
    }

    @Test
    fun arabicLongVowelsStretchedByTwo_countToo_otherLettersDoNot() {
        // ا و ي are lengthened by two or three: "بنات" is "بناات" and "بنااات". Other letters inside a word need three, as in English.
        assertThat(filter.isExplicit("رقص بنات")).isTrue()
        assertThat(filter.isExplicit("رقص بناات")).isTrue()
        assertThat(filter.isExplicit("رقص بنااات")).isTrue()
        assertThat(filter.isExplicit("رقص بننات")).isFalse()
        assertThat(filter.isExplicit("رقص بنننات")).isTrue()
    }

    @Test
    fun aListedWordNeverHidesOneBesideIt() {
        // The allowed phrase is not judged, the rest of the title is.
        assertThat(filter.isExplicit("naked eye")).isFalse()
        assertThat(filter.isExplicit("naked eye porn")).isTrue()
        assertThat(filter.isExplicit("Essex sexy girls")).isTrue()
        assertThat(filter.isExplicit("Bikini Bottom hot girls")).isTrue()
    }

    @Test
    fun wholeGateNeedsTheCompanionNear() {
        assertThat(filter.isExplicit("hot")).isFalse()
        assertThat(filter.isExplicit("hot weather and girls")).isFalse() // "girls" is three words away
        assertThat(filter.isExplicit("hot sunny girls")).isTrue() // two words away
        assertThat(filter.isExplicit("Hot Wheels videos")).isFalse() // weak companions count one word away only
        assertThat(filter.isExplicit("passionate french kiss")).isTrue() // before the trigger
        assertThat(filter.isExplicit("kiss")).isFalse()
    }
}
