import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {LogoMark, Wordmark, WORDMARK_RATIO} from '../components/Brand';
import {Icon} from '../components/Icons';
import {WordReveal} from '../components/ui';
import {clamp, easeInOut, mix, sp, tween} from '../lib/anim';
import {beatsFor, Scene} from '../lib/timeline';

// End card (the TV video's s31 and the phone video's m15): the logo in the centre, dots floating gently;
// «صُنع بحب لعائلتي» word by word on the voice, a heart, then the author's name; non-affiliation line; fade out.

export const S16Outro: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const D = scene.duration_frames;
  const markIn = sp(f, 0, {damping: 20, stiffness: 70});
  const dot = (i: number) => {
    const s = sp(f, 8 + i * 6, {damping: 14, stiffness: 110});
    return {dy: (1 - s) * -120 + Math.sin((f / 30) * Math.PI + i * 1.1) * 7, opacity: clamp(s * 2)};
  };
  const wm = tween(f, 14, 22);
  const fadeOut = tween(f, D - 32, 30, easeInOut);
  const markW = 230;
  const S = (markW / 751) * 1024;
  const cx = 960;
  const cy = 330;
  const wmW = 280;
  const lineTop = 520 + wmW * WORDMARK_RATIO + 24;

  const words = ['صُنع', 'بحب', 'لعائلتي'];
  const starts = [b.at('صنع'), b.at('بحب'), b.at('لعائلتي')].map((x) => x - 3);
  const tName = b.at('أحمد') - 4;
  const heart = sp(f, starts[2] + 6, {damping: 12, stiffness: 120});
  const beat = 1 + 0.08 * Math.max(0, Math.sin((f - starts[2]) / 6)) * clamp(heart);
  const nameIn = tween(f, tName, 18);

  return (
    <AbsoluteFill style={{opacity: 1 - fadeOut}}>
      <div style={{position: 'absolute', left: cx - 512 * (S / 1024), top: cy - 651 * (S / 1024), width: S, height: S, transform: `scale(${mix(0.9, 1, markIn)})`, opacity: clamp(markIn * 1.4)}}>
        <LogoMark size={S} dots={[dot(0), dot(1), dot(2)]} />
      </div>
      <div style={{position: 'absolute', left: cx - wmW / 2, top: 520, opacity: wm, transform: `translateY(${(1 - wm) * 20}px)`}}>
        <Wordmark width={wmW} />
      </div>
      <WordReveal
        words={words}
        starts={starts}
        style={{position: 'absolute', left: 0, right: 0, top: lineTop, textAlign: 'center', fontSize: 64, fontWeight: 600, color: brand.text}}
        wordStyle={(i) => (i === 2 ? {color: brand.primaryBright} : {})}
      />
      <div
        style={{
          position: 'absolute',
          left: cx - 40,
          top: lineTop + 104,
          width: 80,
          height: 80,
          opacity: clamp(heart * 1.5),
          transform: `scale(${mix(0.4, 1, clamp(heart)) * beat})`,
          filter: `drop-shadow(0 0 22px ${rgba(brand.amber, 0.5)})`,
        }}
      >
        <Icon name="heart" size={80} color={brand.amber} stroke={2.2} />
      </div>
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: lineTop + 210,
          textAlign: 'center',
          fontSize: 76,
          fontWeight: 700,
          color: brand.primaryBright,
          direction: 'rtl',
          opacity: nameIn,
          transform: `translateY(${(1 - nameIn) * 18}px)`,
        }}
      >
        احمد نايفه
      </div>
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          bottom: 70,
          textAlign: 'center',
          fontSize: 26,
          fontWeight: 500,
          color: brand.muted,
          opacity: 0.8 * tween(f, b.at('نايفه') + 10, 20),
          direction: 'rtl',
        }}
      >
        مشروع شخصي — غير تابع لـ YouTube أو Google
      </div>
    </AbsoluteFill>
  );
};
