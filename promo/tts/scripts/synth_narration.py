"""Synthesise the narration phrase by phrase with a Microsoft Edge neural voice (edge-tts), insert the pauses
ourselves, normalise loudness, verify every scene with ASR, and write the timing data the renderer needs.

  python tts/scripts/synth_narration.py [--voice ar-SA-HamedNeural] [--rate=-6%] [--only s03_home_search,...]

Pipeline
  1. narration/scenes.json -> phrases (narr.split_phrases on `tts_diac`; the same skeleton in `tts`/`display`).
  2. Each phrase -> narr.engine_text -> edge-tts (cached in tts/cache/<sha1>.mp3 + .json with the engine's own
     WordBoundary events, so re-runs only synthesise changed phrases).
  3. Decode to 48 kHz, trim the engine's leading/trailing silence (20 ms / 50 ms guard), 6 ms fades.
  4. Scene = phrases joined by real silences: 0.25 s after ، | 0.3 s after : | 0.5 s after . ؟ | 0.9 s at a
     blank line (beat). EBU R128 per scene: gentle 2:1 compression, gain to -16 LUFS, true peak <= -1.5 dBTP.
  5. ASR check (faster-whisper medium, language=ar) of every final scene WAV -> CER; scenes above --warn-cer are
     re-checked phrase by phrase so the faulty words are listed.
  6. Outputs: audio/scenes/<id>.wav, audio/narration_full.wav (placed on the video timeline), timeline.json,
     audio/word_timings.json (exact word times from the voice), audio/asr_report.json.

TLS: set EDGE_TTS_CA_FILE to a CA bundle when behind an intercepting proxy (verification stays on).
Voice: Microsoft Edge "Read aloud" service via the edge-tts package - personal, non-commercial use.
"""
import argparse, hashlib, json, subprocess, sys, time
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).parent))
from arnorm import cer, normalize, latin_to_arabic  # noqa: E402
from narr import split_phrases, words, engine_text, strip_diac  # noqa: E402

V = Path(__file__).resolve().parents[2]
FFMPEG = str(V / 'bin/ffmpeg')
SR = 48000

ap = argparse.ArgumentParser()
ap.add_argument('--voice', default='ar-SA-HamedNeural')
ap.add_argument('--rate', default='-6%')
ap.add_argument('--pitch', default='+0Hz')
ap.add_argument('--out', default='audio')
ap.add_argument('--fps', type=int, default=30)
ap.add_argument('--only', default='', help='comma-separated scene ids to ASR-check (all scenes are assembled)')
ap.add_argument('--no-asr', action='store_true')
ap.add_argument('--warn-cer', type=float, default=0.04)
ap.add_argument('--asr-model', default='medium')
args = ap.parse_args()

OUT = V / args.out
CACHE = V / 'tts/cache'
for d in (OUT / 'scenes', OUT / 'raw', CACHE):
    d.mkdir(parents=True, exist_ok=True)

# visual padding around the voice (seconds)
LEAD_IN = 0.3          # default: visuals settle before the voice starts (per scene "lead" overrides)
TAIL = 0.4             # after the voice, before the next scene
SECTION_EXTRA = 0.6    # extra breathing room when the section changes
FIRST_LEAD = 2.5       # logo reveal before the first word
LAST_HOLD = 3.2        # end card hold after the last word
HEAD, TAILPAD = 0.02, 0.05    # guard kept around each phrase when trimming the engine's silence

scenes = json.loads((V / 'narration/scenes.json').read_text())


# ------------------------------------------------------------------ synthesis (cached)
def synth_phrase(text: str):
    key = hashlib.sha1(f'{args.voice}|{args.rate}|{args.pitch}|{text}'.encode()).hexdigest()[:16]
    mp3, meta = CACHE / f'{key}.mp3', CACHE / f'{key}.json'
    if not (mp3.exists() and meta.exists()):
        from edge_synth import synth_sync
        for attempt in range(3):
            wb = synth_sync(text, str(mp3), args.voice, args.rate, args.pitch)
            y = decode(mp3)
            if len(y) > SR * 0.2 and np.abs(y).max() > 0.05:
                break
            print('    (empty audio from the service, retrying)', flush=True)
            time.sleep(2)
        else:
            raise SystemExit(f'no usable audio for: {text}')
        meta.write_text(json.dumps({'text': text, 'voice': args.voice, 'rate': args.rate, 'words': wb}, ensure_ascii=False))
    return mp3, json.loads(meta.read_text())['words']


def decode(path: Path) -> np.ndarray:
    raw = subprocess.run([FFMPEG, '-v', 'error', '-i', str(path), '-ac', '1', '-ar', str(SR),
                          '-af', 'aresample=resampler=soxr', '-f', 'f32le', '-'], capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.float32).copy()


def trim(y: np.ndarray):
    """Cut the engine's silence: first/last 10 ms frame above -45 dB re. the phrase peak, with guards."""
    hop = int(SR * 0.01)
    n = len(y) // hop
    rms = np.sqrt((y[: n * hop].reshape(n, hop) ** 2).mean(1) + 1e-12)
    db = 20 * np.log10(rms / (np.abs(y).max() + 1e-9))
    idx = np.where(db > -45)[0]
    a = max(0, idx[0] * hop - int(HEAD * SR))
    b = min(len(y), (idx[-1] + 1) * hop + int(TAILPAD * SR))
    seg = y[a:b].copy()
    f = int(0.006 * SR)
    seg[:f] *= np.linspace(0, 1, f)
    seg[-f:] *= np.linspace(1, 0, f)
    return seg, a / SR


# ------------------------------------------------------------------ loudness (EBU R128)
PRE = 'highpass=f=60,acompressor=threshold=0.125:ratio=2:attack=10:release=150:knee=4'


def _ln_json(stderr: str) -> dict:
    a = stderr.rindex('{')
    return json.loads(stderr[a:stderr.index('}', a) + 1])


def measure(path: Path, pre: str = ''):
    p = subprocess.run([FFMPEG, '-hide_banner', '-nostats', '-i', str(path), '-af',
                        (pre + ',' if pre else '') + 'loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json', '-f', 'null', '-'],
                       capture_output=True, text=True)
    m = _ln_json(p.stderr)
    return {'integrated_lufs': float(m['input_i']), 'true_peak_dbtp': float(m['input_tp']), 'lra': float(m['input_lra'])}


def loudnorm(src: Path, dst: Path, target=-16.0, pre: str = PRE):
    m = measure(src, pre)
    gain = target - m['integrated_lufs']
    af = (f"{pre + ',' if pre else ''}volume={gain:.2f}dB,aresample=192000:resampler=soxr,"
          f"alimiter=limit=0.79:attack=2:release=60:level=false,aresample={SR}:resampler=soxr")
    subprocess.run([FFMPEG, '-hide_banner', '-loglevel', 'error', '-y', '-i', str(src), '-af', af,
                    '-ar', str(SR), '-ac', '1', '-c:a', 'pcm_s16le', str(dst)], check=True)
    return measure(dst)


# ------------------------------------------------------------------ word timings from the voice
def map_words(boundaries, diac_words, plain_words, offset):
    """Engine WordBoundary events -> the phrase's `tts` words (1:1 when the engine keeps our tokens; otherwise
    a character-proportional fallback inside the phrase). Times in seconds, shifted by `offset`."""
    wb = [b for b in boundaries if b['type'] == 'WordBoundary']
    key = lambda t: normalize(strip_diac(t)).replace(' ', '')
    if len(wb) == len(diac_words) and all(key(b['text']) == key(w) for b, w in zip(wb, diac_words)):
        # a Latin word ("Android", "OS") can stand for 1-2 spoken plain words (أندرويد / أو إس): split its span
        spans = [len(latin_to_arabic(w).split()) for w in diac_words]
        if sum(spans) == len(plain_words):
            out, k = [], 0
            for b, n in zip(wb, spans):
                ps = plain_words[k:k + n]
                L = sum(len(p) + 1 for p in ps)
                acc = 0
                for p in ps:
                    s = b['start'] + b['dur'] * acc / L
                    acc += len(p) + 1
                    out.append({'w': p, 's': s + offset, 'e': b['start'] + b['dur'] * acc / L + offset})
                k += n
            return out, True
    # fallback: spread the engine's first..last boundary proportionally to word length
    s0 = (wb[0]['start'] if wb else 0) + offset
    s1 = (wb[-1]['start'] + wb[-1]['dur'] if wb else 0) + offset
    L = sum(len(w) + 1 for w in plain_words)
    out, acc = [], 0
    for p in plain_words:
        a = s0 + (s1 - s0) * acc / L
        acc += len(p) + 1
        out.append({'w': p, 's': a, 'e': s0 + (s1 - s0) * acc / L})
    return out, False


# ------------------------------------------------------------------ build scenes
t_video, prev_section = 0.0, None
timeline_scenes, combined, word_file, report = [], [], {}, []
onset_lags = []
for i, sc in enumerate(scenes):
    P = split_phrases(sc['tts_diac'])
    R = split_phrases(sc['tts'])
    D = split_phrases(sc['display'])
    assert len(P) == len(R) == len(D), sc['id']
    print(f'[{sc["id"]}] {len(P)} phrases', flush=True)
    audio = [np.zeros(int(0.1 * SR), np.float32)]
    pos = 0.1
    phrases, scene_words = [], []
    cue_idx, cues, cur = 0, [], None
    for k, (p, r, d) in enumerate(zip(P, R, D)):
        et = engine_text(p['text'])
        mp3, wb = synth_phrase(et)
        y = decode(mp3)
        seg, cut = trim(y)
        start = pos
        audio.append(seg)
        pos += len(seg) / SR
        if wb and wb[0]['type'] == 'WordBoundary':
            onset_lags.append(cut + HEAD - wb[0]['start'])
        mw, exact = map_words(wb, words(p['text']), words(r['text']), start - cut)
        if cur is None:
            cur = {'start_s': start, 'display': [], 'tts': []}
        cur['display'].append(d['text'])
        cur['tts'].append(r['text'])
        for w in mw:
            w['cue'] = len(cues)
        scene_words += mw
        phrases.append({'text': et, 'start': round(start, 3), 'end': round(pos, 3), 'exact_words': exact,
                        'dur': round(len(seg) / SR, 3)})
        if p['cue_end'] or k == len(P) - 1:
            cur['end_s'] = pos
            cues.append(cur)
            cur = None
        if k < len(P) - 1:
            gap = p['pause']
            audio.append(np.zeros(int(gap * SR), np.float32))
            pos += gap
    audio.append(np.zeros(int(0.1 * SR), np.float32))
    raw = OUT / 'raw' / f'{sc["id"]}.wav'
    sf.write(raw, np.concatenate(audio), SR, subtype='FLOAT')
    dst = OUT / 'scenes' / f'{sc["id"]}.wav'
    lm = loudnorm(raw, dst)
    dur = sf.info(str(dst)).duration

    lead = FIRST_LEAD if i == 0 else float(sc.get('lead', LEAD_IN))
    if prev_section is not None and sc['section'] != prev_section:
        lead += SECTION_EXTRA
    tail = LAST_HOLD if i == len(scenes) - 1 else float(sc.get('tail', TAIL))
    fps = args.fps
    start_f = round(t_video * fps)
    end_f = round((t_video + lead + dur + tail) * fps)
    timeline_scenes.append({
        'id': sc['id'], 'section': sc['section'], 'title': sc['title'], 'step': sc.get('step'),
        'audio': f'{args.out}/scenes/{sc["id"]}.wav', 'audio_duration_s': round(dur, 3),
        'start_s': round(start_f / fps, 3), 'duration_s': round((end_f - start_f) / fps, 3),
        'start_frame': start_f, 'duration_frames': end_f - start_f,
        'voice_offset_s': round(lead, 3), 'voice_offset_frames': round(lead * fps),
        'cues': [{'start_s': round(c['start_s'] + lead, 3), 'end_s': round(c['end_s'] + lead, 3),
                  'start_frame': round((c['start_s'] + lead) * fps), 'end_frame': round((c['end_s'] + lead) * fps),
                  'text_display': ' '.join(c['display']), 'text_tts': ' '.join(c['tts'])} for c in cues],
    })
    word_file[sc['id']] = {'audio_duration_s': round(dur, 3), 'voice_offset_s': round(lead, 3),
                           'source': 'edge-tts WordBoundary', 'phrases': phrases,
                           'words': [{'w': w['w'], 'cue': w['cue'], 's': round(w['s'] + lead, 3), 'e': round(w['e'] + lead, 3)}
                                     for w in scene_words]}
    nwords = len(words(sc['tts']))
    report.append({'id': sc['id'], 'audio_s': round(dur, 2), 'words': nwords,
                   'speech_s': round(sum(p['dur'] for p in phrases), 2),
                   'wpm_speech': round(nwords / (sum(p['dur'] for p in phrases) / 60), 1),
                   'wpm_overall': round(nwords / (dur / 60), 1), 'loudness': lm,
                   'phrases': len(P), 'exact_word_timing': all(p['exact_words'] for p in phrases)})
    combined.append((start_f / fps + lead, dst))
    t_video = end_f / fps
    prev_section = sc['section']
    print(f'  {dur:6.2f}s  {nwords} words  {report[-1]["wpm_speech"]} wpm(speech)  I={lm["integrated_lufs"]} TP={lm["true_peak_dbtp"]}', flush=True)

# The engine's WordBoundary offsets run ahead of the decoded MP3 by a near-constant amount (stream/decoder delay):
# measured as (energy onset of each phrase) - (first word's boundary): 0.107-0.117 s on every phrase (10 ms hop).
# Every word is shifted by the 10th percentile minus 5 ms.
lag = float(np.median(onset_lags)) if onset_lags else 0.0
corr = max(0.0, float(np.percentile(onset_lags, 10)) - 0.005) if onset_lags else 0.0
for d in word_file.values():
    for w in d['words']:
        w['s'] = round(w['s'] + corr, 3)
        w['e'] = round(w['e'] + corr, 3)
if onset_lags:
    q = np.percentile(onset_lags, [10, 25, 50, 75, 90])
    print('onset lag percentiles (10/25/50/75/90):', ' '.join(f'{x:.3f}' for x in q), ' -> word shift', round(corr, 3))

total = t_video
buf = np.zeros(int(round(total * SR)), np.float32)
for start, p in combined:
    y, _ = sf.read(p, dtype='float32')
    a = int(round(start * SR))
    buf[a:a + len(y)] += y[: max(0, len(buf) - a)]
comb_raw = OUT / 'raw' / 'narration_combined_prenorm.wav'
sf.write(comb_raw, buf, SR, subtype='FLOAT')
meas = loudnorm(comb_raw, OUT / 'narration_full.wav', pre='')

# ------------------------------------------------------------------ ASR verification
asr_info = None
if not args.no_asr:
    from faster_whisper import WhisperModel
    asr = WhisperModel(args.asr_model, device='cpu', compute_type='int8', cpu_threads=3,
                       download_root=str(V / 'tts/models/whisper'))

    def transcribe(path):
        segs, _ = asr.transcribe(str(path), language='ar', beam_size=5, vad_filter=False,
                                 condition_on_previous_text=False, temperature=0.0)
        return ' '.join(s.text.strip() for s in segs)

    only = set(filter(None, args.only.split(',')))
    prev = {}
    if only and (OUT / 'asr_report.json').exists():   # keep the last check of the scenes not re-checked now
        prev = {s['id']: s for s in json.loads((OUT / 'asr_report.json').read_text())['scenes']}
    for r, sc in zip(report, scenes):
        if only and sc['id'] not in only:
            old = prev.get(sc['id'])
            if old and 'cer' in old and abs(old['audio_s'] - r['audio_s']) < 0.011:
                r.update({k: old[k] for k in ('cer', 'hyp', 'phrase_check') if k in old})
            continue
        hyp = transcribe(OUT / 'scenes' / f'{sc["id"]}.wav')
        r['hyp'] = hyp
        r['cer'] = round(cer(sc['tts'], hyp), 4)
        print(f'  ASR {sc["id"]}: CER {r["cer"]:.3f}  {hyp}', flush=True)
        if r['cer'] > args.warn_cer:   # localise: phrase by phrase
            r['phrase_check'] = []
            y, _ = sf.read(OUT / 'scenes' / f'{sc["id"]}.wav', dtype='float32')
            R = split_phrases(sc['tts'])
            for ph, rr in zip(word_file[sc['id']]['phrases'], R):
                a, b = int(max(0, ph['start'] - 0.05) * SR), int((ph['end'] + 0.05) * SR)
                tmp = OUT / 'raw' / '_phrase.wav'
                sf.write(tmp, y[a:b], SR)
                h = transcribe(tmp)
                c = cer(rr['text'], h)
                r['phrase_check'].append({'ref': rr['text'], 'hyp': h, 'cer': round(c, 3)})
                if c > 0:
                    print(f'      phrase CER {c:.2f}: «{rr["text"]}» -> «{h}»', flush=True)
    checked = [r for r in report if 'cer' in r]
    if checked:
        tot = sum(len(normalize(s['tts'])) for s, r in zip(scenes, report) if 'cer' in r)
        overall = sum(r['cer'] * len(normalize(s['tts'])) for s, r in zip(scenes, report) if 'cer' in r) / tot
        asr_info = {'model': f'faster-whisper {args.asr_model} int8, language=ar', 'overall_cer_char_weighted': round(overall, 4)}
        print('overall CER', round(overall, 4))

engine = {'engine': 'edge-tts 7.2.8 (Microsoft Edge Read Aloud neural voices)', 'voice': args.voice,
          'rate': args.rate, 'pitch': args.pitch, 'text_field': 'tts_diac',
          'pauses_s': {'comma': 0.25, 'colon': 0.3, 'sentence': 0.5, 'beat': 0.9},
          'note': 'phrase-by-phrase synthesis, silences inserted by us; waqf ta marbuta sent as هْ; shadda written before its vowel'}
timeline = {
    'fps': args.fps, 'width': 1920, 'height': 1080,
    'total_duration_s': round(total, 3), 'total_frames': round(total * args.fps),
    'narration_track': f'{args.out}/narration_full.wav',
    'tts': engine,
    'loudness': {'target': '-16 LUFS integrated, true peak <= -1.5 dBTP (EBU R128 / ffmpeg loudnorm measurement)',
                 'measured_combined': meas},
    'asr_check': asr_info,
    'padding_s': {'lead_in': LEAD_IN, 'tail': TAIL, 'section_extra': SECTION_EXTRA, 'first_lead': FIRST_LEAD,
                  'last_hold': LAST_HOLD},
    'scenes': timeline_scenes,
}
(V / 'timeline.json').write_text(json.dumps(timeline, ensure_ascii=False, indent=1))
(OUT / 'word_timings.json').write_text(json.dumps({'_meta': {'source': engine, 'median_onset_lag_s': round(lag, 3), 'word_shift_s': round(corr, 3),
                                                              'note': 'seconds from scene start (voice offset included)'},
                                                    **word_file}, ensure_ascii=False, indent=1))
(OUT / 'asr_report.json').write_text(json.dumps({'engine': engine, 'asr': asr_info, 'scenes': report}, ensure_ascii=False, indent=1))
print(f'TOTAL {total:.2f} s ({int(total // 60)}:{total % 60:04.1f})  combined {meas}')
