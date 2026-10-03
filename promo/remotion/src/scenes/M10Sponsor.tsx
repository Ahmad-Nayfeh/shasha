import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {PlayerPage} from '../components/MobileScreens';
import {Callout, MobileHeader, PhoneStage, SidePill} from '../components/MobileStage';
import {easeInOut, keys, tween} from '../lib/anim';
import {digits, t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// m10 — sponsor segments: the green stretch on the seek bar is a paid promotion inside the video; the player jumps
// over it by itself and says so (with «تراجع» to go back).

const SEG: [number, number] = [214, 262]; // seconds, in a 12:34 video

export const M10Sponsor: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tSkip = b.at('وتتخطى');
  const tSeg = b.at('فقرات');
  const tAlone = b.at('وحدها');
  const tAds = b.at('الإعلانات');
  const tInside = b.at('داخله');

  // the playhead approaches the segment, then jumps over it
  const time = keys(f, [[0, 196], [tAlone - 6, 213], [tAlone, 214], [tAlone + 8, SEG[1] + 1], [tAlone + 160, SEG[1] + 6]], easeInOut);
  const hi = tween(f, tSeg - 4, 12) * (1 - tween(f, tAlone + 30, 14) * 0.6);
  const toast = tween(f, tAlone + 8, 10) * (1 - tween(f, tAlone + 70, 12));

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <MobileHeader kicker={t('المشغّل')} index={digits('4 / 4')} title={t('تخطي فقرات الرعاية')} />
      <PhoneStage f={f} from={-60} glow={brand.primary}>
        <PlayerPage time={time} sponsor={SEG} sponsorHi={hi} toast={toast} controls={1} />
      </PhoneStage>

      <Callout f={f} at={tSkip - 2} icon="skip" title={t('يتخطاها وحده')} sub={t('فقرات الرعاية')} top={300} />
      <SidePill f={f} at={tAds - 6} icon="tag" label={t('إعلان داخل الفيديو')} top={470} crossAt={tInside + 6} size={36} />
    </div>
  );
};
