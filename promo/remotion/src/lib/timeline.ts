// Timing model. Everything is derived from ../data/timeline.json (scene windows + sentence cues, generated
// from the real narration) and ../data/words.json (word timings from tools/align_words.py). Nothing is
// hard-coded in frames: scenes ask for beats by the *spoken* words (cue.text_tts).
import timelineJson from '../data/timeline.json';
import wordsJson from '../data/words.json';
import wordsMobileJson from '../data/words-mobile.json';

export const FPS = 30;

export type Cue = {
  start_s: number;
  end_s: number;
  start_frame: number;
  end_frame: number;
  text_display: string | null;
  text_tts: string;
};
export type Scene = {
  id: string;
  section: string;
  title: string;
  audio: string;
  audio_duration_s: number;
  start_s: number;
  duration_s: number;
  start_frame: number;
  duration_frames: number;
  voice_offset_s: number;
  voice_offset_frames: number;
  cues: Cue[];
};
type Timeline = {fps: number; width: number; height: number; total_frames: number; scenes: Scene[]};

export const timeline = timelineJson as unknown as Timeline;
export const scenes = timeline.scenes;
export const sceneById = (id: string): Scene => {
  const s = scenes.find((x) => x.id === id);
  if (!s) throw new Error('Unknown scene ' + id);
  return s;
};

// Every cue carries its own text_display (narration/scenes.json → tts/scripts/synth_narration.py); the spoken text is
// only a fallback.
export const cueDisplay = (scene: Scene, i: number): string =>
  scene.cues[i].text_display ?? scene.cues[i].text_tts;

// --- word timings --------------------------------------------------------------------------------------

const DIAC = /[ؐ-ًؚ-ٰٟۖ-ۭـ]/g;
export const normWord = (w: string) =>
  w
    .normalize('NFKC')
    .replace(DIAC, '')
    .replace(/[إأآٱ]/g, 'ا')
    .replace(/ى/g, 'ي')
    .replace(/ة/g, 'ه')
    .replace(/ؤ/g, 'و')
    .replace(/ئ/g, 'ي')
    .replace(/[^\p{L}\p{N}]/gu, '')
    .toLowerCase();

export type Word = {w: string; cue: number; s: number; e: number}; // seconds from scene start
type WordsFile = Record<string, {audio_duration_s: number; words: Word[]}>;
// scene ids are unique across both videos (s.. = the TV video, m.. = the phone video), so one lookup serves both
const wordsFile = {...(wordsJson as unknown as WordsFile), ...(wordsMobileJson as unknown as WordsFile)};

const proportionalWords = (scene: Scene): Word[] => {
  const out: Word[] = [];
  scene.cues.forEach((c, ci) => {
    const toks = c.text_tts.split(/\s+/).filter((t) => normWord(t));
    const total = toks.reduce((a, t) => a + t.length + 1, 0);
    let acc = 0;
    for (const t of toks) {
      const s = c.start_s + ((c.end_s - c.start_s) * acc) / total;
      acc += t.length + 1;
      const e = c.start_s + ((c.end_s - c.start_s) * acc) / total;
      out.push({w: t, cue: ci, s, e});
    }
  });
  return out;
};

const wordCache = new Map<string, Word[]>();
export const sceneWords = (scene: Scene): Word[] => {
  const hit = wordCache.get(scene.id);
  if (hit) return hit;
  const d = wordsFile[scene.id];
  const fresh = d && Math.abs(d.audio_duration_s - scene.audio_duration_s) < 0.02;
  const ws = fresh ? d.words : proportionalWords(scene);
  wordCache.set(scene.id, ws);
  return ws;
};

export type Beats = {
  /** frame (scene-relative) where the spoken phrase starts */
  at: (phrase: string, occurrence?: number) => number;
  /** frame (scene-relative) where the spoken phrase ends */
  end: (phrase: string, occurrence?: number) => number;
  cue: (i: number) => {from: number; to: number};
  voice: number;
  duration: number;
};

export const beatsFor = (scene: Scene): Beats => {
  const ws = sceneWords(scene);
  const nw = ws.map((x) => normWord(x.w));
  const find = (phrase: string, occurrence = 0) => {
    const toks = phrase.split(/\s+/).map(normWord).filter(Boolean);
    let count = 0;
    for (let i = 0; i + toks.length <= nw.length; i++) {
      if (toks.every((t, k) => nw[i + k] === t)) {
        if (count === occurrence) return {i, n: toks.length};
        count++;
      }
    }
    throw new Error(`beat "${phrase}" not found in ${scene.id}`);
  };
  return {
    at: (p, o = 0) => Math.round(ws[find(p, o).i].s * FPS),
    end: (p, o = 0) => {
      const {i, n} = find(p, o);
      return Math.round(ws[i + n - 1].e * FPS);
    },
    cue: (i) => ({from: scene.cues[i].start_frame, to: scene.cues[i].end_frame}),
    voice: scene.voice_offset_frames,
    duration: scene.duration_frames,
  };
};

// --- captions ------------------------------------------------------------------------------------------

// Split after sentence/clause punctuation that is followed by a space (so "adb-setup.sh" stays whole).
const splitSegments = (t: string) =>
  t
    .split(/(?<=[،,.:؛!?؟])\s+/)
    .map((s) => s.trim())
    .filter(Boolean);

export type CaptionChunk = {text: string; from: number; to: number};
const MAX_CHARS = 54;

const captionCache = new Map<string, CaptionChunk[]>();
export const captionChunks = (scene: Scene): CaptionChunk[] => {
  const hit = captionCache.get(scene.id);
  if (hit) return hit;
  const ws = sceneWords(scene);
  type Seg = {text: string; s: number; e: number; end: boolean; cue: number};
  const segs: Seg[] = [];
  scene.cues.forEach((c, ci) => {
    const disp = cueDisplay(scene, ci);
    const cueWords = ws.filter((w) => w.cue === ci);
    const dSegs = splitSegments(disp);
    const tSegs = splitSegments(c.text_tts);
    const tCounts = tSegs.map((s) => s.split(/\s+/).filter((t) => normWord(t)).length);
    const total = tCounts.reduce((a, b) => a + b, 0);
    if (dSegs.length === tSegs.length && total === cueWords.length && cueWords.length > 0) {
      let k = 0;
      dSegs.forEach((d, i) => {
        const first = cueWords[k];
        const last = cueWords[k + tCounts[i] - 1];
        k += tCounts[i];
        segs.push({text: d, s: first.s, e: last.e, end: i === dSegs.length - 1 || /[.؟!:]$/.test(d), cue: ci});
      });
    } else {
      segs.push({text: disp, s: cueWords[0]?.s ?? c.start_s, e: cueWords.at(-1)?.e ?? c.end_s, end: true, cue: ci});
    }
  });
  // Greedy merge of short clauses into readable chunks.
  const merged: Seg[] = [];
  for (const s of segs) {
    const prev = merged.at(-1);
    if (prev && prev.cue === s.cue && !prev.end && prev.text.length + 1 + s.text.length <= MAX_CHARS) {
      prev.text += ' ' + s.text;
      prev.e = s.e;
      prev.end = s.end;
    } else if (prev && prev.cue === s.cue && prev.end && prev.text.length + 1 + s.text.length <= 34 && s.s - prev.e < 0.5) {
      prev.text += ' ' + s.text;
      prev.e = s.e;
      prev.end = s.end;
    } else merged.push({...s});
  }
  // Very long single clauses: split at the middle word (timing proportional).
  const final: Seg[] = [];
  for (const s of merged) {
    if (s.text.length <= MAX_CHARS + 8) {
      final.push(s);
      continue;
    }
    const words = s.text.split(' ');
    let best = 1;
    let bestD = 1e9;
    let acc = 0;
    const tot = s.text.length;
    for (let i = 1; i < words.length; i++) {
      acc += words[i - 1].length + 1;
      const d = Math.abs(acc - tot / 2) - (/[،,:]$/.test(words[i - 1]) ? 8 : 0);
      if (d < bestD) {
        bestD = d;
        best = i;
      }
    }
    const a = words.slice(0, best).join(' ');
    const b = words.slice(best).join(' ');
    const mid = s.s + ((s.e - s.s) * a.length) / tot;
    final.push({text: a, s: s.s, e: mid, end: false, cue: s.cue}, {text: b, s: mid, e: s.e, end: s.end, cue: s.cue});
  }
  const out: CaptionChunk[] = final.map((s) => ({
    text: s.text,
    from: Math.max(0, Math.round(s.s * FPS) - 4),
    to: Math.round(s.e * FPS) + 10,
  }));
  for (let i = 0; i < out.length - 1; i++) {
    // hold the previous chunk until the next one if the gap is short (no flicker)
    if (out[i + 1].from - out[i].to < 18) out[i].to = out[i + 1].from;
  }
  captionCache.set(scene.id, out);
  return out;
};
