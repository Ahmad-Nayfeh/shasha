import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {LogoMark, Wordmark, WORDMARK_RATIO} from '../components/Brand';
import {WordReveal} from '../components/ui';
import {clamp, easeInOut, mix, sp, tween} from '../lib/anim';
import {beatsFor, Scene} from '../lib/timeline';

// s16 — end card: the logo back in the centre, dots floating gently; the tagline word by word on the voice;
// non-affiliation line; fade to the background.

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
  const markW = 250;
  const S = (markW / 751) * 1024;
  const cx = 960;
  const cy = 360;
  const wmW = 300;

  const words = ['يوتيوب', 'على', 'التلفاز،', 'بهدوء،', 'وبنيّة', 'واضحة.'];
  const starts = [b.at('يوتيوب'), b.at('على'), b.at('التلفاز'), b.at('بهدوء'), b.at('وبنية'), b.at('واضحة')].map((x) => x - 3);

  return (
    <AbsoluteFill style={{opacity: 1 - fadeOut}}>
      <div style={{position: 'absolute', left: cx - 512 * (S / 1024), top: cy - 651 * (S / 1024), width: S, height: S, transform: `scale(${mix(0.9, 1, markIn)})`, opacity: clamp(markIn * 1.4)}}>
        <LogoMark size={S} dots={[dot(0), dot(1), dot(2)]} />
      </div>
      <div style={{position: 'absolute', left: cx - wmW / 2, top: 560, opacity: wm, transform: `translateY(${(1 - wm) * 20}px)`}}>
        <Wordmark width={wmW} />
      </div>
      <WordReveal
        words={words}
        starts={starts}
        style={{position: 'absolute', left: 0, right: 0, top: 560 + wmW * WORDMARK_RATIO + 40, textAlign: 'center', fontSize: 52, fontWeight: 500, color: brand.text}}
        wordStyle={(i) => (i >= 3 ? {color: brand.primaryBright} : {})}
      />
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
          opacity: 0.8 * tween(f, b.at('واضحة') + 10, 20),
          direction: 'rtl',
        }}
      >
        مشروع شخصي — غير تابع لـ YouTube أو Google
      </div>
    </AbsoluteFill>
  );
};
