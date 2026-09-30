"""Render the narration as a readable Arabic document (for review and for the promo folder):
narration/narration_tts_diac.md (and a copy at script_ar.md), from narration/scenes.json + timeline.json +
audio/asr_report.json when present."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from narr import engine_text, split_phrases  # noqa: E402

V = Path(__file__).resolve().parents[2]
scenes = json.loads((V / 'narration/scenes.json').read_text())
tl = json.loads((V / 'timeline.json').read_text()) if (V / 'timeline.json').exists() else None
asr = json.loads((V / 'audio/asr_report.json').read_text()) if (V / 'audio/asr_report.json').exists() else None
by_id = {s['id']: s for s in tl['scenes']} if tl else {}
cer_by = {s['id']: s.get('cer') for s in asr['scenes']} if asr else {}


def mmss(x):
    return f'{int(x // 60)}:{x % 60:04.1f}'


SECTION_AR = {'intro': 'المقدمة', 'why': 'لماذا', 'features': 'جولة في الميزات', 'install': 'التثبيت خطوة بخطوة',
              'breaks': 'عندما يتعطل يوتيوب', 'disclaimer': 'بصراحة', 'outro': 'الخاتمة'}

L = ['<div dir="rtl">', '', '# «شاشة» — نص التعليق الصوتي', '']
if tl:
    L += [f'- المدة الكلية: **{mmss(tl["total_duration_s"])}** ({tl["total_frames"]} إطاراً، {tl["fps"]} إطاراً في الثانية).',
          f'- الصوت: `{tl["tts"]["voice"]}` بسرعة `{tl["tts"]["rate"]}` — {tl["tts"]["engine"]}.']
if asr and asr.get('asr'):
    L += [f'- فحص النطق الآلي ({asr["asr"]["model"]}): معدل خطأ الحروف الكلي **{asr["asr"]["overall_cer_char_weighted"] * 100:.2f}%**.']
L += ['', 'لكل مشهد ثلاث صيغ متوازية في `narration/scenes.json`:', '',
      '1. **على الشاشة** (`display`): ما تكتبه الترجمة؛ المصطلحات اللاتينية كما هي (GitHub، QR، USB، OK…).',
      '2. **للنطق** (`tts`): المنطوق بلا تشكيل؛ الأسماء الأجنبية كما تُلفظ (جوجل تي في، كيو آر، يو إس بي…) والأعداد بالحروف.',
      '3. **مشكول** (`tts_diac`): التشكيل الكامل، الإعراب داخل العبارة، والوقف بالسكون على آخر كلمة قبل كل وقفة.',
      '', 'ما يصل إلى المحرك فعلاً (`narr.engine_text`): تُحذف علامات التنصيص، وتُكتب الشدة قبل الحركة، وتُرسل التاء المربوطة',
      'الموقوف عليها هاءً ساكنة (`هْ`) لأن الصوت ينطق `ةْ` تاءً. العبارات تُولَّد واحدة واحدة وتُدرج بينها وقفات حقيقية:',
      '0.25 ث بعد «،»، و0.3 ث بعد «:»، و0.5 ث بعد الجملة.', '',
      '| # | المشهد | البداية | المدة | الكلمات | CER |', '|---|---|---|---|---|---|']
for n, sc in enumerate(scenes, 1):
    t = by_id.get(sc['id'])
    c = cer_by.get(sc['id'])
    start = mmss(t['start_s']) if t else '—'
    dur = f"{t['duration_s']:.1f} ث" if t else '—'
    cer_s = f'{c * 100:.1f}%' if c is not None else '—'
    L.append(f"| {n} | {sc['title']} (`{sc['id']}`) | {start} | {dur} | {len(sc['tts'].split())} | {cer_s} |")
L.append('')
prev = None
for n, sc in enumerate(scenes, 1):
    if sc['section'] != prev:
        L += [f'## {SECTION_AR[sc["section"]]}', '']
        prev = sc['section']
    L += [f'### {n}. {sc["title"]}  <small>`{sc["id"]}`</small>', '',
          f'**على الشاشة:** {sc["display"]}', '', f'**للنطق:** {sc["tts"]}', '', f'**مشكول:** {sc["tts_diac"]}', '',
          '**العبارات كما أُرسلت إلى الصوت:**', '']
    L += [f'{k}. `{engine_text(p["text"])}`' for k, p in enumerate(split_phrases(sc['tts_diac']), 1)]
    L.append('')
L += ['</div>', '']
text = '\n'.join(L)
(V / 'narration/narration_tts_diac.md').write_text(text)
(V / 'script_ar.md').write_text(text)
print('wrote', V / 'narration/narration_tts_diac.md')
