import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {Icon} from '../components/Icons';
import {Remote} from '../components/Remote';
import {fr, Screen, TvFrame} from '../components/Tv';
import {atEnd, atStart, Chip, featureTv, FocusRing, Header, mx, stepOf} from '../components/ui';
import {clamp, mix, sp, tween} from '../lib/anim';
import {FEATURE_TV} from '../lib/layout';
import {dir, flowSign, isEn, t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// s10 — voice search: press the microphone (on the screen or on the remote), say what you want in Arabic or in
// English, and the results appear. Real captures: the idle search page, the "I'm listening" card, the results.

const TV = featureTv();

export const S10Search: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tSearch = b.at('البحث');
  const tMic = b.at('زر الميكروفون');
  const tSay = b.at('وقل');
  const tAr = b.at('بالعربية');
  const tEn = b.at('بالإنجليزية');
  const tRes = b.at('فتظهر');

  const tvIn = sp(f, 0, {damping: 24, stiffness: 90});
  const press = tMic + 10;
  const listening = tween(f, tSay - 10, 10) * (1 - tween(f, tRes - 8, 12));
  const results = tween(f, tRes - 4, 14);
  const remoteIn = sp(f, tSearch - 6, {damping: 22});

  // the two languages of the voice search, lit in turn
  const arOn = tween(f, tAr - 2, 8) * (1 - tween(f, tEn - 2, 8));
  const enOn = tween(f, tEn - 2, 8);

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker={t('المشاهدة')} index={stepOf(2, 3)} title={t('البحث بالصوت')} />

      <TvFrame x={TV.x + (1 - tvIn) * -60 * flowSign} y={TV.y} w={TV.w} opacity={clamp(tvIn * 1.4)}>
        <Screen name="search_mic" />
        <Screen name="search_listening" opacity={listening} />
        <Screen name="search_adult" opacity={results} />
        <FocusRing track={[{f: tMic - 4, rect: fr('search_mic', 'mic'), r: 63}]} presses={[press]} until={press + 10} width={6} />
      </TvFrame>

      {/* the remote's microphone button */}
      <Remote x={mx(1590, 150 * 0.95)} y={290} scale={0.95} rotate={-8 * flowSign} opacity={clamp(remoteIn * 1.5)} presses={[{btn: 'mic', f: press}]} />

      {/* the two languages */}
      <div style={{position: 'absolute', ...atStart(90), top: 880, display: 'flex', gap: 22, direction: dir, opacity: tween(f, tAr - 10, 12)}}>
        <Chip icon="mic" label={t('العربية')} size={30} t={1} tone={arOn > 0.5 ? 'teal' : 'neutral'} dim={1 - Math.max(arOn, 0.35)} />
        <Chip icon="mic" label="English" size={30} t={1} tone={enOn > 0.5 ? 'teal' : 'neutral'} dim={1 - Math.max(enOn, 0.35)} mono={!isEn} />
      </div>
      <div
        style={{
          position: 'absolute',
          ...atEnd(FEATURE_TV.x),
          top: TV.y - 74,
          opacity: tween(f, tSay, 14) * (1 - results),
          transform: `translateY(${(1 - tween(f, tSay, 14)) * 10}px)`,
        }}
      >
        <Chip icon="mic" label={t('تكلّم الآن…')} tone="teal" size={24} />
      </div>
      <div style={{position: 'absolute', ...atEnd(FEATURE_TV.x + FEATURE_TV.w - 260), top: TV.y - 74, opacity: results, transform: `translateY(${(1 - results) * 10}px)`}}>
        <div
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 10,
            padding: '8px 22px 10px',
            borderRadius: 99,
            background: rgba(brand.primary, 0.14),
            border: `1.5px solid ${rgba(brand.primary, 0.5)}`,
            fontSize: 24,
            fontWeight: 600,
            color: brand.primaryBright,
            direction: dir,
          }}
        >
          <Icon name="check" size={24} color={brand.primaryBright} stroke={3} />
          {t('النتائج')}
        </div>
      </div>
    </div>
  );
};
