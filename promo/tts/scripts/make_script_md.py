"""Render a narration script as a readable Arabic document (for review and for the promo folder):
narration/narration_tts_diac.md (the TV video) or narration/narration_mobile_tts_diac.md (the phone video), from
narration/scenes*.json + timeline*.json (+ audio/asr_report.json when the edge-tts pipeline made one).

  python tts/scripts/make_script_md.py            the TV video
  python tts/scripts/make_script_md.py mobile     the phone video
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from narr import engine_text, split_phrases  # noqa: E402
from eleven_narration import eleven_text  # noqa: E402

V = Path(__file__).resolve().parents[2]
KEY = sys.argv[1] if len(sys.argv) > 1 else 'tv'
SCRIPTS = {
    'tv': dict(scenes='narration/scenes.json', timeline='timeline.json', audio='audio',
               out='narration/narration_tts_diac.md', video='التلفاز'),
    'mobile': dict(scenes='narration/scenes-mobile.json', timeline='timeline-mobile.json', audio='audio-mobile',
                   out='narration/narration_mobile_tts_diac.md', video='الجوال'),
}
if KEY not in SCRIPTS:
    sys.exit('usage: make_script_md.py [tv|mobile]')
cfg = SCRIPTS[KEY]
scenes = json.loads((V / cfg['scenes']).read_text(encoding='utf-8'))
tl = json.loads((V / cfg['timeline']).read_text(encoding='utf-8')) if (V / cfg['timeline']).exists() else None
asr_file = V / cfg['audio'] / 'asr_report.json'
asr = json.loads(asr_file.read_text()) if asr_file.exists() else None
by_id = {s['id']: s for s in tl['scenes']} if tl else {}
cer_by = {s['id']: s.get('cer') for s in asr['scenes']} if asr else {}
eleven = bool(tl and tl.get('tts', {}).get('voice_id'))


def mmss(x):
    return f'{int(x // 60)}:{x % 60:04.1f}'


SECTION_AR = {
    'intro': 'المقدمة', 'family': 'للأهل: الملفات والحدود', 'watch': 'المشاهدة', 'phone': 'الجوال ريموتاً',
    'tv': 'التحديث وأنواع التلفاز', 'install': 'التثبيت خطوة بخطوة', 'outro': 'الخاتمة',
    'player': 'المشغّل', 'library': 'مكتبتك', 'update': 'التحديث',
}

L = ['<div dir="rtl">', '', f'# «شاشة» — نص التعليق الصوتي لفيديو {cfg["video"]}', '']
if tl:
    t = tl['tts']
    if eleven:
        L += [f'- المدة الكلية: **{mmss(tl["total_duration_s"])}** ({tl["total_frames"]} إطاراً، {tl["fps"]} إطاراً في الثانية).',
              f'- الصوت: «{t["voice"]}» من ElevenLabs (`{t["voice_id"]}`، النموذج `{t["model"]}`)، عبارة عبارة مع '
              'الجملتين المجاورتين سياقاً، والوقفات صمتٌ حقيقي نُدرجه نحن.']
    else:
        L += [f'- المدة الكلية: **{mmss(tl["total_duration_s"])}** ({tl["total_frames"]} إطاراً، {tl["fps"]} إطاراً في الثانية).',
              f'- الصوت: `{t["voice"]}` بسرعة `{t.get("rate", "")}` — {t["engine"]}.']
if asr and asr.get('asr'):
    L += [f'- فحص النطق الآلي ({asr["asr"]["model"]}): معدل خطأ الحروف الكلي **{asr["asr"]["overall_cer_char_weighted"] * 100:.2f}%**.']
L += ['', f'لكل مشهد ثلاث صيغ متوازية في `{cfg["scenes"]}`:', '',
      '1. **على الشاشة** (`display`): ما تكتبه الترجمة؛ المصطلحات اللاتينية كما هي (GitHub، QR، USB، OK…).',
      '2. **للنطق** (`tts`): المنطوق بلا تشكيل؛ الأسماء الأجنبية كما تُلفظ (جوجل تي في، كيو آر، يو إس بي…) والأعداد بالحروف.',
      '3. **مشكول** (`tts_diac`): التشكيل الكامل، الإعراب داخل العبارة، والوقف بالسكون على آخر كلمة قبل كل وقفة.',
      '']
if eleven:
    L += ['ما يُرسل إلى ElevenLabs هو صيغة `tts` بلا تشكيل، عبارةً عبارة (تُحذف علامات التنصيص والأقواس وحدها)؛ والصيغة المشكولة',
          'للصوت البديل (edge-tts). الوقفات صمتٌ حقيقي: 0.25 ث بعد «،»، و0.3 ث بعد «:»، و0.5 ث بعد الجملة، و0.9 ث عند السطر الفارغ.', '']
else:
    L += ['ما يصل إلى المحرك فعلاً (`narr.engine_text`): تُحذف علامات التنصيص، وتُكتب الشدة قبل الحركة، وتُرسل التاء المربوطة',
          'الموقوف عليها هاءً ساكنة (`هْ`) لأن الصوت ينطق `ةْ` تاءً. العبارات تُولَّد واحدة واحدة وتُدرج بينها وقفات حقيقية:',
          '0.25 ث بعد «،»، و0.3 ث بعد «:»، و0.5 ث بعد الجملة.', '']
head = '| # | المشهد | البداية | المدة | الكلمات |' + (' CER |' if asr else '')
L += [head, '|---|---|---|---|---|' + ('---|' if asr else '')]
for n, sc in enumerate(scenes, 1):
    t = by_id.get(sc['id'])
    start = mmss(t['start_s']) if t else '—'
    dur = f"{t['duration_s']:.1f} ث" if t else '—'
    row = f"| {n} | {sc['title']} (`{sc['id']}`) | {start} | {dur} | {len(sc['tts'].split())} |"
    if asr:
        c = cer_by.get(sc['id'])
        row += f" {c * 100:.1f}% |" if c is not None else ' — |'
    L.append(row)
L.append('')
prev = None
for n, sc in enumerate(scenes, 1):
    if sc['section'] != prev:
        L += [f'## {SECTION_AR[sc["section"]]}', '']
        prev = sc['section']
    L += [f'### {n}. {sc["title"]}  <small>`{sc["id"]}`</small>', '',
          f'**على الشاشة:** {sc["display"]}', '', f'**للنطق:** {sc["tts"]}', '', f'**مشكول:** {sc["tts_diac"]}', '']
    if eleven:
        L += ['**العبارات كما أُرسلت إلى الصوت:**', '']
        L += [f'{k}. `{eleven_text(p["text"])}`' for k, p in enumerate(split_phrases(sc['tts']), 1)]
    else:
        L += ['**العبارات كما أُرسلت إلى الصوت:**', '']
        L += [f'{k}. `{engine_text(p["text"])}`' for k, p in enumerate(split_phrases(sc['tts_diac']), 1)]
    L.append('')
L += ['</div>', '']
out = V / cfg['out']
out.write_text('\n'.join(L), encoding='utf-8')
print('wrote', out)
