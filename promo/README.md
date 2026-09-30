<div dir="rtl">

# مصدر الفيديو التعريفي لـ«شاشة»

هذا المجلد يحوي كل ما يلزم لإعادة إنتاج الفيديو `docs/video/shasha-explainer.mp4` من المستودع نفسه:
نص التعليق الصوتي، وسكربتات توليد الصوت والتحقق منه، ومشروع Remotion الذي يرسم المشاهد.

- المدة: **6:12** (11149 إطاراً، 30 إطاراً في الثانية، 1920×1080): الميزات أولاً، ثم التثبيت في تسع خطوات.
- الصوت: **Microsoft Edge — ar-SA-HamedNeural (حامد، عربي سعودي، ذكر)** بسرعة ‎-6%، عبر مكتبة `edge-tts`.
- فحص النطق الآلي (faster-whisper medium): معدل خطأ الحروف الكلي **2.5%** (التفاصيل لكل مشهد في `narration/narration_tts_diac.md`).
- الصور التوضيحية لدليل التثبيت (`docs/images/install/*.jpg`) تُستخرج من مشاهد التثبيت نفسها.

## المحتويات

| المسار | ما فيه |
|---|---|
| `narration/scenes.json` | **مصدر النص**: لكل مشهد ثلاث صيغ متوازية: `display` (ما يُكتب في الترجمة)، و`tts` (المنطوق بلا تشكيل، والمصطلحات مكتوبة كما تُلفظ)، و`tts_diac` (المنطوق مشكولاً كاملاً مع الوقف بالسكون). |
| `narration/narration_tts_diac.md` | النص كاملاً مشهداً مشهداً بصيغه الثلاث، والعبارات كما أُرسلت إلى الصوت، ومدة كل مشهد ونتيجة فحص النطق (يولّده `make_script_md.py`). |
| `narration/narration.m4a` | التعليق الصوتي النهائي (AAC، مضغوط)، مطابق لتوقيت الفيديو. |
| `tts/scripts/` | التوليد والتحقق: `synth_narration.py`، `edge_synth.py`، `narr.py`، `arnorm.py`، `check_text.py`، `tricky_words.py`، `ab_variants.py`، `make_script_md.py`. |
| `tts/eval/` | نتائج مقارنة الأصوات والاختبارات (JSON). |
| `remotion/` | مشروع Remotion 4: `src/` (المشاهد والمكونات)، `public/` (الخطوط والشعار)، `tools/`، وسكربتات `sync-assets.sh` و`sync-screens.sh` و`render-preview.sh` و`render-final.sh` و`compress.sh`. |
| `timeline.json` | توقيت المشاهد والجمل (يولّده `synth_narration.py`). |

ما ليس هنا لأنه يُولَّد من جديد أو ثقيل: `node_modules`، والبيئات الافتراضية، ونماذج Whisper، وملفات WAV، ولقطات الشاشة
(تُنسخ من `app/src/test/screenshots` و`remote/src/test/screenshots` بـ `./sync-screens.sh`)، ومجلد `out/`.

## إعادة الإنتاج خطوة بخطوة

المتطلبات: Node.js 20 أو أحدث، وPython 3.11، وffmpeg (ضعه في `promo/bin/ffmpeg` و`promo/bin/ffprobe`، أو عدّل
المسار في السكربتات)، ومتصفح Chromium: إن وُجد المسار المكتوب في `remotion/remotion.config.ts` استُعمل، وإلا يحمّل Remotion متصفحه.

```sh
cd promo
python3 -m venv .venv && .venv/bin/pip install -r tts/requirements.txt

# 1) النص: عدّل narration/scenes.json ثم تحقق منه (التشكيل، الوقف، تطابق الصيغ الثلاث)
.venv/bin/python tts/scripts/check_text.py

# 2) الصوت: توليد العبارات واحدة واحدة، والوقفات، والضبط على ‎-16 LUFS، والتحقق بـ ASR
.venv/bin/python tts/scripts/synth_narration.py            # يحتاج إنترنت (خدمة Edge)
#    خلف وسيط TLS:  EDGE_TTS_CA_FILE=/path/to/ca.pem .venv/bin/python tts/scripts/synth_narration.py
#    بلا إنترنت، لإعادة الرسم فقط بالصوت المرفق:
#    mkdir -p audio && bin/ffmpeg -i narration/narration.m4a -ar 48000 -ac 1 audio/narration_full.wav

# 3) الرسم
cd remotion && npm ci
./sync-assets.sh && npm run words && npm run music && ./sync-screens.sh ../..
node tools/stills.mjs out/stills s14_devmode@300          # لقطة من مشهد (إطار نسبي)
./render-preview.sh 5400 5699                              # معاينة 10 ثوانٍ بالصوت
GRAIN=0 ./render-final.sh out/shasha_explainer.mp4         # الفيديو كاملاً (concurrency 1، نحو 30 دقيقة)
./compress.sh out/shasha_explainer.mp4 ../../docs/video/shasha-explainer.mp4   # أقل من 29 ميغابايت
../.venv/bin/python tools/install_stills.py ../../docs/images/install          # صور دليل التثبيت
node tools/stills.mjs out --comp=Poster poster:0                          # صورة الغلاف (docs/video/poster.jpg)
../.venv/bin/python tools/check_beats.py                                     # كل عبارة تبحث عنها المشاهد موجودة في النص
```

ملاحظات:
- `sync-assets.sh` ينسخ `timeline.json` والصوت إلى المشروع؛ و`npm run words` يكتب `src/data/words.json` من توقيت
  الكلمات الذي يعطيه الصوت نفسه (أحداث WordBoundary)، ويرجع إلى مواءمة ASR إن لم يجده.
- كل توقيت في المشاهد مأخوذ من الكلمات المنطوقة (`beatsFor(scene).at('...')`)، فتعديل النص لا يحتاج تعديل إطارات؛
  وإن حُذفت عبارة يبحث عنها مشهد، فـ`tools/check_beats.py` يسمّيها.
- النسخة في المستودع مرسومة بلا حبيبات (`GRAIN=0`) لأن الضغط إلى أقل من 29 ميغابايت يزيلها على أي حال؛ وللنسخة
  الكاملة بالحبيبات: `./render-final.sh` ثم `DENOISE=1 ./compress.sh ...`.
- أسماء ملفات المجموعة (المثبّتات والدليل) في مكان واحد: `remotion/src/lib/installer.ts`.

## قواعد النطق المتّبعة (في `tts/scripts/narr.py`)

- تشكيل كامل، وحركات الإعراب داخل العبارة، و**الوقف بالسكون** على آخر كلمة قبل كل وقفة.
- التاء المربوطة الموقوف عليها تُرسل للمحرك هاءً ساكنة (`هْ`): الصوت ينطق `ةْ` تاءً مفتوحة (قيس: مطابق لـ`تْ`).
- الشدّة تُكتب قبل الحركة: بالترتيب المعاكس يمدّ الصوت الحركة بدل تضعيف الحرف (قيس على «مِلَفٌّ»).
- همزة الوصل ألف مجردة، والأعداد بالحروف، والأسماء الأجنبية بحروف عربية كما تُلفظ («جوجل تي في»، «كيو آر»،
  «يو إس بي»، «آي بي»)، إلا اسم السطر الإنجليزي «Android TV OS build» فيُرسل بالإنجليزية ليُقرأ كما يظهر على التلفاز.
- الوقفات مُدرجة صمتاً حقيقياً: ‎0.25 ث بعد الفاصلة، و‎0.3 ث بعد النقطتين، و‎0.5 ث بعد الجملة، ونحو ‎0.9 ث بين المشاهد.

## الصوت والرخص

- **الصوت:** خدمة «القراءة بصوت عالٍ» في Microsoft Edge، الصوت العصبي `ar-SA-HamedNeural`، عبر مكتبة
  [edge-tts](https://github.com/rany2/edge-tts) (رخصة LGPL-3.0). **للاستخدام الشخصي غير التجاري فقط**؛ المشروع غير تابع
  لـ Microsoft، وشروط الخدمة تسري على الصوت المولَّد.
- **فحص النطق:** [faster-whisper](https://github.com/SYSTRAN/faster-whisper) (MIT) بنماذج Whisper من OpenAI (MIT).
- **الخطوط:** IBM Plex Sans Arabic وIBM Plex Mono بموجب **SIL Open Font License 1.1** (النص في
  `remotion/public/fonts/OFL-IBMPlex.txt`)، وDejaVu Sans Mono بموجب رخصة Bitstream Vera (`LICENSE-DejaVu.txt`).
- **Remotion:** مجاني للأفراد والفرق الصغيرة بحسب [رخصته](https://www.remotion.dev/license)؛ هذا مشروع شخصي.
- **الموسيقى:** مولّدة بالكامل بالسكربت `remotion/tools/make_music.py` (لا عينات ولا موسيقى منزّلة).
- **الصور:** لقطات التطبيق من اختبارات لقطات الشاشة في المستودع؛ واجهات التلفاز والحاسوب مرسومة من جديد بلا شعارات
  (لا شعار YouTube أو Google أو TCL أو GitHub أو Microsoft أو Apple).

</div>
