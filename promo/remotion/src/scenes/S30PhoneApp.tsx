import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {Icon} from '../components/Icons';
import {HomePage} from '../components/MobileScreens';
import {thumbBg} from '../components/MobileUi';
import {Callout, PhoneStage} from '../components/MobileStage';
import {atEnd, atStart, Header} from '../components/ui';
import {clamp, mix, sp, tween} from '../lib/anim';
import {dir, flowSign, t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// s30 — the phone app, in one sentence: its own app for an Android phone, and its own video.

export const S30PhoneApp: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tPhone = b.at('ولجوالك');
  const tApp = b.at('تطبيق');
  const tVideo = b.at('وله');
  const tExplain = b.at('يشرحه');

  const card = sp(f, tVideo - 4, {damping: 22});
  const play = Math.max(0, Math.sin(clamp((f - tExplain) / 30) * Math.PI));
  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker={t('وللجوال أيضاً')} title={t('تطبيق «شاشة» للأندرويد')} />
      <PhoneStage f={f} from={tPhone - 6} glow={brand.primary}>
        <HomePage scroll={tween(f, tApp, 200) * 160} />
      </PhoneStage>

      <Callout f={f} at={tApp - 4} icon="phone" title={t('تطبيق «شاشة»')} sub={t('لجوال أندرويد')} top={320} />

      {/* its own video (on the left in Arabic, on the right in English) */}
      <div style={{position: 'absolute', ...atEnd(120), top: 360, width: 560, opacity: clamp(card * 1.4), transform: `translateX(${(1 - card) * -60 * flowSign}px) scale(${mix(0.95, 1, card)})`}}>
        <div style={{position: 'relative', width: 560, height: 315, borderRadius: 24, overflow: 'hidden', background: thumbBg(4), boxShadow: `0 30px 70px rgba(0,0,0,0.5), 0 0 0 2px ${rgba(brand.primary, 0.45)}`}}>
          <div style={{position: 'absolute', left: '50%', top: '50%', width: 108, height: 108, borderRadius: 999, transform: `translate(-50%, -50%) scale(${1 + play * 0.12})`, background: rgba(brand.bg, 0.6), border: `3px solid ${brand.primaryBright}`, display: 'grid', placeItems: 'center', boxShadow: `0 0 ${40 * (0.4 + play)}px ${rgba(brand.primary, 0.5)}`}}>
            <Icon name="play" size={52} color={brand.primaryBright} stroke={2.4} />
          </div>
          <div style={{position: 'absolute', left: 0, right: 0, bottom: 0, height: 70, background: 'linear-gradient(180deg, transparent, rgba(0,0,0,0.7))'}} />
          <div style={{position: 'absolute', ...atStart(22), bottom: 16, fontSize: 30, fontWeight: 700, direction: dir, fontFamily: brand.font}}>{t('فيديو تطبيق الجوال')}</div>
        </div>
      </div>
    </div>
  );
};
