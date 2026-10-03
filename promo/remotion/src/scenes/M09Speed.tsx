import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {PlayerPage, SpeedSheet} from '../components/MobileScreens';
import {Callout, MobileHeader, PhoneStage, TapAt} from '../components/MobileStage';
import {sp, tween} from '../lib/anim';
import {digits, isEn, t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// m09 — playback speed: the player's settings sheet, slower to follow, faster to save time.

export const M09Speed: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tSpeed = b.at('سرعة');
  const tSlow = b.at('أبطأ');
  const tFast = b.at('أسرع');

  // the sheet closes and the «1.5×» badge shows a little sooner in English (a shorter sentence)
  const tClose = tFast + (isEn ? 28 : 40);
  const tBadge = tFast + (isEn ? 18 : 30);
  const sheet = sp(f, tSpeed - 6, {damping: 22}) * (1 - tween(f, tClose, 12));
  const sel = f >= tFast ? 4 : f >= tSlow ? 1 : 2;
  const label = f >= tBadge ? '1.5×' : undefined;

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <MobileHeader kicker={t('المشغّل')} index={digits('3 / 4')} title={t('السرعة')} />
      <PhoneStage f={f} from={-60} glow={brand.primary}>
        <PlayerPage time={200 + f / 30 * (f >= tFast ? 1.5 : f >= tSlow ? 0.75 : 1)} speed={label} speedGlow={label ? tween(f, tBadge, 10) : 0} />
        <div style={{position: 'absolute', inset: 0}}>
          <SpeedSheet sel={sel} t={sheet} />
        </div>
        <TapAt f={f} at={tSpeed - 8} x={isEn ? 366 : 34} y={64} />
        <TapAt f={f} at={tSlow + 2} x={195} y={591} />
        <TapAt f={f} at={tFast + 2} x={195} y={741} />
      </PhoneStage>

      <Callout f={f} at={tSpeed - 4} icon="gauge" title={t('سرعة التشغيل')} top={300} />
      <Callout f={f} at={tSlow - 2} icon="back" title={t('أبطأ لتفهم')} sub={t('٠٫٧٥×')} top={470} tone="amber" />
      <Callout f={f} at={tFast - 2} icon="skip" title={t('أسرع لتوفّر وقتك')} sub={t('١٫٥×')} top={640} />
    </div>
  );
};
