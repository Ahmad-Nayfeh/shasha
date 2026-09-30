import React from 'react';
import {brand, rgba} from '../brand';
import {clamp} from '../lib/anim';

// Laptop (front view) + terminal window in IBM Plex Mono, with typed commands and output lines that
// scroll. Arabic output lines are shaped RTL inside the left-aligned terminal (muted colour).

export type TermLine = {t: number; text: string; kind?: 'cmd' | 'say' | 'info' | 'ar' | 'ok' | 'dim' | 'blank' | 'warn' | 'plain'; typeRate?: number};

const ARABIC = /[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]/;

/**
 * Arabic output lines contain Latin phrases ("Allow USB debugging?", 192.168.1.50:5555, adb, OK): each Latin run is
 * shown as an LTR isolate so its own punctuation stays with it (the "?" after "debugging") while the quotes, brackets
 * and the sentence's final punctuation around it follow the Arabic (RTL) line.
 */
export const isolateLatin = (text: string): React.ReactNode[] => {
  const out: React.ReactNode[] = [];
  let buf = '';
  let i = 0;
  while (i < text.length) {
    if (/[A-Za-z0-9]/.test(text[i])) {
      let j = i;
      while (j < text.length && !ARABIC.test(text[j])) j++;
      const run = text.slice(i, j);
      const tail = run.match(/[\s(\[«»"'.…،:؛]*$/)?.[0] ?? '';
      if (buf) out.push(buf);
      buf = tail;
      out.push(
        <bdi key={i} dir="ltr" style={{unicodeBidi: 'isolate', direction: 'ltr'}}>
          {run.slice(0, run.length - tail.length)}
        </bdi>,
      );
      i = j;
    } else {
      buf += text[i];
      i++;
    }
  }
  if (buf) out.push(buf);
  return out;
};

const ARABIC_RUN = /[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]+(?:[ \u00A0]+[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]+)*[:.،…]*/g;

/**
 * The reverse case, in the English (LTR) output lines: an Arabic label with its punctuation ("الصلاحيات:",
 * "للإعادة:") is shown as an RTL isolate so its colon stays at the end (left) of the Arabic words.
 */
export const isolateArabic = (text: string): React.ReactNode[] => {
  const out: React.ReactNode[] = [];
  let last = 0;
  for (const m of text.matchAll(ARABIC_RUN)) {
    const i = m.index ?? 0;
    if (i > last) out.push(text.slice(last, i));
    out.push(
      <bdi key={i} dir="rtl" style={{unicodeBidi: 'isolate', direction: 'rtl'}}>
        {m[0]}
      </bdi>,
    );
    last = i + m[0].length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
};

const LINE_H = 30;
const MAX_LINES = 17;

export const Terminal: React.FC<{
  f: number;
  lines: TermLine[];
  prompt?: string;
  tab?: 'bash' | 'cmd';
  width: number;
  height: number;
}> = ({f, lines, prompt = '~/shasha $ ', tab = 'bash', width, height}) => {
  const shown = lines.filter((l) => f >= l.t);
  const n = shown.length;
  const extra = Math.max(0, n - MAX_LINES);
  // smooth scroll: ease the last line in
  const last = shown[n - 1];
  const scrollT = last ? clamp((f - last.t) / 5) : 1;
  const offset = (extra - (n > MAX_LINES ? 1 - scrollT : 0)) * LINE_H;
  const cursorOn = Math.floor(f / 15) % 2 === 0;
  return (
    <div style={{position: 'absolute', left: 0, top: 0, width, height, background: '#05080B', overflow: 'hidden', fontFamily: brand.mono, direction: 'ltr'}}>
      {/* title bar + tabs */}
      <div style={{height: 46, display: 'flex', alignItems: 'center', gap: 10, padding: '0 16px', background: '#0E1318', borderBottom: '1px solid rgba(234,240,246,0.06)'}}>
        {['#3A4452', '#3A4452', '#3A4452'].map((c, i) => (
          <div key={i} style={{width: 13, height: 13, borderRadius: 99, background: c}} />
        ))}
        <div style={{display: 'flex', gap: 6, marginLeft: 18}}>
          {(['bash', 'cmd'] as const).map((k) => (
            <div
              key={k}
              style={{
                padding: '6px 16px',
                borderRadius: 8,
                fontSize: 16,
                color: tab === k ? brand.text : brand.faint,
                background: tab === k ? '#1A222C' : 'transparent',
              }}
            >
              {k === 'bash' ? 'bash — macOS / Linux' : 'cmd — Windows'}
            </div>
          ))}
        </div>
      </div>
      <div style={{position: 'absolute', left: 22, right: 22, top: 62, bottom: 12, overflow: 'hidden'}}>
        <div style={{transform: `translateY(${-offset}px)`}}>
          {shown.map((l, i) => {
            const isLast = i === n - 1;
            let text = l.text;
            if (l.kind === 'cmd') {
              const k = clamp(Math.floor((f - l.t) * (l.typeRate ?? 1)), 0, l.text.length);
              text = l.text.slice(0, k);
            }
            const typing = l.kind === 'cmd' && text.length < l.text.length;
            const color =
              l.kind === 'cmd'
                ? brand.text
                : l.kind === 'say'
                  ? '#F1F5F9'
                  : l.kind === 'ok'
                    ? brand.primaryBright
                    : l.kind === 'ar'
                      ? brand.muted
                      : l.kind === 'warn'
                        ? brand.amber
                        : l.kind === 'dim'
                          ? brand.faint
                          : '#B8C3D0';
            return (
              <div
                key={i}
                style={{
                  height: LINE_H,
                  lineHeight: `${LINE_H}px`,
                  fontSize: 19,
                  whiteSpace: 'pre',
                  color,
                  fontWeight: l.kind === 'say' ? 600 : 400,
                  opacity: l.kind === 'cmd' ? 1 : clamp((f - l.t) / 3),
                  ...(l.kind === 'ar' ? {direction: 'rtl', unicodeBidi: 'plaintext', textAlign: 'left', fontFamily: brand.font, fontSize: 18, paddingLeft: 46} : {}),
                }}
              >
                {l.kind === 'cmd' ? <span style={{color: brand.primary}}>{prompt}</span> : null}
                {l.kind === 'ar' ? isolateLatin(text.trim()) : l.kind === 'cmd' ? text : isolateArabic(text)}
                {(typing || (isLast && l.kind === 'cmd')) && cursorOn ? (
                  <span style={{display: 'inline-block', width: 11, height: 22, background: brand.primary, verticalAlign: 'middle', marginLeft: 2}} />
                ) : null}
              </div>
            );
          })}
          {/* idle prompt with blinking cursor when nothing is typing */}
          {!shown.length || (last && last.kind !== 'cmd' && f - last.t > 40) ? null : null}
        </div>
      </div>
    </div>
  );
};

/** Laptop front view; children = the screen content (width x height px). */
export const Laptop: React.FC<{x: number; y: number; w: number; children?: React.ReactNode; opacity?: number}> = ({x, y, w, children, opacity = 1}) => {
  const bz = w * 0.022;
  const sw = w - bz * 2;
  const sh = sw * 0.625;
  const lidH = sh + bz * 2;
  return (
    <div style={{position: 'absolute', left: x, top: y, width: w, height: lidH + w * 0.05, opacity}}>
      {/* glow on the desk/wall */}
      <div style={{position: 'absolute', left: -w * 0.2, right: -w * 0.2, top: -lidH * 0.2, height: lidH * 1.4, background: `radial-gradient(50% 50% at 50% 50%, ${rgba(brand.primary, 0.08)}, transparent 70%)`}} />
      {/* lid */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          top: 0,
          width: w,
          height: lidH,
          borderRadius: w * 0.028,
          background: 'linear-gradient(180deg, #1A2129, #0B0F13)',
          boxShadow: `0 30px 70px rgba(0,0,0,0.55), inset 0 0 0 1.5px rgba(255,255,255,0.08)`,
        }}
      />
      <div style={{position: 'absolute', left: bz, top: bz, width: sw, height: sh, borderRadius: w * 0.008, overflow: 'hidden', background: '#05080B'}}>
        {children}
        <div style={{position: 'absolute', inset: 0, background: 'linear-gradient(120deg, rgba(255,255,255,0.05), rgba(255,255,255,0) 35%)', pointerEvents: 'none'}} />
      </div>
      <div style={{position: 'absolute', left: '50%', top: bz * 0.35, width: 6, height: 6, borderRadius: 99, transform: 'translateX(-50%)', background: '#1D252E'}} />
      {/* base */}
      <div
        style={{
          position: 'absolute',
          left: -w * 0.06,
          width: w * 1.12,
          top: lidH - 2,
          height: w * 0.028,
          borderRadius: `0 0 ${w * 0.03}px ${w * 0.03}px`,
          background: 'linear-gradient(180deg, #2B3440 0%, #161C23 60%, #0B0F13 100%)',
          boxShadow: '0 16px 30px rgba(0,0,0,0.5)',
        }}
      />
      <div style={{position: 'absolute', left: '50%', top: lidH - 2, width: w * 0.16, height: w * 0.01, transform: 'translateX(-50%)', borderRadius: `0 0 ${w * 0.01}px ${w * 0.01}px`, background: '#0B0F13'}} />
    </div>
  );
};

export const laptopScreen = (w: number) => {
  const bz = w * 0.022;
  const sw = w - bz * 2;
  return {bz, sw, sh: sw * 0.625};
};
