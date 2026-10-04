<div dir="rtl">

# مصدر الفيديوهين التعريفيين لـ«شاشة»

هذا المجلد يحوي كل ما يلزم لإعادة إنتاج الفيديوهين من المستودع نفسه: نص التعليق الصوتي، وسكربتات توليد الصوت وتركيبه،
ومشروع Remotion الذي يرسم المشاهد.

| الفيديو | المدة | ما فيه |
|---|---|---|
| `docs/video/shasha-explainer.mp4` (التلفاز) | **6:13** (11187 إطاراً، 30 إطاراً في الثانية، 1920×1080) | للأهل: يوتيوب هادئ لتلفاز العائلة بلا إعلانات ولا تسجيل دخول؛ ملفات الأطفال (القنوات المسموحة، والحصة اليومية، وساعات المشاهدة)؛ «الوضع الجاد» للأطفال وللكبار كلٌّ على حدة؛ الحارس؛ المقترحة وShorts لكل ملف؛ صفحة القناة وترتيبها؛ البحث بالصوت؛ الجوال ريموتاً و«قبول / رفض»؛ التحديث بضغطة؛ أي تلفاز يصلح؛ ثم التثبيت بحقيبة الكمبيوتر في ثماني خطوات. |
| `docs/video/shasha-mobile.mp4` (الجوال) | **2:03** (3691 إطاراً) | تنزيل ملف التطبيق والسماح بالتثبيت مرة واحدة؛ البحث؛ صفحات القنوات؛ التشغيل في الخلفية؛ صورة داخل صورة؛ السرعة؛ تخطي فقرات الرعاية؛ التنزيل (فيديو أو صوت)؛ الاشتراكات؛ السجل؛ التحديث بضغطة. شاشات التطبيق مرسومة من جديد بنصوص `mobile/src/main/res/values-ar/strings.xml`. |
| `docs/video/shasha-explainer-en.mp4` (التلفاز، بالإنجليزية) | **4:53** (8801 إطار) | فيديو التلفاز نفسه بالإنجليزية: التعليق بصوت Christopher، والمسرح معكوس من اليسار إلى اليمين، ولقطات التطبيق من واجهته الإنجليزية. صفحة التحكم من الجوال تبقى عربية كما هي في التطبيق. |
| `docs/video/shasha-mobile-en.mp4` (الجوال، بالإنجليزية) | **1:40** (3005 إطارات) | فيديو الجوال نفسه بالإنجليزية. |

- **الصوت: ElevenLabs، الصوت «Abdullah - Professional, and Energetic»** (`voice_id` = `pCKbQ4EPGE06zpEPGNvS`، النموذج
  `eleven_multilingual_v2`، بلا `language_code`، الإخراج `mp3_44100_192`). يُولَّد **حصراً** عبر سير العمل
  `.github/workflows/voice.yml` والسكربت `voice/run.py`؛ والمفتاح سرّ المستودع `ELEVEN_LABS` ولا يُكتب في أي ملف ولا يُستعمل محلياً.
- **البديل:** Microsoft Edge `ar-SA-HamedNeural` عبر `edge-tts` (مجاني، للتلفاز فقط): انظر «الصوت البديل» أدناه.
- الصورة التوضيحية لدليل التثبيت (`docs/images/install/*.jpg`) تُستخرج من مشاهد التثبيت نفسها.

## المحتويات

| المسار | ما فيه |
|---|---|
| `narration/scenes.json` | **مصدر نص التلفاز** (28 مشهداً): لكل مشهد ثلاث صيغ متوازية: `display` (ما يُكتب في الترجمة)، و`tts` (المنطوق بلا تشكيل، والمصطلحات مكتوبة كما تُلفظ)، و`tts_diac` (المنطوق مشكولاً كاملاً مع الوقف بالسكون، للصوت البديل). أرقام المشاهد فيها فجوات عمداً (`s11`، `s14`، `s20`، وفي الجوال `m04`): مشاهد حُذفت بعد توليد الصوت، وأسماء ملفاته مبنية على الرقم. |
| `narration/scenes-mobile.json` | مصدر نص الجوال (14 مشهداً، معرّفاتها `m01`…`m15`) بالصيغ الثلاث نفسها. |
| `narration/scenes-en.json` و`scenes-mobile-en.json` | النسخة الإنجليزية من النصين (المعرّفات والأقسام نفسها؛ `display` و`tts` فقط)، ومنها التعليق الإنجليزي: انظر «النسخة الإنجليزية» أدناه. |
| `narration/narration_tts_diac.md` و`narration_mobile_tts_diac.md` | النص كاملاً مشهداً مشهداً بصيغه الثلاث، والعبارات كما أُرسلت إلى الصوت، ومدة كل مشهد (يولّدهما `make_script_md.py`). |
| `narration/narration.m4a` و`narration-mobile.m4a` | التعليق الصوتي النهائي (AAC، مضغوط) مطابق لتوقيت كل فيديو. |
| `voice/` | `run.py` (يشغّله سير العمل)، و`request-first-run.json` (**الطلب الأول**: 222 عبارة للفيديوهين، منه وُلِّد معظم الصوت المرفق)، و`request.json` (الطلب الثاني: سبع عبارات صُحّحت بعده في خطوتَي ماك وتصحيح الشبكة)، و`samples-en/` (عيّنات خمسة أصوات إنجليزية: Jon وChristopher وPeter وAllison وAmelia، مع أوصافها ومعرّفاتها). |
| `tts/scripts/` | `eleven_narration.py` (الطلب والتركيب: **الافتراضي**)، و`check_text.py` (فحص النص)، و`narr.py` (تقسيم العبارات والوقفات)، و`make_script_md.py`، وللصوت البديل `synth_narration.py` و`edge_synth.py` و`arnorm.py` و`tricky_words.py` و`ab_variants.py`. |
| `tts/eval/` | نتائج مقارنة الأصوات والاختبارات (JSON). |
| `remotion/` | مشروع Remotion 4: `src/` (المشاهد والمكونات؛ التركيبتان `Main` للتلفاز و`Mobile` للجوال)، `public/` (الخطوط والشعار)، `tools/`، وسكربتات `sync-assets.sh` و`sync-screens.sh` و`render-preview.sh` و`render-final.sh` و`compress.sh` و`make-app720.sh` (نسخة التطبيق) و`make-preview.sh` (معاينة README). |
| `timeline.json` و`timeline-mobile.json` | توقيت المشاهد والجمل (يكتبهما `eleven_narration.py assemble`). |

ما ليس هنا لأنه يُولَّد من جديد أو ثقيل: `node_modules`، والبيئات الافتراضية، وملفات WAV (`audio/` و`audio-mobile/`)، ولقطات الشاشة
(تُنسخ من `app/src/test/screenshots` و`remote/src/test/screenshots` بـ `./sync-screens.sh`)، ومجلد `out/`.

## إعادة الإنتاج خطوة بخطوة

المتطلبات: Node.js 20 أو أحدث، وPython 3.11، وffmpeg (ضعه في `promo/bin/ffmpeg` و`promo/bin/ffprobe`، أو عدّل
المسار في السكربتات)، ومتصفح Chromium: إن وُجد المسار المكتوب في `remotion/remotion.config.ts` استُعمل، وإلا يحمّل Remotion متصفحه.

```sh
cd promo
python3 -m venv .venv && .venv/bin/pip install -r tts/requirements.txt

# 1) النص: عدّل narration/scenes.json (والجوال) ثم تحقق منه (التشكيل، الوقف، تطابق الصيغ الثلاث)
.venv/bin/python tts/scripts/check_text.py
.venv/bin/python tts/scripts/check_text.py narration/scenes-mobile.json

# 2) الصوت (ElevenLabs، الافتراضي): اكتب الطلب، وادفعه ليشغّل سير العمل، ثم ركّب الناتج
.venv/bin/python tts/scripts/eleven_narration.py request          # يكتب voice/request.json (التلفاز والجوال معاً + عيّنات إنجليزية)
git add voice/request.json && git commit -m "Voice request" && git push   # يشغّل .github/workflows/voice.yml (أو Run workflow يدوياً)
#    حمّل مخرجات التشغيل «voice-output» من صفحة التشغيل في GitHub Actions (تبقى ثلاثة أيام) وفُكّها، ثم:
.venv/bin/python tts/scripts/eleven_narration.py assemble /path/to/voice-output
#    عدّلت النص بعد التوليد، أو نقصت عبارات (errors.txt)؟ اكتب طلباً لما نقص وحده، وادفعه كما سبق، ثم ركّب الجولتين معاً:
#    .venv/bin/python tts/scripts/eleven_narration.py retry /path/to/voice-output          # يكتب voice/request.json
#    .venv/bin/python tts/scripts/eleven_narration.py assemble /path/to/voice-output /path/to/voice-output-2
#    (العبارة تُبحث عنها بنصّها في المجلدين، فلا تتغير بقية الصوت؛ ولا تحتاج جولة جديدة لتعديل لا يمسّ المنطوق)
#    بلا إنترنت، لإعادة الرسم فقط بالصوت المرفق (وتخطَّ npm run words: توقيت الكلمات محفوظ في remotion/src/data/words*.json):
#    mkdir -p audio audio-mobile
#    bin/ffmpeg -i narration/narration.m4a -ar 48000 -ac 1 audio/narration_full.wav
#    bin/ffmpeg -i narration/narration-mobile.m4a -ar 48000 -ac 1 audio-mobile/narration_full.wav

# 3) الرسم
cd remotion && npm ci
./sync-assets.sh && npm run words && npm run words:mobile && npm run music && npm run music:mobile && ./sync-screens.sh ../..
node tools/stills.mjs out/stills s06_serious@300                 # لقطة من مشهد (إطار نسبي)
node tools/stills.mjs out/stills --comp=Mobile m08_pip@120       # لقطة من فيديو الجوال
./render-preview.sh 5400 5699                                    # معاينة 10 ثوانٍ بالصوت (COMP=Mobile للجوال)
export CONCURRENCY=2                                             # إطاران بالتوازي (الافتراضي 1 لأن الجهاز مشترك)
GRAIN=0 ./render-final.sh out/shasha_explainer.mp4               # فيديو التلفاز كاملاً
COMP=Mobile GRAIN=0 ./render-final.sh out/shasha_mobile.mp4      # فيديو الجوال كاملاً
./compress.sh out/shasha_explainer.mp4 ../../docs/video/shasha-explainer.mp4        # أقل من 29 ميغابايت
./compress.sh out/shasha_mobile.mp4 ../../docs/video/shasha-mobile.mp4 12           # الهدف بالميغابايت (فيديو الجوال أخف: نحو 12)
./make-app720.sh out/shasha_explainer.mp4 ../../app/src/main/assets/video/explainer-ar.mp4   # نسخة التطبيق 720p، نحو 11.9 ميغابايت (ميزانية التطبيق 12)
./make-preview.sh ../../docs/video/shasha-explainer.mp4 ../../docs/video/preview.webp 33 52 86 124 160   # خمس بدايات بالثواني (ثلاث ثوانٍ لكل مقطع)
../.venv/bin/python tools/install_stills.py ../../docs/images/install          # صور دليل التثبيت
node tools/stills.mjs out --comp=Poster poster:0                          # صورة الغلاف (docs/video/poster.jpg)
../.venv/bin/python tools/check_beats.py                                     # كل عبارة تبحث عنها المشاهد موجودة في النص
```

ملاحظات:
- `eleven_narration.py assemble` يفكّ كل عبارة، ويقصّ صمت المحرك، ويسوّي مستوى العبارات (متوسطها)، ويُدرج الوقفات صمتاً
  حقيقياً، ثم يضبط كل مشهد على **‎-16 LUFS** (قمة حقيقية ‎-1.5 dBTP على الأكثر)، ويكتب `timeline*.json` وتوقيت الكلمات
  (من محاذاة الحروف التي يعيدها ElevenLabs) و`audio*/narration_full.wav` و`narration*.m4a`. يبحث عن العبارة **بنصّها**
  لا برقم الملف، فلا يضرّه حذف عبارات من النص بعد التوليد: كذلك `voice/request-first-run.json` فيه عبارات حُذفت لاحقاً من النص.
- `sync-assets.sh` ينسخ الخطّين الزمنيين والصوتين إلى المشروع؛ و`npm run words` يكتب `src/data/words.json` من توقيت
  الكلمات الذي يعطيه الصوت نفسه (و`words:mobile` يكتب `words-mobile.json`)، ويرجع إلى مواءمة ASR إن لم يجده (`--asr`).
- كل توقيت في المشاهد مأخوذ من الكلمات المنطوقة (`beatsFor(scene).at('...')`)، فتعديل النص لا يحتاج تعديل إطارات؛
  وإن حُذفت عبارة يبحث عنها مشهد، فـ`tools/check_beats.py` يسمّيها.
- النسخ في المستودع مرسومة بلا حبيبات (`GRAIN=0`) لأن الضغط إلى الحجم المطلوب يزيلها على أي حال؛ وللنسخة
  الكاملة بالحبيبات: `./render-final.sh` ثم `DENOISE=1 ./compress.sh ...`.
- نسخة التطبيق (`app/src/main/assets/video/explainer-ar.mp4`) هي فيديو التلفاز بدقة 720p مضغوطاً (نحو 11.9 ميغابايت)، ومعاينة
  الصفحة الرئيسية `docs/video/preview.webp` خمسة مقاطع من ثلاث ثوانٍ (12 إطاراً في الثانية، عرض 720، libwebp_anim جودة 55).
- أسماء ملفات المجموعة وأحجامها وإصدارها (المثبّتات والدليل وملف التطبيق `shasha-1.2.0-1065.apk`) في مكان واحد:
  `remotion/src/lib/installer.ts`. هي ما في حقيبة الإصدار 1.2.0؛ فبعد كل إصدار جديد غيّرها وأعد رسم مشاهد التثبيت (من `s18_need` إلى `s29_first_start`)
  ثم الفيديو كاملاً.
- المصطلحات اللاتينية تُكتب في `tts` كما تُلفظ («جوجل تي في»، «كيو آر»، «يو إس بي»، «آي بي»)، والأعداد بالحروف، والأقواس
  وعلامات التنصيص تُحذف قبل الإرسال (للعين فقط).

## النسخة الإنجليزية

الفيديوهان بالإنجليزية من المشاهد نفسها، وتختار اللغةَ خاصيةُ الإدخال `lang` (انظر `remotion/src/lib/lang.ts`). العربية تبقى
الافتراضية، فلا يتغير شيء في رسم الفيديوهين العربيين.

- **الصوت:** ElevenLabs، الصوت «Christopher - Gentle and Trustworthy» (`voice_id` = `G17SuINrv2H9FC6nvetn`، العيّنة
  `voice/samples-en/en-male-02`)، عبر سير العمل نفسه. النصان `narration/scenes-en.json` و`scenes-mobile-en.json`، والصوت
  المركّب `narration/narration-en.m4a` و`narration-mobile-en.m4a`، والخطان الزمنيان `timeline-en.json` و`timeline-mobile-en.json`.
- **النصوص المرسومة:** كل نص عربي يُرسم يمرّ عبر `t()`، ومقابله الإنجليزي في `remotion/src/i18n/en-*.json`؛ والعبارات المنطوقة
  التي تتوقّت بها المشاهد مقابلها في `remotion/src/i18n/anchors-en-*.json`. السكربت `node tools/check_i18n.mjs` يسمّي كل نص أو
  عبارة بلا مقابل.
- **لقطات التطبيق:** تُصوَّر من اختبارات لقطات الشاشة بواجهة التطبيق الإنجليزية إلى `promo/screens-en/`، وتكتب معها حدود كل عنصر
  في `app/build/promo-english/<اللقطة>.json`. منها قيست مستطيلات `remotion/src/screens-en.json`: هوامش كل مستطيل عربي حول
  عنصره، معكوسةً حول العنصر نفسه في اللقطة الإنجليزية، وما يحيط بمجموعة أو بنص في الوسط قيس على اللقطة. اللقطة التي ليس لها
  مقابل إنجليزي تُرسم بلقطتها العربية، كصفحة التحكم من الجوال، وهي عربية في التطبيق أيضاً.

```sh
# في جذر المستودع: لقطات التطبيق الإنجليزية
./gradlew :app:recordRoborazziDebug -PshashaPromoEnglish --tests '*ScreenshotTest'
# في remotion/
./sync-assets.sh && npm run words -- --en && npm run words:mobile -- --en
npm run music -- --en && npm run music:mobile -- --en && ./sync-screens.sh ../..
node tools/check_i18n.mjs
node tools/stills.mjs out/stills --lang=en s06_serious@300               # لقطة من مشهد
node tools/stills.mjs out/dbg --comp=ScreensDebug --lang=en home_adult:0  # مستطيلات لقطة إنجليزية
VIDEO_LANG=en GRAIN=0 ./render-final.sh out/shasha_explainer_en.mp4
VIDEO_LANG=en COMP=Mobile GRAIN=0 ./render-final.sh out/shasha_mobile_en.mp4
./compress.sh out/shasha_explainer_en.mp4 ../../docs/video/shasha-explainer-en.mp4 21.5   # معدل الفيديو العربي نفسه
./compress.sh out/shasha_mobile_en.mp4 ../../docs/video/shasha-mobile-en.mp4 9.8
./make-app720.sh out/shasha_explainer_en.mp4 ../../app/src/main/assets/video/explainer-en.mp4 10.8   # نسخة التطبيق باللغة الإنجليزية
```

## الصوت البديل: edge-tts (التلفاز فقط)

إن لم يتوفر مفتاح ElevenLabs، يعمل خط edge-tts القديم على `narration/scenes.json` وحده (بصيغة `tts_diac`):

```sh
.venv/bin/python tts/scripts/synth_narration.py            # يحتاج إنترنت (خدمة Edge)
#    خلف وسيط TLS:  EDGE_TTS_CA_FILE=/path/to/ca.pem .venv/bin/python tts/scripts/synth_narration.py
```

يكتب الملفات نفسها (`timeline.json`، `audio/…`) ويفحص النطق بـ faster-whisper (معدل خطأ الحروف لكل مشهد). والصوت: `ar-SA-HamedNeural`
بسرعة ‎-6%.

قواعد النطق في هذا الخط (في `tts/scripts/narr.py`):

- تشكيل كامل، وحركات الإعراب داخل العبارة، و**الوقف بالسكون** على آخر كلمة قبل كل وقفة.
- التاء المربوطة الموقوف عليها تُرسل للمحرك هاءً ساكنة (`هْ`): الصوت ينطق `ةْ` تاءً مفتوحة (قيس: مطابق لـ`تْ`).
- الشدّة تُكتب قبل الحركة: بالترتيب المعاكس يمدّ الصوت الحركة بدل تضعيف الحرف (قيس على «مِلَفٌّ»).
- همزة الوصل ألف مجردة.
- الوقفات مُدرجة صمتاً حقيقياً (كما في ElevenLabs): ‎0.25 ث بعد الفاصلة، و‎0.3 ث بعد النقطتين، و‎0.5 ث بعد الجملة، ونحو ‎0.9 ث بين المشاهد.

## الصوت والرخص

- **الصوت:** ElevenLabs (الصوت العام «Abdullah - Professional, and Energetic» من مكتبة الأصوات)، يُولَّد باشتراك صاحب المشروع الشخصي في ElevenLabs
  ويُستعمل ضمن ما تتيحه خطته. الصوت البديل: خدمة «القراءة بصوت عالٍ» في Microsoft Edge (`ar-SA-HamedNeural`) عبر مكتبة
  [edge-tts](https://github.com/rany2/edge-tts) (رخصة LGPL-3.0)، للاستخدام الشخصي غير التجاري؛ والمشروع غير تابع لـ Microsoft.
- **فحص النطق (للصوت البديل):** [faster-whisper](https://github.com/SYSTRAN/faster-whisper) (MIT) بنماذج Whisper من OpenAI (MIT).
- **الخطوط:** IBM Plex Sans Arabic وIBM Plex Mono بموجب **SIL Open Font License 1.1** (النص في
  `remotion/public/fonts/OFL-IBMPlex.txt`)، وDejaVu Sans Mono بموجب رخصة Bitstream Vera (`LICENSE-DejaVu.txt`).
- **Remotion:** مجاني للأفراد والفرق الصغيرة بحسب [رخصته](https://www.remotion.dev/license)؛ هذا مشروع شخصي.
- **الموسيقى:** مولّدة بالكامل بالسكربت `remotion/tools/make_music.py` (لا عينات ولا موسيقى منزّلة).
- **الصور:** لقطات التطبيق من اختبارات لقطات الشاشة في المستودع؛ واجهات التلفاز والحاسوب والجوال مرسومة من جديد بلا شعارات
  (لا شعار YouTube أو Google أو TCL أو GitHub أو Microsoft أو Apple).

</div>
