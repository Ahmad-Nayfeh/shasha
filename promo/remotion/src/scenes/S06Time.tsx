import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {Icon} from '../components/Icons';
import {fr, Screen, TvFrame} from '../components/Tv';
import {atEnd, atStart, Chip, featureTv, FocusRing, Header, stepOf} from '../components/ui';
import {clamp, easeInOut, keys, mix, sp, tween} from '../lib/anim';
import {flowSign, isEn, t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// s06 — daily quota per profile + allowed schedule; a quiet 5-minute warning; the time-up screen that
// only opens with the PIN (or from the phone).

const TV = featureTv();
const RING = {cx: 1592, cy: 400, r: 118, w: 16}; // Arabic layout; atEnd() mirrors it in English

const DayBar: React.FC<{t: number; f: number}> = ({t: k, f}) => {
  // 24 h, RTL: 00:00 at the right → 24:00 at the left (left → right in English). Allowed 07:00–20:00 (the capture: «من 7:00 ص إلى 8:00 م»).
  const W = 470;
  const x = (h: number) => (isEn ? (h / 24) * W : W - (h / 24) * W);
  const now = 17.5;
  return (
    <div style={{position: 'relative', width: W, height: 120, opacity: clamp(k * 1.4), transform: `translateY(${(1 - k) * 16}px)`}}>
      <div style={{position: 'absolute', left: 0, top: 38, width: W, height: 26, borderRadius: 13, background: '#1A212B', overflow: 'hidden'}}>
        {/* night hours */}
        <div style={{position: 'absolute', left: Math.min(x(24), x(20)), width: Math.abs(x(20) - x(24)), top: 0, bottom: 0, background: 'repeating-linear-gradient(135deg, #10151C 0 8px, #151B24 8px 16px)'}} />
        <div style={{position: 'absolute', left: Math.min(x(7), x(0)), width: Math.abs(x(0) - x(7)), top: 0, bottom: 0, background: 'repeating-linear-gradient(135deg, #10151C 0 8px, #151B24 8px 16px)'}} />
        {/* allowed */}
        <div
          style={{
            position: 'absolute',
            left: Math.min(x(7), mix(x(7), x(20), k)),
            width: Math.abs(x(7) - x(20)) * k,
            top: 0,
            bottom: 0,
            background: `linear-gradient(${isEn ? 90 : 270}deg, ${brand.primary}, ${brand.primaryDeep})`,
            opacity: 0.9,
          }}
        />
      </div>
      {/* now marker */}
      <div style={{position: 'absolute', left: x(now) - 2, top: 28, width: 4, height: 46, borderRadius: 2, background: brand.text, opacity: tween(f, 0, 1) * k}} />
      {[
        [7, '07:00'],
        [20, '20:00'],
      ].map(([h, l]) => (
        <div key={l as string} style={{position: 'absolute', left: x(h as number) - 50, width: 100, top: 72, textAlign: 'center', fontSize: 24, fontWeight: 600, color: brand.text, direction: 'ltr'}}>
          {l as string}
        </div>
      ))}
      <div style={{position: 'absolute', ...atStart(0), top: 0, display: 'flex', alignItems: 'center', gap: 8, fontSize: 22, color: brand.muted}}>
        <Icon name="moon" size={24} color={brand.muted} />
        {t('ممنوع ليلاً')}
      </div>
      <div style={{position: 'absolute', ...atEnd(0), top: 0, fontSize: 22, color: brand.muted}}>{t('الأوقات المسموحة')}</div>
    </div>
  );
};

export const S06Time: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tQuota = b.at('حصة يومية');
  const tSched = b.at('وساعات');
  const tFive = b.at('بخمس دقائق');
  const tWarn = b.at('تنبيه هادئ');
  const tUp = b.at('شاشة «انتهى');
  const tPin = b.at('ولا تفتح');

  const tvIn = 1; // continuity: same TV as the previous scene (pure cross-fade, no entrance)
  const warnIn = tween(f, tFive + 10, 12);
  const upIn = tween(f, tUp - 4, 14);

  // minutes left (time-compressed): 60 → 5 at the 5-minute beat → 0 at time-up
  const mins = keys(
    f,
    [
      [tQuota + 16, 60],
      [tFive - 2, 5],
      [tUp - 6, 5],
      [tUp + 10, 0],
    ],
    easeInOut,
  );
  const ringDraw = tween(f, tQuota - 4, 22, easeInOut);
  const amber = tween(f, tFive - 6, 10);
  const ringCol = amber > 0.5 ? brand.amber : brand.primary;
  const C = 2 * Math.PI * RING.r;
  const frac = (mins / 60) * ringDraw;
  const ringIn = sp(f, tQuota - 8, {damping: 22});
  const barIn = sp(f, tSched - 4, {damping: 22});
  const unlockIn = sp(f, tPin - 4, {damping: 20});
  const shown = Math.ceil(mins - 0.001);

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker={t('حماية الأطفال')} index={stepOf(2, 5)} title={t('الحصص والجدول')} />

      <TvFrame x={TV.x + (1 - tvIn) * -60 * flowSign} y={TV.y} w={TV.w} opacity={clamp(tvIn * 1.4)} glowColor={upIn > 0.5 ? brand.amber : brand.primary}>
        <Screen name="settings_quota" />
        <Screen name="quota_warning" opacity={warnIn * (1 - upIn)} />
        <Screen name="time_up" opacity={upIn} />
        <FocusRing
          track={[
            {f: tQuota, rect: fr('settings_quota', 'minutes'), r: 34},
            {f: tSched, rect: fr('settings_quota', 'schedule'), r: 34},
          ]}
          until={tFive + 6}
          color={brand.kids}
          width={6}
        />
        <FocusRing track={[{f: tWarn - 4, rect: fr('quota_warning', 'toast'), r: 72}]} until={tUp - 2} color={brand.amber} width={5} />
        <FocusRing track={[{f: tPin, rect: fr('time_up', 'pin'), r: 56}]} color={brand.amber} width={5} />
      </TvFrame>

      {/* ring timer */}
      <div style={{position: 'absolute', ...atEnd(RING.cx - 170), top: RING.cy - 170, width: 340, height: 340, opacity: clamp(ringIn * 1.4), transform: `scale(${mix(0.9, 1, ringIn)})`}}>
        <svg width={340} height={340} viewBox="0 0 340 340" style={{overflow: 'visible'}}>
          <circle cx={170} cy={170} r={RING.r} fill="none" stroke="rgba(234,240,246,0.08)" strokeWidth={RING.w} />
          {/* remaining time: fills right-to-left (counter-clockwise from the top); clockwise in English */}
          <circle
            cx={170}
            cy={170}
            r={RING.r}
            fill="none"
            stroke={ringCol}
            strokeWidth={RING.w}
            strokeLinecap="round"
            strokeDasharray={`${C * frac} ${C}`}
            opacity={frac > 0.004 ? 1 : 0}
            transform={isEn ? 'rotate(-90 170 170)' : 'rotate(-90 170 170) scale(1 -1) translate(0 -340)'}
            style={{filter: `drop-shadow(0 0 10px ${rgba(ringCol, 0.5)})`}}
          />
        </svg>
        <div style={{position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center'}}>
          {upIn > 0.6 ? (
            <Icon name="hourglass" size={96} color={brand.amber} stroke={2} />
          ) : (
            <>
              <div style={{fontSize: 96, fontWeight: 700, lineHeight: 1, color: amber > 0.5 ? brand.amber : brand.text}}>{shown}</div>
              <div style={{fontSize: 28, fontWeight: 500, color: brand.muted, marginTop: 10}}>{isEn ? (shown === 1 ? t('دقيقة') : t('دقائق')) : shown <= 10 && shown > 2 ? t('دقائق') : t('دقيقة')}</div>
            </>
          )}
        </div>
      </div>
      <div style={{position: 'absolute', ...atEnd(RING.cx - (isEn ? 260 : 200)), width: isEn ? 520 : 400, top: RING.cy + 186, textAlign: 'center', fontSize: 28, fontWeight: 600, color: brand.text, opacity: clamp(ringIn * 1.4) * (1 - unlockIn)}}>
        {upIn > 0.5 ? t('انتهى الوقت') : amber > 0.5 ? t('تنبيه هادئ قبل النهاية') : t('حصة اليوم')}
      </div>

      {/* 24 h schedule */}
      <div style={{position: 'absolute', ...atStart(90), top: 690, opacity: 1 - unlockIn}}>
        <DayBar t={barIn} f={f} />
      </div>

      {/* unlock options */}
      <div style={{position: 'absolute', ...atStart(90), top: 690, display: 'flex', flexDirection: 'column', alignItems: isEn ? 'flex-start' : 'flex-end', gap: 16}}>
        <Chip icon="lock" label={t('تُفتح بالرمز فقط')} tone="amber" size={28} t={unlockIn} />
        <Chip icon="phone" label={t('أو من الجوال')} tone="teal" size={28} t={sp(f, tPin + 10, {damping: 20})} />
      </div>
    </div>
  );
};
