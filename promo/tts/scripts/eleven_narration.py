"""ElevenLabs narration (the default voice of the explainers): writes the request, assembles the result.

The voice is "Abdullah - Professional, and Energetic" (voice_id pCKbQ4EPGE06zpEPGNvS, model
eleven_multilingual_v2, no language_code, mp3_44100_192). The API key exists only as the repository secret
ELEVEN_LABS, so the audio is made by the GitHub workflow .github/workflows/voice.yml (promo/voice/run.py), never here.

  1. request   python tts/scripts/eleven_narration.py request [--script tv,mobile] [--no-samples]
               Writes promo/voice/request.json: one "tts" job per phrase (narr.split_phrases of the `tts` text,
               the same phrases and pauses as the edge-tts pipeline, `timestamps: true`, the neighbouring phrases
               as previous_text / next_text), plus a "samples" job pair that previews English narrator voices
               (3 male + 2 female) of the shared library. Push the file: the workflow runs and uploads the
               artifact "voice-output".
  2. assemble  python tts/scripts/eleven_narration.py assemble <voice-output folder> [<retry folder> ...] [--script tv,mobile]
               Per script: decodes every phrase, trims the engine's silence, levels the phrases (to the median speech level), inserts
               the pauses (0.25 s after «،», 0.3 s after «:», 0.5 s after a sentence, 0.9 s at a blank line), EBU R128
               per scene (-16 LUFS, true peak <= -1.5 dBTP), and writes
                 timeline.json / timeline-mobile.json          scene windows and sentence cues (read by Remotion)
                 audio/word_timings.json (audio-mobile/...)    word times from the character alignment
                 audio/scenes/<id>.wav, audio/narration_full.wav (placed on the video timeline, -16 LUFS)
                 narration/narration.m4a (narration-mobile.m4a)  the compressed track
               then run `npm run words` (tools/align_words.py [--mobile]) in remotion/.
  3. retry     python tts/scripts/eleven_narration.py retry <voice-output folder> [<retry folder> ...]
               Writes a request.json that makes only the phrases the folders lack: failed ones (errors.txt lists them)
               and ones whose text was edited since. Assemble the first run and the retry's folder together.

Needs: Python 3.11, numpy, and ffmpeg (promo/bin/ffmpeg, or on the PATH). No other package.
English: the same commands work for the scripts `tv-en` / `mobile-en` (narration/scenes-en.json,
scenes-mobile-en.json) with --voice <voice_id> (the English voice the owner picks from the samples).
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from narr import split_phrases, words  # noqa: E402

V = Path(__file__).resolve().parents[2]            # promo/
SR = 48000
VOICE_ID = 'pCKbQ4EPGE06zpEPGNvS'                  # Abdullah - Professional, and Energetic
MODEL_ID = 'eleven_multilingual_v2'
EN_SAMPLE = ('Shasha is a calm YouTube for the family TV: no ads, no sign-in, and full control for the parents.')

# script key -> files. `text` is the scenes.json field that is spoken; English scripts have no diacritised variant.
SCRIPTS = {
    'tv': dict(scenes='narration/scenes.json', text='tts', timeline='timeline.json', audio='audio',
               m4a='narration/narration.m4a', first_lead=2.5, last_hold=3.2),
    'mobile': dict(scenes='narration/scenes-mobile.json', text='tts', timeline='timeline-mobile.json',
                   audio='audio-mobile', m4a='narration/narration-mobile.m4a', first_lead=2.2, last_hold=3.0),
    'tv-en': dict(scenes='narration/scenes-en.json', text='tts', timeline='timeline-en.json', audio='audio-en',
                  m4a='narration/narration-en.m4a', first_lead=2.5, last_hold=3.2),
    'mobile-en': dict(scenes='narration/scenes-mobile-en.json', text='tts', timeline='timeline-mobile-en.json',
                      audio='audio-mobile-en', m4a='narration/narration-mobile-en.m4a', first_lead=2.2,
                      last_hold=3.0),
}

# visual padding around the voice (seconds), as in synth_narration.py
LEAD_IN, TAIL, SECTION_EXTRA = 0.3, 0.4, 0.6
HEAD, TAILPAD = 0.02, 0.05          # guard kept around each phrase when its silence is trimmed
PRE = 'highpass=f=60,acompressor=threshold=0.125:ratio=2:attack=10:release=150:knee=4'


# ====================================================================================== request
def load_scenes(key):
    return json.loads((V / SCRIPTS[key]['scenes']).read_text(encoding='utf-8'))


def eleven_text(phrase: str) -> str:
    """What is sent for one phrase: the spoken text with its final pause mark; quotes and brackets are for the eye."""
    return re.sub(r'\s+', ' ', re.sub(r'[«»()"]', '', phrase)).strip()


def phrase_jobs(key, voice_id=VOICE_ID, model_id=MODEL_ID):
    cfg, prefix, jobs = SCRIPTS[key], key.replace('-', '_'), []
    for sc in load_scenes(key):
        texts = [eleven_text(p['text']) for p in split_phrases(sc[cfg['text']])]
        for k, text in enumerate(texts):
            job = {'type': 'tts', 'voice_id': voice_id, 'model_id': model_id, 'text': text,
                   'out': f"{prefix}_{sc['id']}_{k:02d}.mp3", 'timestamps': True}
            before, after = ' '.join(texts[max(0, k - 2):k]), ' '.join(texts[k + 1:k + 3])
            if before:
                job['previous_text'] = before
            if after:
                job['next_text'] = after
            jobs.append(job)
    return jobs


def sample_jobs():
    """English narrator previews: 3 male + 2 female voices of the shared library, most used first. The library's
    use-case labels are e.g. narrative_story and informative_educational; if they match too few voices the same
    query is repeated without them."""
    jobs = []
    for gender, count in (('male', 3), ('female', 2)):
        jobs.append({'type': 'samples', 'text': EN_SAMPLE, 'model_id': MODEL_ID, 'prefix': f'en-{gender}-',
                     'count': count,
                     'filters': {'language': 'en', 'gender': gender,
                                 'use_cases': ['narrative_story', 'informative_educational']},
                     'fallback_filters': [{'language': 'en', 'gender': gender}]})
    return jobs


def write_request(path, jobs, rid, workers=3, budget=1500):
    lines = ['{', f' "id": {json.dumps(rid)},', f' "workers": {workers},', f' "budget_seconds": {budget},',
             ' "jobs": [']
    lines.append(',\n'.join('  ' + json.dumps(j, ensure_ascii=False) for j in jobs))
    lines += [' ]', '}', '']
    Path(path).write_text('\n'.join(lines), encoding='utf-8')


def cmd_request(a):
    keys = [k.strip() for k in a.script.split(',') if k.strip()]
    jobs = [{'type': 'subscription'}]
    for k in keys:
        jobs += phrase_jobs(k, a.voice, MODEL_ID)
    if not a.no_samples:
        jobs += sample_jobs()
    out = Path(a.out)
    write_request(out, jobs, a.id or 'narration-' + '-'.join(keys))
    n = sum(1 for j in jobs if j['type'] == 'tts')
    chars = sum(len(j['text']) for j in jobs if j['type'] == 'tts')
    print(f'{out}: {n} tts jobs ({chars} characters) + {sum(1 for j in jobs if j["type"] == "samples")} samples jobs')


# ====================================================================================== audio helpers
def ffmpeg():
    local = V / 'bin/ffmpeg'
    found = str(local) if local.exists() else shutil.which('ffmpeg')
    if not found:
        sys.exit('ffmpeg not found: put a static build at promo/bin/ffmpeg (see promo/README.md) or on the PATH.')
    return found


FF = None
RESAMPLER = None


def setup():
    """Locate ffmpeg once; use the soxr resampler when this build has it."""
    global FF, RESAMPLER
    if FF:
        return
    FF = ffmpeg()
    probe = subprocess.run([FF, '-hide_banner', '-v', 'error', '-f', 'lavfi', '-i', 'anullsrc=r=8000:d=0.05',
                            '-af', 'aresample=16000:resampler=soxr', '-f', 'null', '-'], capture_output=True)
    RESAMPLER = ':resampler=soxr' if probe.returncode == 0 else ''


def decode(path) -> np.ndarray:
    raw = subprocess.run([FF, '-v', 'error', '-i', str(path), '-ac', '1', '-af', f'aresample={SR}{RESAMPLER}',
                          '-f', 'f32le', '-'], capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.float32).copy()


def write_wav(path, y, codec='pcm_f32le'):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([FF, '-v', 'error', '-y', '-f', 'f32le', '-ar', str(SR), '-ac', '1', '-i', '-', '-c:a', codec,
                    str(path)], input=np.asarray(y, np.float32).tobytes(), check=True)


def read_wav(path) -> np.ndarray:
    return decode(path)


def trim(y: np.ndarray):
    """Cut the engine's silence: first/last 10 ms frame above -45 dB re. the phrase peak, with guards.
    Returns (segment, seconds cut from the start of the original)."""
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


def speech_db(y: np.ndarray) -> float:
    """RMS (dBFS) of the active 10 ms frames of a phrase (those within 30 dB of its loudest frame)."""
    hop = int(SR * 0.01)
    n = len(y) // hop
    if n == 0:
        return -60.0
    rms = np.sqrt((y[: n * hop].reshape(n, hop) ** 2).mean(1) + 1e-12)
    loud = rms[rms > rms.max() * 10 ** (-30 / 20)]
    return float(20 * np.log10(np.sqrt((loud ** 2).mean()) + 1e-12))


def _ln_json(stderr: str) -> dict:
    a = stderr.rindex('{')
    return json.loads(stderr[a:stderr.index('}', a) + 1])


def measure(path, pre=''):
    p = subprocess.run([FF, '-hide_banner', '-nostats', '-i', str(path), '-af',
                        (pre + ',' if pre else '') + 'loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json',
                        '-f', 'null', '-'], capture_output=True, text=True)
    m = _ln_json(p.stderr)
    return {'integrated_lufs': float(m['input_i']), 'true_peak_dbtp': float(m['input_tp']), 'lra': float(m['input_lra'])}


def loudnorm(src, dst, target=-16.0, pre=PRE):
    """Gentle 2:1 compression, linear gain to the target loudness, a limiter for the peaks. Returns the result."""
    m = measure(src, pre)
    gain = target - m['integrated_lufs']
    af = (f"{pre + ',' if pre else ''}volume={gain:.2f}dB,aresample=192000{RESAMPLER},"
          f"alimiter=limit=0.79:attack=2:release=60:level=false,aresample={SR}{RESAMPLER}")
    subprocess.run([FF, '-hide_banner', '-loglevel', 'error', '-y', '-i', str(src), '-af', af, '-ar', str(SR),
                    '-ac', '1', '-c:a', 'pcm_s16le', str(dst)], check=True)
    return measure(dst)


# ====================================================================================== alignment -> words
TOKEN_STRIP = re.compile(r'^[«»()"\'،,:؛.؟?!]+|[«»()"\'،,:؛.؟?!]+$')


def phrase_words(sent: str, timing: dict):
    """[(token, start, end)] for the spoken words of one phrase, in seconds from the start of its MP3.
    `sent` is the exact text that was sent; the alignment has one entry per character of it. When the characters do
    not line up (the service normalised the text) the words are spread over the aligned span by length."""
    toks = []
    for m in re.finditer(r'\S+', sent):
        core = TOKEN_STRIP.sub('', m.group(0))
        if not re.search(r'\w', core):
            continue
        i0 = m.start() + len(m.group(0)) - len(m.group(0).lstrip('«»()"\'،,:؛.؟?!'))
        toks.append((core, i0, i0 + len(core) - 1))
    al = timing.get('alignment') or timing.get('normalized_alignment')
    if al and ''.join(al['characters']) == sent:
        st, en = al['character_start_times_seconds'], al['character_end_times_seconds']
        return [(w, float(st[a]), float(en[b])) for w, a, b in toks], True
    # fallback: proportional over the aligned span (or 0 .. 1 s when there is no alignment at all)
    if al and al.get('character_start_times_seconds'):
        t0, t1 = float(al['character_start_times_seconds'][0]), float(al['character_end_times_seconds'][-1])
    else:
        t0, t1 = 0.0, 1.0
    total = sum(len(w) + 1 for w, _, _ in toks)
    out, acc = [], 0
    for w, _, _ in toks:
        a = t0 + (t1 - t0) * acc / total
        acc += len(w) + 1
        out.append((w, a, t0 + (t1 - t0) * acc / total))
    return out, False


# ====================================================================================== assemble
def artifact_index(art: Path, prefix: str):
    """{(scene id, phrase text): (mp3, timing json)} of an unpacked voice-output folder. The text is read back from the
    character alignment, so a phrase is found whatever its file is called and wherever it sits in its scene: the scripts
    can be trimmed after the audio was made without the phrase numbers (tv_s09_channel_03.mp3) getting out of step."""
    idx = {}
    pat = re.compile(rf'^{re.escape(prefix)}_(.+?)_\d\d(?:r[0-9a-f]{{6}})?$')
    for tj in sorted(art.glob(f'{prefix}_*.timing.json')):
        m = pat.match(tj.name[:-len('.timing.json')])
        mp3 = tj.with_name(tj.name[:-len('.timing.json')] + '.mp3')
        if not m or not mp3.exists():
            continue
        try:
            al = json.loads(tj.read_text(encoding='utf-8')).get('alignment') or {}
        except ValueError:
            continue
        text = ''.join(al.get('characters', []))
        if text and mp3.stat().st_size > 1500:
            idx[(m.group(1), text)] = (mp3, tj)
    return idx


def artifact_index_all(arts, prefix: str):
    """The index of several voice-output folders; a later folder wins (a retry's folder after the first run's)."""
    idx = {}
    for art in arts:
        idx.update(artifact_index(art, prefix))
    return idx


def assemble_script(key, arts, fps: int, m4a: bool):
    cfg, prefix = SCRIPTS[key], key.replace('-', '_')
    scenes = load_scenes(key)
    audio_dir = V / cfg['audio']
    (audio_dir / 'scenes').mkdir(parents=True, exist_ok=True)
    (audio_dir / 'raw').mkdir(parents=True, exist_ok=True)

    # ---- 1. every phrase: decode, trim, measure
    plan, missing = [], []
    index = artifact_index_all(arts, prefix)
    for sc in scenes:
        P, D = split_phrases(sc[cfg['text']]), split_phrases(sc['display'])
        assert len(P) == len(D), f"{sc['id']}: display and spoken text have different phrase skeletons"
        for k, (p, d) in enumerate(zip(P, D)):
            hit = index.get((sc['id'], eleven_text(p['text'])))
            if not hit:
                missing.append(f"{sc['id']}#{k} «{eleven_text(p['text'])}»")
                continue
            plan.append((sc['id'], k, p, d, hit[0], hit[1]))
    if missing:
        folders = ' '.join(str(x) for x in arts)
        sys.exit(f'{key}: {len(missing)} phrase(s) not in {folders}:\n  ' + '\n  '.join(missing[:10]) +
                 ('\n  ...' if len(missing) > 10 else '') + f'\nRun: eleven_narration.py retry {folders}')
    phrases = {}
    for sid, k, p, d, mp3, timing in plan:
        y = decode(mp3)
        if len(y) < SR * 0.15 or np.abs(y).max() < 0.01:
            sys.exit(f'{mp3.name}: empty or silent audio')
        seg, cut = trim(y)
        t = json.loads(timing.read_text(encoding='utf-8'))
        sent = eleven_text(p['text'])
        mw, exact = phrase_words(sent, t)
        assert len(mw) == len(words(p['text'])), f'{mp3.name}: word count differs from the script'
        phrases[(sid, k)] = dict(seg=seg, cut=cut, words=mw, exact=exact, sent=sent, level=speech_db(seg),
                                 first=(mw[0][1] if mw else 0.0))
    # leveling: phrases are made one by one and the voice's level varies by several dB from phrase to phrase (std about
    # 4 dB, 17 dB end to end), which would be heard at every joint: bring each 90 % of the way to the median speech level
    target = float(np.median([v['level'] for v in phrases.values()]))
    for v in phrases.values():
        g = float(np.clip(0.9 * (target - v['level']), -10.0, 10.0))
        v['seg'] = v['seg'] * 10 ** (g / 20)
        v['gain_db'] = round(g, 2)
    # the alignment runs ahead of the decoded MP3 by a near-constant decoder delay: measure it as (energy onset of
    # each phrase) - (its first word's start) and shift every word by the 10th percentile minus 5 ms
    lags = [v['cut'] + HEAD - v['first'] for v in phrases.values()]
    corr = float(np.clip(np.percentile(lags, 10) - 0.005, 0.0, 0.15)) if lags else 0.0

    # ---- 2. scenes: phrases joined by real silences, loudness per scene
    t_video, prev_section = 0.0, None
    timeline_scenes, combined, word_file, report = [], [], {}, []
    for i, sc in enumerate(scenes):
        P, D = split_phrases(sc[cfg['text']]), split_phrases(sc['display'])
        audio, pos = [np.zeros(int(0.1 * SR), np.float32)], 0.1
        cues, cur, scene_words, ph_rows = [], None, [], []
        for k, (p, d) in enumerate(zip(P, D)):
            v = phrases[(sc['id'], k)]
            start = pos
            audio.append(v['seg'])
            pos += len(v['seg']) / SR
            if cur is None:
                cur = {'start_s': start, 'display': [], 'tts': []}
            cur['display'].append(d['text'])
            cur['tts'].append(p['text'])
            for w, s, e in v['words']:
                scene_words.append({'w': w, 'cue': len(cues), 's': s - v['cut'] + start + corr,
                                    'e': e - v['cut'] + start + corr})
            ph_rows.append({'text': v['sent'], 'start': round(start, 3), 'end': round(pos, 3),
                            'exact_words': v['exact'], 'dur': round(len(v['seg']) / SR, 3), 'gain_db': v['gain_db']})
            if p['cue_end'] or k == len(P) - 1:
                cur['end_s'] = pos
                cues.append(cur)
                cur = None
            if k < len(P) - 1:
                audio.append(np.zeros(int(p['pause'] * SR), np.float32))
                pos += p['pause']
        audio.append(np.zeros(int(0.1 * SR), np.float32))
        raw = audio_dir / 'raw' / f"{sc['id']}.wav"
        write_wav(raw, np.concatenate(audio))
        dst = audio_dir / 'scenes' / f"{sc['id']}.wav"
        lm = loudnorm(raw, dst)
        dur = len(read_wav(dst)) / SR

        lead = cfg['first_lead'] if i == 0 else float(sc.get('lead', LEAD_IN))
        if prev_section is not None and sc['section'] != prev_section:
            lead += SECTION_EXTRA
        tail = cfg['last_hold'] if i == len(scenes) - 1 else float(sc.get('tail', TAIL))
        start_f = round(t_video * fps)
        end_f = round((t_video + lead + dur + tail) * fps)
        timeline_scenes.append({
            'id': sc['id'], 'section': sc['section'], 'title': sc['title'], 'step': sc.get('step'),
            'audio': f"{cfg['audio']}/scenes/{sc['id']}.wav", 'audio_duration_s': round(dur, 3),
            'start_s': round(start_f / fps, 3), 'duration_s': round((end_f - start_f) / fps, 3),
            'start_frame': start_f, 'duration_frames': end_f - start_f,
            'voice_offset_s': round(lead, 3), 'voice_offset_frames': round(lead * fps),
            'cues': [{'start_s': round(c['start_s'] + lead, 3), 'end_s': round(c['end_s'] + lead, 3),
                      'start_frame': round((c['start_s'] + lead) * fps), 'end_frame': round((c['end_s'] + lead) * fps),
                      'text_display': ' '.join(c['display']), 'text_tts': ' '.join(c['tts'])} for c in cues]})
        word_file[sc['id']] = {
            'audio_duration_s': round(dur, 3), 'voice_offset_s': round(lead, 3), 'source': 'ElevenLabs alignment',
            'phrases': ph_rows,
            'words': [{'w': w['w'], 'cue': w['cue'], 's': round(w['s'] + lead, 3), 'e': round(w['e'] + lead, 3)}
                      for w in scene_words]}
        n_words = len(words(sc[cfg['text']]))
        speech = sum(r['dur'] for r in ph_rows)
        report.append({'id': sc['id'], 'audio_s': round(dur, 2), 'words': n_words, 'speech_s': round(speech, 2),
                       'wpm_speech': round(n_words / (speech / 60), 1), 'wpm_overall': round(n_words / (dur / 60), 1),
                       'loudness': lm, 'phrases': len(P), 'exact_word_timing': all(r['exact_words'] for r in ph_rows)})
        combined.append((start_f / fps + lead, dst))
        t_video = end_f / fps
        prev_section = sc['section']
        print(f"  [{sc['id']}] {dur:6.2f}s  {n_words:3d} words  {report[-1]['wpm_speech']:5.1f} wpm(speech)  "
              f"I={lm['integrated_lufs']} TP={lm['true_peak_dbtp']}", flush=True)

    # ---- 3. the whole narration on the video timeline, -16 LUFS
    buf = np.zeros(int(round(t_video * SR)), np.float32)
    for start, p in combined:
        y = read_wav(p)
        a = int(round(start * SR))
        buf[a:a + len(y)] += y[: max(0, len(buf) - a)]
    comb_raw = audio_dir / 'raw' / 'narration_combined_prenorm.wav'
    write_wav(comb_raw, buf)
    meas = loudnorm(comb_raw, audio_dir / 'narration_full.wav', pre='')
    if m4a:
        out_m4a = V / cfg['m4a']
        subprocess.run([FF, '-v', 'error', '-y', '-i', str(audio_dir / 'narration_full.wav'), '-c:a', 'aac',
                        '-b:a', '80k', '-movflags', '+faststart', str(out_m4a)], check=True)

    engine = {'engine': 'ElevenLabs text-to-speech with character timestamps', 'voice_id': VOICE_ID,
              'voice': 'Abdullah - Professional, and Energetic', 'model': MODEL_ID, 'output_format': 'mp3_44100_192',
              'text_field': cfg['text'], 'pauses_s': {'comma': 0.25, 'colon': 0.3, 'sentence': 0.5, 'beat': 0.9},
              'note': 'phrase-by-phrase synthesis (previous_text/next_text for continuity), silences inserted by us'}
    timeline = {'fps': fps, 'width': 1920, 'height': 1080, 'total_duration_s': round(t_video, 3),
                'total_frames': round(t_video * fps), 'narration_track': f"{cfg['audio']}/narration_full.wav",
                'tts': engine,
                'loudness': {'target': '-16 LUFS integrated, true peak <= -1.5 dBTP (EBU R128 / ffmpeg loudnorm)',
                             'measured_combined': meas},
                'asr_check': None,
                'padding_s': {'lead_in': LEAD_IN, 'tail': TAIL, 'section_extra': SECTION_EXTRA,
                              'first_lead': cfg['first_lead'], 'last_hold': cfg['last_hold']},
                'scenes': timeline_scenes}
    (V / cfg['timeline']).write_text(json.dumps(timeline, ensure_ascii=False, indent=1), encoding='utf-8')
    (audio_dir / 'word_timings.json').write_text(json.dumps(
        {'_meta': {'source': engine, 'alignment_shift_s': round(corr, 3),
                   'note': 'seconds from scene start (voice offset included)'}, **word_file},
        ensure_ascii=False, indent=1), encoding='utf-8')
    (audio_dir / 'assembly_report.json').write_text(json.dumps(
        {'engine': engine, 'scenes': report}, ensure_ascii=False, indent=1), encoding='utf-8')
    total_words = sum(r['words'] for r in report)
    print(f"{key}: TOTAL {t_video:.1f} s ({int(t_video // 60)}:{t_video % 60:04.1f}), {total_words} words, "
          f"alignment shift {corr:.3f} s, combined {meas}")


def cmd_assemble(a):
    setup()
    arts = [Path(x) for x in a.artifact]
    for key in [k.strip() for k in a.script.split(',') if k.strip()]:
        print(f'== {key}')
        assemble_script(key, arts, a.fps, not a.no_m4a)


def cmd_retry(a):
    arts = [Path(x) for x in a.artifact]
    jobs = []
    for key in [k.strip() for k in a.script.split(',') if k.strip()]:
        prefix = key.replace('-', '_')
        index = artifact_index_all(arts, prefix)
        for j in phrase_jobs(key, a.voice, MODEL_ID):
            sid = re.match(rf'^{re.escape(prefix)}_(.+?)_\d\d\.mp3$', j['out']).group(1)
            if (sid, j['text']) not in index:
                # a new name (hash of the text), so it can never be mistaken for a file of the first run
                j['out'] = j['out'][:-4] + 'r' + hashlib.sha1(j['text'].encode()).hexdigest()[:6] + '.mp3'
                jobs.append(j)
    if not jobs:
        print('nothing to redo: every phrase is in the artifact')
        return
    write_request(a.out, [{'type': 'subscription'}] + jobs, a.id or 'narration-retry')
    print(f'{a.out}: {len(jobs)} tts jobs to redo')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    r = sub.add_parser('request')
    r.add_argument('--script', default='tv,mobile', help='tv, mobile, tv-en, mobile-en (comma separated)')
    r.add_argument('--out', default=str(V / 'voice/request.json'))
    r.add_argument('--id', default='')
    r.add_argument('--voice', default=VOICE_ID)
    r.add_argument('--no-samples', action='store_true', help='leave out the English voice samples')
    r.set_defaults(fn=cmd_request)
    s = sub.add_parser('assemble')
    s.add_argument('artifact', nargs='+', help='the unpacked voice-output folder(s): the first run, then any retry runs')
    s.add_argument('--script', default='tv,mobile')
    s.add_argument('--fps', type=int, default=30)
    s.add_argument('--no-m4a', action='store_true')
    s.set_defaults(fn=cmd_assemble)
    t = sub.add_parser('retry')
    t.add_argument('artifact', nargs='+', help='the unpacked voice-output folder(s) made so far')
    t.add_argument('--script', default='tv,mobile')
    t.add_argument('--out', default=str(V / 'voice/request.json'))
    t.add_argument('--id', default='')
    t.add_argument('--voice', default=VOICE_ID)
    t.set_defaults(fn=cmd_retry)
    a = ap.parse_args()
    a.fn(a)


if __name__ == '__main__':
    main()
