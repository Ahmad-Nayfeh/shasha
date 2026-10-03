"""Word-level timing for the narration (drives kinetic typography / beats in the Remotion scenes).

For every scene in timeline.json: transcribe audio/scenes/<id>.wav with faster-whisper (word timestamps),
then align the recognised words to the *script* words (cue.text_tts) with a character-level edit-distance
alignment, so every script word gets a start/end time even when the ASR spells it differently.
Times are written relative to the scene start (seconds), i.e. voice_offset_s is already added.

  ../.venv/bin/python tools/align_words.py              -> src/data/words.json from ../audio/word_timings.json
  ../.venv/bin/python tools/align_words.py --mobile     -> src/data/words-mobile.json from ../audio-mobile/word_timings.json
  ... --en / --mobile --en                               the English version: words-en.json / words-mobile-en.json
  ../.venv/bin/python tools/align_words.py --asr [small|medium]   (fallback: ASR alignment as in v1)
By default the exact word times of the voice (edge-tts WordBoundary events, see tts/scripts/synth_narration.py)
are used; the ASR alignment below is only the fallback when they are missing or stale.
Re-run whenever the narration is re-synthesised (the renderer falls back to proportional timing when a
scene's audio_duration_s no longer matches).
"""
import json, re, sys, unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
RM = HERE.parent                      # remotion/
V = RM.parent                         # video/
size = next((a for a in sys.argv[1:] if not a.startswith('--')), 'small')
MOBILE = '--mobile' in sys.argv       # the phone video: timeline-mobile.json + audio-mobile/ -> src/data/words-mobile.json
EN = '--en' in sys.argv               # the English version: timeline-en.json + audio-en/ -> src/data/words-en.json
SFX = '-en' if EN else ''
TIMELINE = V / (f'timeline-mobile{SFX}.json' if MOBILE else f'timeline{SFX}.json')
WORD_TIMINGS = V / (f'audio-mobile{SFX}/word_timings.json' if MOBILE else f'audio{SFX}/word_timings.json')
WORDS_OUT = RM / (f'src/data/words-mobile{SFX}.json' if MOBILE else f'src/data/words{SFX}.json')

DIAC = re.compile(r'[ؐ-ًؚ-ٰٟۖ-ۭـ]')


def norm_word(w: str) -> str:
    w = unicodedata.normalize('NFKC', w)
    w = DIAC.sub('', w)
    w = re.sub('[إأآٱ]', 'ا', w)
    w = w.replace('ى', 'ي').replace('ة', 'ه').replace('ؤ', 'و').replace('ئ', 'ي')
    w = re.sub(r'[^\w]|_', '', w)
    return w.lower()


LATIN = {'rss': 'ارساس', 'qr': 'كيوار', 'usb': 'يواسبي', 'ip': 'ايبي', 'adb': 'ايهديبي', 'tcl': 'تيسيال',
         'tv': 'تيفي', 'google': 'غوغل', 'github': 'غيتهب', 'youtube': 'يوتيوب', 'sponsorblock': 'سبونسربلوك',
         'takeout': 'تيكاوت', 'android': 'اندرويد', 'windows': 'ويندوز', 'pin': 'بن', 'os': 'اواس'}
NUMS = {'1080': 'الفوثمانين', '10': 'عشر', '7': 'سبع', '5': 'خمس'}


def hyp_norm(w: str) -> str:
    w = norm_word(w)
    w = re.sub(r'[a-z]+', lambda m: LATIN.get(m.group(0), m.group(0)), w)
    w = w.translate(str.maketrans('٠١٢٣٤٥٦٧٨٩', '0123456789'))
    return re.sub(r'\d+', lambda m: NUMS.get(m.group(0), m.group(0)), w)


def align(ref_chars, hyp_chars):
    """Levenshtein alignment; returns for each ref char index the aligned hyp char index (or None)."""
    n, m = len(ref_chars), len(hyp_chars)
    D = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        D[i][0] = i
    for j in range(m + 1):
        D[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            c = 0 if ref_chars[i - 1] == hyp_chars[j - 1] else 1
            D[i][j] = min(D[i - 1][j - 1] + c, D[i - 1][j] + 1, D[i][j - 1] + 1)
    i, j, out = n, m, [None] * n
    while i > 0 and j > 0:
        c = 0 if ref_chars[i - 1] == hyp_chars[j - 1] else 1
        if D[i][j] == D[i - 1][j - 1] + c:
            out[i - 1] = j - 1
            i, j = i - 1, j - 1
        elif D[i][j] == D[i - 1][j] + 1:
            i -= 1
        else:
            j -= 1
    return out


def from_tts(tl):
    """Exact word times from the voice's own WordBoundary events (written by tts/scripts/synth_narration.py)."""
    p = WORD_TIMINGS
    if not p.exists():
        return None
    wt = json.loads(p.read_text())
    result = {'_meta': {'source': 'edge-tts WordBoundary (exact)', **wt.get('_meta', {}),
                        'note': 'times in seconds relative to scene start'}}
    for sc in tl['scenes']:
        d = wt.get(sc['id'])
        if not d or abs(d['audio_duration_s'] - sc['audio_duration_s']) > 0.02:
            print(sc['id'], 'word timings missing or stale -> ASR alignment')
            return None
        n_ref = sum(len([t for t in c['text_tts'].split() if norm_word(t)]) for c in sc['cues'])
        if n_ref != len(d['words']):
            print(sc['id'], f'word count {len(d["words"])} != {n_ref} -> ASR alignment')
            return None
        result[sc['id']] = {'audio_duration_s': d['audio_duration_s'], 'voice_offset_s': d['voice_offset_s'],
                            'words': d['words']}
    return result


def main():
    tl = json.loads(TIMELINE.read_text())
    if '--asr' not in sys.argv:
        res = from_tts(tl)
        if res:
            out = WORDS_OUT
            out.write_text(json.dumps(res, ensure_ascii=False, indent=1))
            print('wrote', out, '(from the voice word boundaries)')
            return
    from faster_whisper import WhisperModel
    model = WhisperModel(size, device='cpu', compute_type='int8', cpu_threads=2,
                         download_root=str(V / 'tts/models/whisper'))
    result = {'_meta': {'asr': f'faster-whisper {size} int8', 'note': 'times in seconds relative to scene start'}}
    for sc in tl['scenes']:
        wav = V / sc['audio']
        tts_text = ' '.join(c['text_tts'] for c in sc['cues'])
        segs, _ = model.transcribe(str(wav), language='en' if EN else 'ar', beam_size=5, word_timestamps=True,
                                   vad_filter=False, condition_on_previous_text=False, initial_prompt=tts_text)
        hw = [w for s in segs for w in (s.words or [])]
        # hyp chars with a time each (linear inside the word)
        hyp_chars, hyp_t = [], []
        for w in hw:
            t = hyp_norm(w.word)
            for k, ch in enumerate(t):
                hyp_chars.append(ch)
                hyp_t.append(w.start + (w.end - w.start) * (k / max(1, len(t))))
        # ref words (keep original spelling for display/debug)
        ref_words = []
        for ci, c in enumerate(sc['cues']):
            for raw in c['text_tts'].split():
                nw = norm_word(raw)
                if nw:
                    ref_words.append({'w': raw, 'n': nw, 'cue': ci})
        ref_chars, owner = [], []
        for wi, r in enumerate(ref_words):
            for ch in r['n']:
                ref_chars.append(ch)
                owner.append(wi)
        a = align(ref_chars, hyp_chars)
        starts = [None] * len(ref_words)
        ends = [None] * len(ref_words)
        for ci, hj in enumerate(a):
            if hj is None:
                continue
            wi = owner[ci]
            t = hyp_t[hj]
            if starts[wi] is None:
                starts[wi] = t
            ends[wi] = t
        # word end: use the hyp word end containing the last aligned char where possible
        off = sc['voice_offset_s']
        # fill gaps by interpolation inside the cue windows
        cue_s = [c['start_s'] - off for c in sc['cues']]
        cue_e = [c['end_s'] - off for c in sc['cues']]
        n = len(ref_words)
        for wi in range(n):
            if starts[wi] is None:
                # previous known end, next known start
                p = next((ends[k] for k in range(wi - 1, -1, -1) if ends[k] is not None), cue_s[ref_words[wi]['cue']])
                q = next((starts[k] for k in range(wi + 1, n) if starts[k] is not None), cue_e[ref_words[wi]['cue']])
                starts[wi] = p
                ends[wi] = max(p, q)
        words = []
        for wi, r in enumerate(ref_words):
            s = max(0.0, starts[wi]) + off
            e = max(starts[wi], ends[wi] or starts[wi]) + off + 0.12
            words.append({'w': r['w'], 'cue': r['cue'], 's': round(s, 3), 'e': round(e, 3)})
        # enforce monotonic starts
        for k in range(1, len(words)):
            if words[k]['s'] < words[k - 1]['s']:
                words[k]['s'] = words[k - 1]['s']
        matched = sum(1 for x in a if x is not None) / max(1, len(a))
        result[sc['id']] = {'audio_duration_s': sc['audio_duration_s'], 'voice_offset_s': off,
                            'char_match': round(matched, 3), 'hyp': ' '.join(w.word.strip() for w in hw),
                            'words': words}
        print(sc['id'], 'chars aligned', round(matched, 3), flush=True)
    out = WORDS_OUT
    out.write_text(json.dumps(result, ensure_ascii=False, indent=1))
    print('wrote', out)


if __name__ == '__main__':
    main()
