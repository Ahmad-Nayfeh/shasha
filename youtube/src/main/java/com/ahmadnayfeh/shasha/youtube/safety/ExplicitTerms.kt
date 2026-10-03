package com.ahmadnayfeh.shasha.youtube.safety

/**
 * Everything the built-in filter treats as sexual, indecent or suggestive, in one place and grouped by what it is about.
 * [ExplicitFilter] reads these lists; nothing else does. Written the way people write them: the filter folds each entry
 * like it folds a title (accents, Arabic diacritics and letter variants, case), so "عارية" and "عاري" meet the same words and
 * "Étienne" is "etienne".
 *
 * **Where a term goes** (the first that fits; a term is judged against the folded words of a title, a channel name, a
 * description snippet or a search query):
 *
 * 1. `anywhere`: explicit inside any word, so "sexxxy", "freeporn" and "hotsexvideos" are caught. Only for a word that no ordinary
 *    word contains. Check a new one against a dictionary first (see below).
 * 2. `words`: explicit as a whole word, and as its regular plural, -ing, -ed or -er form ("boob" also catches "boobs", "dance"
 *    catches "dancing"). The default for anything that ordinary words contain: "ass" is explicit, "class" and "assassin" are not.
 *    Arabic words also lose the usual prefixes and suffixes (و ف ب ل ك ال, ات ين ون ها ه…), never down to fewer than three letters.
 * 3. `exact`: like `words` but without any prefix or suffix, for a word that meets others when it takes them.
 * 4. `phrases`: explicit as a run of whole words ("lap dance"); written together ("lapdance") they are caught too.
 * 5. [GATES]: ordinary alone, explicit beside a companion: "hot" ("hot weather" passes, "hot girls" does not), "kiss", "strip", "ساخنة".
 * 6. `alone`: explicit only as the whole text, a query typed by itself ("dick"): inside a longer title it is a name or another word.
 * 7. [ALLOWED]: ordinary words and phrases that something above would catch ("Essex", "naked eye", "comic strip"…). Their words
 *    are not judged at all.
 *
 * What is matched, and how, is in [TextFolding] (spelling tricks) and [Word] (leetspeak, prefixes and suffixes, stretched letters).
 *
 * **Decisions** (the owner prefers blocking when unsure about suggestive content, and never breaking an ordinary search):
 * - "sex" is blocked wherever it is in a word: "sex education" is blocked; "Essex football", "sextant" and "unisex" pass.
 * - "hot" alone is ordinary. "hot girls", "hot scene", "hot dance", "hot mom"… are not ([GATES]); "Hot Wheels videos" passes.
 * - "kiss" alone is ordinary: "Kiss FM", the band, "Kiss the Girl" and "Kiss the Rain" (the piano piece) pass. Kissing *scenes*,
 *   kissing challenges and pranks, "french kiss", "passionate kiss" and "make out" are blocked.
 * - "strip" alone is ordinary: comic strip, landing strip, a strip of paper, an LED strip, a football team's strip, "strip wallpaper".
 *   "strip club", "strip tease", "strip pole", "striptease" and "stripper" (not a wire or a paint stripper) are blocked.
 * - Belly dance (belly dance, رقص شرقي, راقصة…) is blocked in every language the lists cover; dancing alone is not.
 * - A bikini is blocked, "Bikini Bottom" (SpongeBob) is not; "bra" is blocked, "bra-ket" is not. So are swimsuit and swimwear
 *   *displays* ("Swimsuit Competition", "Miami Swim Week", "Swimwear | Full Show") and the lingerie brand's shows ("Victoria's Secret");
 *   a swimsuit in an ordinary title ("swimsuit for kids", "swimwear review") is not blocked.
 * - "lesbian" is blocked as the adult sites' biggest category; "Lesbos" (the island) and Arabic names such as اسحاق (Isaac) pass.
 * - Arabic: "قبلة" alone is the prayer direction, "جنسية" is also nationality ("الجنسية السعودية"), "مثير" is also "exciting" ("مباراة مثيرة",
 *   "مثير للاهتمام"), "بوس" is also a game boss, "كس كس" is couscous, "نيك جونيور" is Nick Jr.: those uses are allowed, the sexual ones are not.
 * - Vulgar words that are not sexual in themselves (shit, bitch) are blocked too: "indecent" covers them. Mild ones (damn, hell) are not.
 *
 * **Adding a term**: put it in the category it belongs to, in the list its kind needs, and add a line to ExplicitFilterTest's tables
 * (a title that must be blocked and, for a short or common word, an ordinary title that must not). To check a new `anywhere` or
 * `words` entry against ordinary words, run the opt-in scan in ExplicitTermsScanTest with a word list:
 * `SAFETY_WORDLIST=/path/words.txt ./gradlew :youtube:testDebugUnitTest --tests '*ExplicitTermsScanTest'`, and read what it prints.
 *
 * A word list can never be complete (new words, new tricks, a clean title on an explicit video): this is one layer. YouTube's
 * Restricted Mode (see NewPipeDownloader) is another, and a parent's own blocked words in the TV app a third.
 */
internal object ExplicitTerms {

    val rules: Rules by lazy {
        Rules(
            anywhere = CATEGORIES.flatMap { it.anywhere },
            words = CATEGORIES.flatMap { it.words },
            exact = CATEGORIES.flatMap { it.exact },
            phrases = CATEGORIES.flatMap { it.phrases },
            gates = GATES,
            alone = CATEGORIES.flatMap { it.alone },
            allowed = ALLOWED,
        )
    }

    /** One topic of the lists. [name] only says what it is about. */
    internal class Category(
        val name: String,
        val anywhere: List<String> = emptyList(),
        val words: List<String> = emptyList(),
        val exact: List<String> = emptyList(),
        val phrases: List<String> = emptyList(),
        val alone: List<String> = emptyList(),
    )

    /** One term (or more) per whitespace; everything after a "#" on a line is a comment. */
    private fun terms(text: String): List<String> {
        val out = ArrayList<String>()
        for (line in text.lineSequence()) {
            val end = line.indexOf('#').let { if (it < 0) line.length else it }
            var i = 0
            while (i < end) {
                while (i < end && line[i].isWhitespace()) i++
                val start = i
                while (i < end && !line[i].isWhitespace()) i++
                if (i > start) out += line.substring(start, i)
            }
        }
        return out
    }

    /** One entry per line (for phrases and allowed phrases); everything after a "#" on a line is a comment. */
    private fun lines(text: String): List<String> {
        val out = ArrayList<String>()
        for (line in text.lineSequence()) {
            val end = line.indexOf('#').let { if (it < 0) line.length else it }
            val entry = line.substring(0, end).trim()
            if (entry.isNotEmpty()) out += entry
        }
        return out
    }

    val CATEGORIES: List<Category> = listOf(

        // ================================================================ ENGLISH ===========================================

        Category(
            name = "Sex, sexual acts and sexual talk",
            anywhere = terms(
                """
                sex                  # sexy, sexual, sexting, sexcam, sex tape, freesex… (Essex, Sussex, sextant, unisex are in ALLOWED)
                orgasm masturbat     # orgasms, masturbate, masturbation
                fellatio cunnilingus foreplay kamasutra
                cumshot blowjob handjob rimjob footjob titjob gangbang bukkake deepthroat threesome
                incest bestiality pedophil paedophil
                prostitut            # prostitute, prostitution
                callgirl
                erotik erotiq        # German erotik, French érotique (not "erotic": sclerotic, heterotic and xerotic contain it)
                """,
            ),
            words = terms(
                """
                intercourse sodomy orgy orgies
                horny horney kinky kink
                erotic erotica eroticism erotical erotically homoerotic autoerotic    # (see above)
                sensual sensuality sensually sensuel   # not "anywhere": "consensual" contains it
                seduce seduction seductive seductress seductively    # not "anywhere": "miseducation" contains "seduc"
                molest molestation molester       # not "anywhere": "unmolested" contains it
                jizz cum             # "cum laude" is in ALLOWED
                creampie boner wank
                rape rapist          # "rape seed" is in ALLOWED
                brothel
                """,
            ),
            exact = terms(
                """
                escort escorts       # police and military escorts, Ford Escort… are in ALLOWED; "escorted by police" is not "escort"
                """,
            ),
            phrases = lines(
                """
                blow job
                hand job
                rim job
                foot job
                tit job
                deep throat          # (Watergate's Deep Throat is a title nobody searches in this app)
                jerk off
                jack off
                kama sutra
                call girl
                wet dream
                one night stand
                friends with benefits
                sugar daddy
                make out             # make-out, making out; "make out of" and "make out what" are in ALLOWED
                dry hump
                rape scene
                """,
            ),
        ),

        Category(
            name = "Pornography, adult sites and adult ratings",
            anywhere = terms(
                """
                porn                 # porno, pornhub, pornstar, pornography… ("agapornis", the lovebird, is in ALLOWED)
                xxx                  # XXXL and XXXS sizes are in ALLOWED
                nsfw hentai doujin rule34
                xnxx xhamster redtube youporn brazzers bangbros onlyfans fansly chaturbate stripchat
                livejasmin camsoda bongacams myfreecams spankbang eporner thumbzilla tnaflix youjizz tube8 xtube
                camgirl camwhore
                playboy
                """,
            ),
            words = terms(
                """
                ecchi               # not "anywhere": "Vecchio" contains it
                xvideo              # not "anywhere": the French "jeuxvideo" contains it
                pr0n r34
                hotwife
                lesbian              # lesbians: the biggest search of the adult sites ("Lesbos", the island, is not "lesbo" + s: only the full word is listed)
                """,
            ),
            phrases = lines(
                """
                x rated
                blue movie
                blue film
                only fans
                cam girl
                webcam girl
                webcam show
                cam show
                adult only           # "adult content", "adult video" and the like are in GATES
                rule 34
                """,
            ),
        ),

        Category(
            name = "Nudity",
            anywhere = terms(
                """
                nudity nudist nudism
                upskirt downblouse underboob sideboob nipslip
                """,
            ),
            words = terms(
                """
                nude                # "nude lipstick", "nude makeup" and the like are in ALLOWED
                naked nakedness nakid nakd nakked nekid nekked nekkid    # "naked eye", "naked cake"… are in ALLOWED
                naturist naturism
                topless             # "topless Jeep" is in ALLOWED
                nipple              # bottle and plumbing nipples are in ALLOWED
                """,
            ),
            phrases = lines(
                """
                nip slip
                wardrobe malfunction
                birthday suit
                in the buff
                skinny dipping
                full frontal
                see through dress
                see through top
                see through shirt
                see through bra
                see through bikini
                see through lingerie
                see through swimsuit
                see through clothes
                see through clothing
                see through outfit
                see through pants
                see through leggings
                see through haul
                see through try on
                see through yoga pants         # "see thru" is as common a spelling
                see through yoga leggings
                see through workout leggings
                see through gym leggings
                see thru dress
                see thru top
                see thru shirt
                see thru bra
                see thru bikini
                see thru lingerie
                see thru clothes
                see thru outfit
                see thru pants
                see thru leggings
                see thru yoga pants
                see thru yoga leggings
                transparent try on
                """,
            ),
        ),

        Category(
            name = "Explicit body terms",
            anywhere = terms("dildo"),
            words = terms(
                """
                boob boobie boobies titty titties tits busty
                booty                # "pirate booty" is in ALLOWED
                ass arse asshole butthole anus anal    # "Annals" is in ALLOWED
                cleavage             # "cell cleavage", "mineral cleavage"… are in ALLOWED
                penis vagina vaginal vulva clitoris clit genital genitals genitalia scrotum testicle
                pussy                # "pussy cat" and "pussy willow" are in ALLOWED
                cunt twat
                """,
            ),
            alone = terms(
                """
                dick cock tit butt   # names and ordinary words inside a title ("Moby Dick"), vulgar when typed alone; see also GATES
                """,
            ),
        ),

        Category(
            name = "Fetish and kink",
            anywhere = terms("fetish bdsm dominatrix femdom maledom shibari"),
            words = terms("bondage spank spanking abdl"),
            phrases = lines(
                """
                foot worship
                feet pics
                """,
            ),
        ),

        Category(
            name = "Vulgar words (indecent, not only sexual)",
            anywhere = terms("fuck                # fucking, motherfucker…"),
            words = terms(
                """
                fck fuk fuc fcuk phuck fucc fuq
                shit shitty bullshit horseshit dipshit shithead shitshow shithole shitfaced
                slut slutty whore skank thot bitch
                milf gilf dilf
                dickhead cocksucker
                """,
            ),
        ),

        Category(
            name = "Suggestive dance",
            anywhere = terms(
                """
                striptease stripclub
                """,
            ),
            words = terms(
                """
                twerk twerkin twerkteam twerkclub    # twerking, twerked, twerks (not "anywhere": Kraftwerk, Kunstwerk, Stadtwerke contain it)
                burlesque stripper                   # a wire or a paint stripper is in ALLOWED
                """,
            ),
            phrases = lines(
                """
                belly dance          # belly dancer, belly dancing, bellydance
                oriental dance
                lap dance
                pole dance
                private dance
                table dance
                exotic dance
                go go dancer
                gogo dancer
                booty dance
                dirty dance          # dirty dancing: see also GATES
                naughty dance
                sensual dance
                raks sharqi          # Franco-Arabic spellings of belly dance
                raqs sharqi
                raks sharki
                raqs sharki
                raks baladi
                raqs baladi
                danza del vientre
                danse du ventre
                gobek dansi
                strip tease
                rub and blow
                rub blow
                rub n blow
                """,
            ),
        ),

        Category(
            name = "Suggestive clothing",
            anywhere = terms(
                """
                lingerie             # (not "lingeri": "lingering" contains it; not "langeri": "boulangerie" does)
                bikini monokini trikini microkini # "Bikini Bottom" and "Bikini Atoll" are in ALLOWED
                """,
            ),
            words = terms(
                """
                lingeri lingery lingeree lingerry langerie lanjerie lanjeri lenjerie lingerei   # misspellings of lingerie
                thong panties panty bra brassiere     # "bra-ket" is in ALLOWED
                negligee boudoir
                """,
            ),
            phrases = lines(
                """
                try on haul          # clothing hauls: the owner's rule, any try-on haul
                tryon haul
                bikini haul
                lingerie haul
                swimsuit try on
                bikini try on
                underwear try on
                underwear haul
                victoria s secret                # the lingerie brand and its runway show ("Victoria's Secret": the apostrophe splits the word)
                victorias secret
                hooters girls                    # the restaurant's waitresses
                hooters girl
                hooters waitress
                arab girl dance                  # "Arab girl dancing", "Arabic girl's dance performance": the English of رقص بنات
                arab girls dance
                arab girl s dance
                arabic girl dance
                arabic girls dance
                arabic girl s dance
                arabian girl dance
                arabian girls dance
                sports illustrated swimsuit      # the swimsuit issue
                si swimsuit
                swim week                        # Miami Swim Week and the like: runway shows of swimwear
                art hearts fashion               # its organiser
                black tape project               # a producer of swimwear and lingerie runway shows
                in swimsuit                      # "Top 20 in swimsuits": pageant rounds
                in a swimsuit
                in swimwear
                butt lift                        # "butt lifting leggings" and the surgery
                butt lifting
                booty lift
                booty lifting
                """,
            ),
        ),

        Category(
            name = "Kissing, scenes, ASMR and other suggestive videos",
            phrases = lines(
                """
                how to kiss
                kissing tips
                kissing tutorial
                french kiss
                girls kissing        # "girl kissing girl", "girls kiss girls": kissing videos of the adult kind; "Kiss the Girl" is not one
                girls kissing girls
                girl kissing girl
                girls kiss girls
                girl kiss girl
                kissing girls
                ear licking          # ASMR
                ear lick
                ear eating
                ear nibbling
                ear kissing
                asmr licking
                licking asmr
                asmr kissing
                kissing asmr
                nuru massage
                tantric massage
                erotic massage
                happy ending massage
                """,
            ),
        ),

        // ================================================================ ARABIC ============================================

        Category(
            name = "Arabic: sex and pornography",
            anywhere = terms(
                """
                سكس                      # sex: سكسي، سكسية، السكس. Couscous (كسكس), Essex, Saxon (سكسون) are in ALLOWED
                اباحي                    # pornographic: إباحي، إباحية، الإباحية
                هنتاي                    # hentai
                اونليفانز                # OnlyFans written as one word
                ستربتيز ستريبتيز         # striptease
                تويرك                    # twerk: تويركينج، تويركنج
                """,
            ),
            words = terms(
                """
                سيكس                     # sex, spelled as it sounds (not "anywhere": سيكسب is "he will win")
                جنس                      # sex: الجنس، جنسي، جنسية، جنسيات. Nationality (الجنسية السعودية) and "الجنس البشري" are in ALLOWED
                بورن بورنو بورنوغرافي بورنوجرافي بورنوغرافيا   # porn (not "anywhere": بورنموث is Bournemouth, بورنيو is Borneo)
                شهوه                     # شهوة، شهوات، الشهوة
                شهواني
                اغراء اغواء              # إغراء، إغواء
                مثير                     # مثيرة، مثيرات، المثير: arousing, but also "exciting" (مباراة مثيرة، مثير للاهتمام: see ALLOWED)
                ايروتيك اروتيك           # erotic
                لانجري لانجيري لنجري لنجيري      # lingerie
                بكيني بيكيني             # bikini ("Bikini Bottom" is in ALLOWED)
                قبلات                    # kisses (قبلة alone is the prayer direction: see GATES)
                مضاجعه                   # intercourse
                لواط سحاق سحاقيه         # sexual acts between people of one sex, as the words are used in sexual content
                خليع خليعه خلاعه         # lewd
                """,
            ),
            phrases = lines(
                """
                اونلي فانز               # OnlyFans
                ستريب تيز
                فيكتوريا سيكريت          # Victoria's Secret
                فيكتوريا سيكرت
                فكتوريا سيكرت
                """,
            ),
        ),

        Category(
            name = "Arabic: nudity and the body",
            words = terms(
                """
                عاري عراه                # naked: عارية، عاريات، العاري، عراة
                عريان                    # عريانة، عريانين (Levantine and Egyptian)
                تعري                     # undressing: التعري. (متعرية is below)
                متعري متعريه
                طيز                      # ass
                بزاز بزايز               # boobs. (A draper, البزاز, is a rare name: accepted)
                نهود                     # breasts
                """,
            ),
            phrases = lines(
                """
                ملابس داخليه             # underwear: ملابس داخلية (the owner's rule)
                بدون ملابس
                بلا ملابس
                """,
            ),
        ),

        Category(
            name = "Arabic: suggestive dance",
            words = terms(
                """
                راقصه راقصات             # a dancer, usually a belly dancer: راقصة. (A ballet dancer is in ALLOWED)
                رقاصه رقاصات
                """,
            ),
            phrases = lines(
                """
                رقص شرقي                 # belly dance: الرقص الشرقي
                رقص بلدي
                رقص خليجي
                رقص مثير
                رقص ساخن
                رقص ايحائي
                بدله رقص                 # a dance costume: بدلة رقص، بدلات رقص
                بدلات رقص
                """,
            ),
        ),

        Category(
            name = "Arabic: vulgar words",
            anywhere = terms(
                """
                شرموط                    # شرموطة، شرموطات
                قحبه                     # قحبة
                دعاره                    # دعارة: prostitution
                """,
            ),
            words = terms(
                """
                شراميط عاهره عاهر
                نياكه متناك منيوك
                """,
            ),
            exact = terms(
                """
                ميلف                     # MILF (not "ميلفين", Melvin)
                فاضح فاضحه               # indecent (not "فأضحى" and "فأضحك", which fold to the same letters with a prefix)
                لخت                      # naked, in Persian (not "للختان", for circumcision)
                كس كسم كسمك              # vulgar. Couscous written as two words (كس كس) is in ALLOWED
                نيك                      # vulgar. Nick (نيك جونيور، نيك جوناس) is in ALLOWED
                زب
                بوسه بوسات               # بوسة، بوسات: kiss. Prefixes would make "بوسي" (a name) a kiss
                """,
            ),
            alone = terms(
                """
                بوس              # a kiss, but also a game boss (معركة البوس): only when it is the whole query
                هز               # shaking: "وهزي إليك بجذع النخلة" (Maryam 25) is a recitation. See also GATES
                """,
            ),
        ),

        // ================================================================ FRANCO-ARABIC ====================================

        Category(
            name = "Franco-Arabic (Arabizi): the Arabic words typed with Latin letters and digits",
            words = terms(
                """
                sks sksy seks sexxy sekssi
                3arya 3aryan 3aryana 3ariya 3ariyeh
                ka7ba kahba qa7ba qahba a7ba 9a7ba 9ahba
                sharmouta sharmota sharmoota sharmuta sharmoute charmouta
                metnak mtnak mitnak
                """,
            ),
            phrases = lines(
                """
                kos omak
                kos ommak
                banat sa5na
                bnat sa5na
                banat sa5en
                bnat sa5en
                banat 3arya
                banat 3aryat
                """,
            ),
        ),

        // ================================================================ OTHER LANGUAGES ===================================

        Category(
            name = "Spanish and Portuguese",
            anywhere = terms("desnud                # desnuda, desnudo, desnudas, desnudez"),
            words = terms(
                """
                tetas culo puta follar follando follada perreo perrear
                peitos putaria buceta punheta nua nuas
                """,
            ),
        ),

        Category(
            name = "French",
            words = terms(
                """
                nue seins fesses salope putain pute nichons branler nudite   # not "baiser" (a kiss)
                """,
            ),
        ),

        Category(
            name = "German and Dutch",
            words = terms(
                """
                nackt nackte nackten nackter nacktes nacktbilder nacktfotos naakt
                titten arsch arschloch ficken fotze schlampe hure huren
                """,
            ),
        ),

        Category(
            name = "Italian",
            words = terms("nuda tette scopare puttana"),
        ),

        Category(
            name = "Turkish and Indonesian",
            words = terms(
                """
                seksi seksual ciplak sikis amcik yarrak orospu
                bokep telanjang mesum
                """,
            ),
        ),

        Category(
            name = "Hindi and Urdu, typed with Latin letters",
            exact = terms("nangi nanga     # naked (not the Indonesian \"nangis\")"),
            words = terms(
                """
                chudai chudayi gaand
                bhosdi bhosdike bhosda madarchod behenchod chodna
                """,
            ),
        ),

        Category(
            name = "Persian and Urdu, in Arabic letters (they fold onto the Arabic ones: سکس is سكس, پورن is بورن)",
            words = terms(
                """
                برهنه
                ننگا ننگي                # naked (Urdu)
                """,
            ),
        ),

        Category(
            name = "Russian",
            anywhere = terms(
                """
                секс                     # секстант and секстет, Эссекс and Суссекс are in ALLOWED
                эротик эротич
                обнажен                  # обнажённая
                стриптиз
                интим                    # also "интим услуги"
                жопа жопу жопы жопе
                сиськ
                пизд
                бляд                     # блядь
                шлюх
                """,
            ),
            // Whole words, not "anywhere": "упорно", "спорно" and "опорных" contain "порно", "колебания" and "дебаты" contain "ебан" and "ебат".
            words = terms(
                """
                порно порнуха порнухи порнуху порнография порнографии порнографию порнографический порнографические порнофильм порнофильмы
                голая голые голый голых голую голым голыми   # "Голый король" is in ALLOWED
                минет
                хуй хуя хую хуем хуе
                трахать трахаться трахает трахал
                ебать ебал ебаный ебаная ебаные ебаного ебучий ебучая ебут ебёт ебу ебаться ебанутый ебанутая
                """,
            ),
        ),
    )

    // ======================================================================================================================
    // GATES: ordinary words that are explicit beside a companion. [Gate.after] and [Gate.before] count the words from the trigger
    // (1 = the next word). Say it with the nearest companions only where a farther one would catch ordinary titles.
    // ======================================================================================================================

    val GATES: List<Gate> = listOf(
        // ---- English ----
        // "hot girls", "hot young girls", "hot mom": two words reach; "Hot Wheels girls" is the price.
        Gate(
            triggers = terms("hot"),
            companions = terms(
                """
                girl babe chick woman women lady ladies mom mama mommy milf wife wives aunty auntie teen teenager teacher nurse maid
                student bikini lingerie sex naked nude porn kiss couple secretary
                """,
            ),
            after = 2,
        ),
        // "hot videos", "hot scene", "hot dance": the next word only, or "Hot Wheels videos" and "hot dog video" would go.
        Gate(
            triggers = terms("hot"),
            companions = terms("video clip pic photo picture model body bodies dance scene romance selfie shoot photoshoot cam tiktok reel show"),
            after = 1,
        ),
        Gate(
            triggers = terms("kiss"),
            companions = terms("scene compilation challenge prank french passionate deep sexy hot steamy tutorial lesson tip"),
            before = 2,
            after = 1,
        ),
        Gate(
            triggers = terms("strip"),
            companions = terms("tease teaser club bar dance pole poker show girl woman women naked nude"),
            before = 1,
            after = 1,
        ),
        Gate(
            triggers = terms("scene"),
            companions = terms("love bed bedroom intimate steamy hot kiss nude naked erotic sensual lovemaking"),
            before = 1,
        ),
        Gate(
            triggers = terms("adult"),
            companions = terms(
                """
                content video film movie site only toy entertainment humor humour joke stuff channel chat cam club dating magazine store
                shop dvd comic anime cartoon
                """,
            ),
        ),
        Gate(triggers = terms("mature"), companions = terms("content audience women woman lady ladies mom milf girl only")),
        Gate(triggers = terms("naughty"), companions = terms("girl dance video clip maid nurse teacher bedroom secretary america school")),
        Gate(triggers = terms("dirty"), companions = terms("talk pic picture photo chat joke minded mind dance scene movie film video clip")),
        Gate(triggers = terms("spicy"), companions = terms("scene video clip girl dance pic photo content chat")),
        Gate(triggers = terms("steamy"), companions = terms("scene kiss romance video clip movie film novel affair")),
        Gate(triggers = terms("provocative"), companions = terms("dance outfit clothes clothing dress girl photoshoot video pose")),
        Gate(triggers = terms("skimpy"), companions = terms("outfit bikini clothes clothing dress costume swimsuit")),
        Gate(triggers = terms("revealing"), companions = terms("outfit dress clothes clothing bikini swimsuit photoshoot video")),
        // "curvy plus size women": the companion may come a few words after.
        Gate(triggers = terms("curvy"), companions = terms("girl woman women model body bikini lingerie babe"), after = 3),
        Gate(triggers = terms("thick"), companions = terms("girl woman women thigh body babe")),
        Gate(triggers = terms("transparent"), companions = terms("dress clothes clothing top shirt bikini lingerie haul swimsuit outfit pants leggings")),
        Gate(triggers = terms("underwear"), companions = terms("haul model challenge girl photoshoot pic show runway")),
        // Pageants and fashion shows of swimwear are swimsuit displays: "Swimsuit Competition", "Swimwear & Underwear".
        Gate(
            triggers = terms("swimsuit"),
            companions = terms("haul model photoshoot shoot calendar issue competition round showcase show runway pageant contest parade performance"),
        ),
        Gate(triggers = terms("swimwear"), companions = terms("haul model models photoshoot shoot runway show underwear lingerie intimates bikini"), after = 2),
        // "Calibri Swimwear | Resort 2024 | Full Show": the show may come a few words later.
        Gate(triggers = terms("swimwear"), companions = terms("show shows runway"), after = 5),
        Gate(triggers = terms("sheer"), companions = terms("dress top shirt blouse pants leggings outfit lingerie bikini haul clothes clothing nightgown robe")),
        Gate(triggers = terms("asmr"), companions = terms("kiss lick sensual naughty seductive hot"), after = 2),
        Gate(triggers = terms("uncensored"), companions = terms("scene anime video movie film edition leaked")),
        // Names inside a title ("Moby Dick", "Dick Tracy") and ordinary words ("cock-a-doodle-doo"): only the vulgar companions.
        Gate(
            triggers = terms("dick"),
            companions = terms("pic picture photo size joke suck big huge small hard hot massage cam rating flash tease riding sex nude video"),
            before = 1,
            after = 1,
        ),
        Gate(
            triggers = terms("cock"),
            companions = terms("suck ring pic picture size big huge hard hot tease riding slut whore fuck sex nude massage cam rating flash"),
            before = 1,
            after = 1,
        ),

        // ---- Arabic: the noun comes first, the adjective after it ("بنات ساخنة"), so the companion is before the trigger ----
        Gate(
            triggers = terms("رقص ترقص يرقص ترقصن يرقصن رقصت"),
            companions = terms("شرقي بلدي خليجي ساخن مثير ايحائي بنات فتيات نسائي عاري هز"),
            before = 2,
            after = 2,
        ),
        Gate(
            triggers = terms("هز هزي يهز تهز هزهزه"),
            companions = terms("خصر وسط بطن ارداف موخره جسم صدر جسد بنات"),
            after = 2,
        ),
        Gate(
            triggers = terms("ساخن"),
            companions = terms("بنات فتيات نساء مقاطع مشاهد مشهد صور فيديو فيديوهات قصص افلام فيلم رقص جسم مثير"),
            before = 2,
            after = 0,
        ),
        Gate(
            triggers = terms("جريء جريئه جريئات جريئين"), // the hamza makes the feminine and the plural other words once folded
            companions = terms("لقطات مشاهد مشهد صور مقاطع فيديو فيديوهات بنات فتيات ملابس ازياء رقص افلام تصوير"),
            before = 2,
            after = 0,
        ),
        Gate(
            triggers = terms("حميمي"),
            companions = terms("مشهد مشاهد لقطات علاقه علاقات لحظات فيديو فيديوهات مقاطع"),
            before = 2,
            after = 0,
        ),
        Gate(
            triggers = terms("مشهد مشاهد لقطه لقطات"),
            companions = terms("قبله قبلات تقبيل بوس رومانسي سرير حميمي ساخن جريء عاري اغراء فاضح جنسي"),
            after = 1,
        ),
        Gate(triggers = terms("قبله"), companions = terms("ساخن فرنسي عميق حار طويل شفاه"), after = 1),
        Gate(
            triggers = terms("اغتصاب"),
            companions = terms("مشهد مشاهد لقطه لقطات فيديو فيديوهات مقطع مقاطع فتاه بنات طفله فتيات"),
            before = 2,
            after = 2,
        ),
    )

    /** The nationalities that follow "الجنسية": what makes it nationality, not sex. */
    private val NATIONALITIES = terms(
        """
        سعودي كندي تركي امريكي الماني سويدي بريطاني مصري سوري اردني لبناني عراقي يمني اماراتي كويتي قطري بحريني عماني فلسطيني
        سوداني مغربي جزائري تونسي ليبي فرنسي اسباني هولندي ماليزي استرالي اوروبي اجنبي ايطالي روسي نرويجي دنماركي
        """,
    )

    // ======================================================================================================================
    // ALLOWED: ordinary words that the lists above would catch. A word listed here is not judged at all, so only add what is
    // really ordinary, and as narrow as it can be. A phrase is matched like any other (plurals, Arabic prefixes), written
    // together too ("bikinibottom").
    // ======================================================================================================================

    val ALLOWED: List<String> =
        terms(
            """
            # ---- words that contain "sex" ----
            essex sussex middlesex wessex unisex
            sextant sextet sextette sexton sextuple sextuplet sextillion sexagenarian sexagenary sexagesimal sexennial
            sexenio sexta sexto            # Spanish and Portuguese: sixth (sexta-feira)
            asexual asexually asexuality   # biology: asexual reproduction
            секстант секстет эссекс суссекс уэссекс миддлсекс
            sextante unissex sexteto assexuada assexuado lasexta    # Portuguese and Spanish: sextant, unisex, sextet, asexual, a TV channel
            sensex                                                  # the Indian share index
            5ks                                                     # 5K runs: a 5 is an s, so "5ks" would be "sks"
            lxxx lxxxi lxxxiv lxxxv lxxxvi lxxxix                  # Roman numerals 80 to 89
            xxxi xxxiv xxxv xxxvi xxxix                           # Roman numerals 31 to 39 (XXXII is XXXI with a longer last letter)
            اسكس ايسكس ساسكس سوسكس ويسكس ميدلسكس                  # Essex, Sussex, Wessex, Middlesex
            سكسون انجلوسكسون انغلوسكسون                           # Saxon, Saxony, Anglo-Saxon (the usual endings come with the word)
            سكسوكه                                                # a goatee
            جنسون                                                 # Jenson
            للجنسين                                                                    # "for both sexes" (unisex clothing…)
            # ---- other words that contain a listed word ----
            agapornis pornichet            # lovebirds, and a French seaside town (contain "porn")
            xxxl xxxs                      # sizes: XXXL, XXXXL, XXXS
            كسكسو                          # couscous, as it is written in the West (كسكس and كسكسي are below)
            """,
        ) + lines(
            """
            # ---- phrases that contain a listed word ----
            bikini bottom                  # SpongeBob
            bikini atoll
            bra ket                        # physics: bra-ket notation
            cum laude
            pussy cat
            pussy willow
            naked eye
            naked mole rat
            naked cake
            naked neck
            naked juice
            naked scientist
            nude lipstick
            nude lip
            nude makeup
            nude shade
            nude color
            nude colour
            nude nails
            nude palette
            nude eyeshadow
            nude heels
            nude shoes
            topless jeep
            pirate booty
            pirate s booty                 # "Pirate's Booty" (an apostrophe ends a word)
            bottle nipple
            baby nipple
            feeding nipple
            pipe nipple
            grease nipple
            brass nipple
            pacifier nipple
            cell cleavage
            embryo cleavage
            mineral cleavage
            bond cleavage
            dna cleavage
            protein cleavage
            cleavage furrow
            wire stripper
            paint stripper
            cable stripper
            wallpaper stripper
            stripper clip
            stripper plate
            police escort
            military escort
            security escort
            convoy escort
            motorcade escort
            ford escort
            escort mission
            escort quest
            escort carrier
            escort vehicle
            make out of
            make out the
            make out what
            make out who
            make out which
            make out that
            make out how
            rape of the lock                 # Pope's poem
            rape seed
            rape oil
            rape field
            oilseed rape
            hot video game
            голый король                   # the fairy tale
            # ---- Arabic ----
            كس كس                          # couscous written as two words
            نيك جونيور                     # Nick Jr.
            نيك جر
            نيك جوناس
            نيك فوجيسيك                    # Nick Vujicic
            نيك فوجيسيتش
            نيك فويتشيتش
            نيك فويجيسيك
            نيك نيم
            بيكيني بوتوم                   # SpongeBob's Bikini Bottom
            بيكيني بوتم
            بيكيني بوتون
            حصول علي جنسيه                 # getting a nationality: الحصول على الجنسية
            تقديم علي جنسيه
            اكتساب جنسيه
            فقدان جنسيه
            جنس بشري                       # the human race (الجنس البشري)
            راقصه باليه                    # a ballet dancer
            راقصات باليه
            """,
        ) +
        // "الجنسية" is nationality as often as sex: "الجنسية السعودية", "الحصول على الجنسية". Allowed beside a nationality or the words
        // that go with one; "العلاقة الجنسية" and a lone "جنسية" are not.
        NATIONALITIES.map { "جنسيه $it" } +
        terms("حصول شروط طلب منح سحب اسقاط قانون").map { "$it جنسيه" } +
        terms("ثانيه اخري مزدوجه").map { "جنسيه $it" } +
        // "مثير" is "exciting" as often as "arousing": "مباراة مثيرة", "مثير للاهتمام". Allowed with the nouns and the "for…" of the first.
        terms("اهتمام جدل دهشه اعجاب فضول قلق ضحك استغراب انتباه تساؤل سخريه شك حيره اشمئزاز خوف رعب تفكير حماس تعجب عجب غضب حزن شفقه")
            .map { "مثير $it" } +
        terms(
            "حقائق معلومات اخبار مغامره مغامرات مباراه مباريات مواجهه مواجهات نهايه نهايات احداث سباق تحدي اكتشافات اسرار رحله رحلات منافسه منافسات اهداف تجربه تجارب",
        ).map { "$it مثير" }

}
