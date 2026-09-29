# نتائج بناء الصفحة الرئيسية من 20 قناة حقيقية

- **التاريخ:** 2026-09-29، نحو الساعة 00:10 بتوقيت UTC.
- **الاختبار:** `FeedSyncRealNetworkTest` في وحدة `:app`. يستورد القنوات العشرين كملف
  `subscriptions.csv` بصيغة Google Takeout، ثم يشغّل المزامنة نفسها التي يشغّلها التطبيق كل ساعتين
  (`FeedRepository.syncAll`)، ويعدّ كم قناة تُجلب في اللحظة نفسها.
- **الأمر:**
  `ANDROID_HOME=/opt/android-sdk ./gradlew :app:testDebugUnitTest --tests '*FeedSyncRealNetworkTest' -Dyoutube.realNetwork=true`
- **مكان التشغيل:** خادم في مركز بيانات، وليس شاشة TCL ولا شبكة المنزل.

## الحصيلة

| البند | النتيجة |
|---|---|
| القنوات | 20 من 20 نجحت، ولم تفشل أي قناة |
| مصدر الفيديوهات | RSS لكل القنوات (لا مدد في النتائج، والبديل عبر تبويب الفيديوهات لم يُحتج إليه) |
| أقصى عدد قنوات تُجلب معاً | 3 (الحد المطلوب 3) |
| مدة المزامنة كاملة | 2363 ms في التشغيل الأول، و1125 ms في التشغيل الثاني |
| عناصر الصفحة الرئيسية | 290 فيديو مرتبة من الأحدث إلى الأقدم، منها 132 شورتس (تُخفى لاحقاً حسب الملف) |
| أحدث فيديو وأقدمه | 2026-09-29 00:04 UTC، و2026-07-03 16:00 UTC (قبل نحو 87 يوماً) |

## القنوات

كل قناة أعطت 15 فيديو من RSS، وهو كل ما يعطيه RSS. العمود الأخير هو ما بقي بعد قاعدة "آخر 90 يوماً".

| القناة | المعرّف | من RSS | المحفوظ |
|---|---|---|---|
| الجزيرة | `UCfiwzLy-8yKzIbsmZTzxDgw` | 15 | 15 |
| العربية | `UCahpxixMCwoANAftn6IxkTg` | 15 | 15 |
| سكاي نيوز عربية | `UCIJXOvggjKtCagMfxvcCzAA` | 15 | 15 |
| BBC News عربي | `UCelk6aHijZq-GJBBB9YpReA` | 15 | 15 |
| DW عربية | `UC30ditU5JI16o5NbFsHde_Q` | 15 | 15 |
| AJ+ عربي | `UCnVGseMUtv2yFNC-dQ2JdSw` | 15 | 15 |
| طيور الجنة | `UCozAuGJZ5MPoijz8IE_1Gtw` | 15 | 15 |
| سبيستون | `UCuQKih3Ac3NABADQKQdeV6A` | 15 | 15 |
| روتانا | `UCNhqvQMXIgRfjAGmxQqdNRw` | 15 | 15 |
| قناة المملكة | `UC0jiFAzTgl17k7awGbuoYew` | 15 | 15 |
| Rick Astley | `UCuAXFkgsw1L7xaCfnd5JJOw` | 15 | 6 |
| MrBeast | `UCX6OQ3DkcsbYNE6H8uQQuVA` | 15 | 14 |
| Kurzgesagt | `UCsXVk37bltHxD1rDPwtNM8Q` | 15 | 15 |
| Veritasium | `UCHnyfMqiRRG1u-2MsSQLbXA` | 15 | 15 |
| TED | `UCAuUUnT6oDeKwE6v1NGQxug` | 15 | 15 |
| NASA | `UCLA_DiR1FfKNvjuUpBHmylQ` | 15 | 15 |
| Marques Brownlee | `UCBJycsmduvYEL83R_U4JriQ` | 15 | 15 |
| Linus Tech Tips | `UCXuqSBlHAE6Xw-yeJA0Tunw` | 15 | 15 |
| National Geographic | `UCpVm7bg6pXKo1Pr6k5kxG9A` | 15 | 15 |
| BBC News | `UC16niRr50-MSBwiO3YDb3RA` | 15 | 15 |

قناة Rick Astley لم تنشر إلا 6 فيديوهات في آخر 90 يوماً، فحُذف الباقي كما يجب.

## ما لا يثبته هذا الاختبار

معيار القبول في المواصفات هو "RSS يبني الصفحة الرئيسية من 20 قناة **بلا تقطيع أثناء تشغيل فيديو 1080p**".
الجزء الأول ثابت هنا. أما التقطيع فلا يُقاس إلا على شاشة TCL نفسها. التصميم الذي يمنعه:

- المزامنة **لا تبدأ** إذا كان فيديو يعمل، وإذا بدأ فيديو أثناءها **لا تبدأ أي قناة جديدة**، وتكتمل
  القنوات الجارية فقط (3 على الأكثر، وكل واحدة طلب RSS صغير). ثم تُجدوَل مزامنة تكميلية بعد 15 دقيقة
  تتخطى ما اكتمل.
- الجلب على خيوط بأولوية خلفية (`THREAD_PRIORITY_BACKGROUND`)، فالمشغّل والواجهة يأخذان المعالج أولاً.
- هذه السلوكيات مغطاة باختبارات آلية بلا شبكة (`FeedSyncWorkerTest` و`FeedRepositoryTest`).

المشغّل مربوط الآن بهذه الإشارة: `player/PlayingSignal` يضبط `PlaybackActivity.setPlaying` من كل مشغّل،
فتبقى مرفوعة ما دام المشغّل يريد التشغيل (والتحميل المؤقت منه)، والمزامنة الدورية وزر «تحديث» ورئيسية ملف
الطفل (`KidsFeed`) كلها تتوقف عندها. يبقى على الشاشة: تشغيل فيديو 1080p فور بدء تحديث يدوي ومراقبة التقطيع،
كما في التجربة 2 في [acceptance.md](acceptance.md).
