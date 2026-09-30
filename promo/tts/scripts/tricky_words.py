"""Pronunciation spot-checks for the chosen voice.

1. Tricky words, each synthesised ALONE (exactly the engine text the narration sends) and transcribed by two
   ASR models (faster-whisper medium and small, language=ar). A word "passes" when either model writes it
   (after the usual normalisation) or the only difference is an orthographic variant the ASR cannot hear.
2. Phrase-final intonation: the same phrase sent with a trailing comma (the narration's continuation phrases)
   and with a full stop; the pitch slope over the last voiced 400 ms shows whether the voice keeps the
   phrase open (comma) or closes it (stop).

  python tts/scripts/tricky_words.py [--voice ar-SA-HamedNeural --rate=-6%]  -> tts/edge_eval/tricky/report.json
"""
import argparse, json, subprocess, sys
from pathlib import Path
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from arnorm import cer, normalize  # noqa: E402
from narr import engine_text  # noqa: E402
from edge_synth import synth_sync  # noqa: E402

V = Path(__file__).resolve().parents[2]
FF = str(V / 'bin/ffmpeg')
OUT = V / 'tts/edge_eval/tricky'
OUT.mkdir(parents=True, exist_ok=True)
ap = argparse.ArgumentParser()
ap.add_argument('--voice', default='ar-SA-HamedNeural')
ap.add_argument('--rate', default='-6%')
a = ap.parse_args()

# (diacritised as in the narration, plain reference)
WORDS = [
    ('شَاشَةْ', 'شاشة'), ('يُوتْيُوبْ', 'يوتيوب'), ('خُوَارِزْمِيَّةْ', 'خوارزمية'), ('جُوجِلْ تِي فِي', 'جوجل تي في'),
    ('سْبُونْسَرْ بْلُوكْ', 'سبونسر بلوك'), ('رَمْزَ كِيُو آرْ', 'رمز كيو ار'), ('عُنْوَانَ آيْ بِي', 'عنوان اي بي'),
    ('تَحْتَ آيْ بِي أَدْرِسْ', 'تحت اي بي ادرس'), ('تَصْحِيحَ أَخْطَاءِ يُو إِسْ بِي', 'تصحيح أخطاء يو اس بي'),
    ('انْزِلْ إِلَى Android TV OS build', 'انزل الى اندرويد تي في او اس بيلد'),
    ('غِيتْ هَبْ', 'غيت هب'), ('وِينْدُوزْ', 'ويندوز'), ('مَاكْ أَوْ وِينْدُوزْ', 'ماك او ويندوز'), ('الْكُمْبْيُوتَرْ', 'الكمبيوتر'),
    ('اضْغَطْ إِنْتَرْ', 'اضغط انتر'), ('اضْغَطْ أُوكِي', 'اضغط اوكي'), ('رِيمُوتْ', 'ريموت'), ('وَمِلَفٌّ لِلْأَطْفَالْ', 'وملف للأطفال'),
    ('الِاقْتِرَانَ بِرَمْزْ', 'الاقتران برمز'), ('تَصْحِيحَ الْأَخْطَاءِ اللَّاسِلْكِيّ', 'تصحيح الأخطاء اللاسلكي'),
    ('رَمْزٌ سِرِّيّ', 'رمز سري'), ('أَنْتَ الْآنَ مُطَوِّرْ', 'أنت الآن مطور'), ('خِيَارَاتِ الْمُطَوِّرْ', 'خيارات المطور'),
    ('سَبْعَ مَرَّاتْ', 'سبع مرات'), ('لِعَشْرِ ثَوَانْ', 'لعشر ثوان'), ('بِخَمْسِ دَقَائِقْ', 'بخمس دقائق'),
    ('تِلْقَائِيَّا', 'تلقائيا'), ('قَبْلَ مُرُورِ دَقِيقَةْ', 'قبل مرور دقيقة'), ('اسْتِخْرَاجُ الْكُلّ', 'استخراج الكل'),
    ('مَزِيدٌ مِنَ الْمَعْلُومَاتْ', 'مزيد من المعلومات'), ('تَشْغِيلٌ عَلَى أَيِّ حَالْ', 'تشغيل على أي حال'),
    ('اكْتُبْ وَايْ لِلْمُوَافَقَةْ', 'اكتب واي للموافقة'), ('أَوْ إِنْ لِلرَّفْضْ', 'أو إن للرفض'),
    ('الرَّئِيسِيَّةْ', 'الرئيسية'), ('الْإِحْصَائِيَّاتْ', 'الإحصائيات'), ('حِصَصٌ لِلْوَقْتْ', 'حصص للوقت'),
    ('رَمْزِ الْوَالِدَيْنْ', 'رمز الوالدين'), ('يُثَبِّتُ التَّطْبِيقْ', 'يثبت التطبيق'), ('وَيَمْنَحُهُ صَلَاحِيَّاتِهْ', 'ويمنحه صلاحياته'),
    ('قِسْمِ الْأَبْ', 'قسم الأب'), ('فَحْصِ التَّحْدِيثَاتْ', 'فحص التحديثات'), ('خُطْوَةً بِخُطْوَةْ', 'خطوة بخطوة'),
    ('الشَّبَكَةَ وَالْإِنْتَرْنِتْ', 'الشبكة والإنترنت'), ('فُكَّ ضَغْطَهْ', 'فك ضغطه'), ('بِنَقْرَتَيْنْ', 'بنقرتين'),
    ('مُقْفَلَا', 'مقفلا'), ('يَتَخَطَّاهَا', 'يتخطاها'), ('لِلشَّاشَةْ', 'للشاشة'), ('اخْتِيَارِيَّةْ', 'اختيارية'),
    ('الْمُشَغِّلْ', 'المشغل'), ('ثُمَّ اخْتَرْ فَتْحْ مَرَّتَيْنْ', 'ثم اختر فتح مرتين'), ('شَبَكَتِكَ الْمَنْزِلِيَّةْ', 'شبكتك المنزلية'),
]
# phrase-final intonation probes: same words, comma vs full stop
INTON = ['تَحْتَاجُ إِلَى حَاسُوبٍ شَخْصِيّ', 'ثُمَّ اضْغَطِ الزِّرَّ الْأَوْسَطَ سَبْعَ مَرَّاتْ', 'يَظْهَرُ تَنْبِيهٌ هَادِئْ']


def wav_of(mp3: Path) -> Path:
    w = mp3.with_suffix('.wav')
    subprocess.run([FF, '-v', 'error', '-y', '-i', str(mp3), '-ac', '1', '-ar', '16000', str(w)], check=True)
    return w


from faster_whisper import WhisperModel  # noqa: E402
models = {m: WhisperModel(m, device='cpu', compute_type='int8', cpu_threads=3, download_root=str(V / 'tts/models/whisper'))
          for m in ('medium', 'small')}


def asr(path, model):
    segs, _ = models[model].transcribe(str(path), language='ar', beam_size=5, vad_filter=False,
                                       condition_on_previous_text=False, temperature=0.0)
    return ' '.join(s.text.strip() for s in segs)


rows = []
for k, (diac, ref) in enumerate(WORDS):
    text = engine_text(diac + '.')
    mp3 = OUT / f'w{k:02d}.mp3'
    if not mp3.exists():
        synth_sync(text, str(mp3), a.voice, a.rate)
    w = wav_of(mp3)
    hm, hs = asr(w, 'medium'), asr(w, 'small')
    cm, cs = cer(ref, hm), cer(ref, hs)
    rows.append({'text': text, 'ref': ref, 'medium': hm, 'small': hs, 'cer_medium': round(cm, 3), 'cer_small': round(cs, 3),
                 'best': round(min(cm, cs), 3)})
    print(f'{min(cm, cs):5.2f}  {ref:34s} | medium: {hm:30s} | small: {hs}', flush=True)

import librosa  # noqa: E402
inton = []
for k, base in enumerate(INTON):
    res = {}
    for mark in ('،', '.'):
        mp3 = OUT / f'i{k}_{"comma" if mark == "،" else "stop"}.mp3'
        if not mp3.exists():
            synth_sync(engine_text(base + mark), str(mp3), a.voice, a.rate)
        y, sr = librosa.load(str(wav_of(mp3)), sr=16000)
        f0, vf, _ = librosa.pyin(y, fmin=60, fmax=300, sr=sr, frame_length=1024, hop_length=160)
        t = np.arange(len(f0)) * 160 / sr
        ok = ~np.isnan(f0)
        tv, fv = t[ok], 12 * np.log2(f0[ok] / np.median(f0[ok]))
        last = tv >= tv[-1] - 0.4
        slope = float(np.polyfit(tv[last], fv[last], 1)[0])       # semitones per second over the last 400 ms
        res['comma' if mark == '،' else 'stop'] = {'final_slope_st_per_s': round(slope, 1),
                                                   'final_level_st': round(float(fv[last][-5:].mean()), 2)}
    inton.append({'phrase': base, **res})
    print('intonation', base, res, flush=True)

(OUT / 'report.json').write_text(json.dumps({'voice': a.voice, 'rate': a.rate, 'words': rows, 'intonation': inton},
                                            ensure_ascii=False, indent=1))
bad = [r for r in rows if r['best'] > 0.15]
print(f'\n{len(rows)} words/phrases, {len(rows) - len(bad)} recognised (best CER <= 0.15); review: {[r["ref"] for r in bad]}')
