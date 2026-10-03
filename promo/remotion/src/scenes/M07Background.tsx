import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {PlayerPage} from '../components/MobileScreens';
import {Callout, MobileHeader, PhoneStage, TapAt} from '../components/MobileStage';
import {LauncherPage, ScreenOff} from '../components/MobileSystem';
import {clamp, sp, tween} from '../lib/anim';
import {digits, endSide, t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// m07 — background play: the player, then the home screen (the audio goes on: a media notification), then the
// switched-off screen with the sound still running (equalizer bars beside the phone).

export const M07Background: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tPlayer = b.at('والمشغل');
  const tBg = b.at('الخلفية');
  const tKeep = b.at('يستمر');
  const tOff = b.at('مطفأة');
  const tYou = b.at('وأنت');

  const toHome = tween(f, tBg + 6, 12);
  const off = tween(f, tOff + 4, 14);
  const bars = clamp(tween(f, tKeep, 10) * 1.2);
  const time = 120 + f / 30;
  const tapHome = tBg;

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <MobileHeader kicker={t('المشغّل')} index={digits('1 / 4')} title={t('التشغيل في الخلفية{header}', {header: ''})} />
      <PhoneStage f={f} from={-60} glow={brand.primary}>
        <PlayerPage time={time} controls={tween(f, 0, 10) * (1 - tween(f, tBg - 6, 8))} />
        <div style={{position: 'absolute', inset: 0, opacity: toHome}}>
          <LauncherPage notif={sp(f, tBg + 16, {damping: 22})} />
        </div>
        <div style={{position: 'absolute', inset: 0, opacity: off}}>
          <ScreenOff />
        </div>
        <TapAt f={f} at={tapHome} x={195} y={832} />
      </PhoneStage>

      {/* the sound goes on: bars beside the phone, on the side where the callouts are not */}
      <div style={{position: 'absolute', [endSide]: 250, top: 420, width: 360, height: 220, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 12, opacity: bars}}>
        {Array.from({length: 13}).map((_, i) => {
          const h = 30 + 90 * Math.abs(Math.sin(f / 7 + i * 0.9)) * (0.5 + 0.5 * Math.sin(f / 23 + i));
          return <div key={i} style={{width: 14, height: h, borderRadius: 7, background: i % 3 === 0 ? brand.primaryBright : brand.primary, opacity: 0.55 + 0.45 * Math.abs(Math.sin(f / 9 + i))}} />;
        })}
      </div>
      <Callout f={f} at={tPlayer} icon="play" title={t('يعمل في الخلفية')} sub={t('الصوت لا ينقطع')} top={300} />
      <Callout f={f} at={tOff - 4} icon="moon" title={t('والشاشة مطفأة')} top={470} tone="amber" />
      <Callout f={f} at={tYou - 2} icon="phone" title={t('وأنت تفعل ما تشاء')} top={640} />
    </div>
  );
};
