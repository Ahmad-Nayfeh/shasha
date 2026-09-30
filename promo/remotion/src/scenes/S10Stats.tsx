import React from 'react';
import {useCurrentFrame} from 'remotion';
import {arDigits, brand, rgba} from '../brand';
import {Icon} from '../components/Icons';
import {TvFrame} from '../components/Tv';
import {Card, CrossedOut, Header} from '../components/ui';
import {clamp, easeInOut, easeOut, keys, sp, tween} from '../lib/anim';
import {FEATURE_TV} from '../lib/layout';
import {beatsFor, Scene} from '../lib/timeline';

// s10 — honest stats: minutes per profile, top channels, vs last week. No badges, no streaks.

const TV = FEATURE_TV;
// Sara's week as in the stats capture (right → left: Tuesday … today), minutes; her quota is 1 hour.
const DAYS = ['ث', 'ر', 'خ', 'ج', 'س', 'ح', 'اليوم'];
const KID = [38, 62, 45, 20, 58, 71, 41];
const QUOTA = 60;

export const S10Stats: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tMin = b.at('دقائق كل ملف');
  const tTop = b.at('وأكثر القنوات');
  const tCmp = b.at('ومقارنة');
  const tNo = b.at('بلا نقاط');
  const tStreak = b.at('أوسمة');

  const tvIn = 1; // continuity: same TV as the previous scene (pure cross-fade, no entrance)
  const cardIn = sp(f, 6, {damping: 22});
  // as in the capture (the app writes its numbers with Latin digits)
  const channels: Array<[string, number, string, string, string]> = [
    ['عالم الطبيعة', 118, '1 س 58 د', 'ع', '#C27A22'],
    ['حكايات قبل النوم', 74, '1 س 14 د', 'ح', '#B8404F'],
    ['Space Lab', 52, '52 د', 'S', '#3F58B4'],
  ];

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker="جولة في الميزات" index="٨ / ٨" title="إحصائيات صادقة" />

      <TvFrame
        x={TV.x + (1 - tvIn) * -60}
        y={TV.y}
        w={TV.w}
        opacity={clamp(tvIn * 1.4)}
        screenshot="stats"
        focus={[
          {f: tMin, key: 'chart', r: 40},
          {f: tTop, key: 'channels', r: 40},
          {f: tCmp, key: 'compare', r: 22},
        ]}
        focusUntil={tNo}
        camera={{
          // slow push-in (keeps every edge of the capture on screen: 1/1.05 of it is always visible)
          s: keys(f, [[0, 1], [scene.duration_frames, 1.05]], easeInOut),
          cx: 0.5,
          cy: 0.5,
        }}
      />

      <div style={{position: 'absolute', right: 90, top: 236, opacity: clamp(cardIn * 1.4), transform: `translateX(${(1 - cardIn) * -40}px)`}}>
        <Card style={{width: 500, padding: '28px 34px 30px', boxSizing: 'border-box'}}>
          <div style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between'}}>
            <div style={{fontSize: 32, fontWeight: 700}}>هذا الأسبوع · سارة</div>
            <div style={{fontSize: 26, fontWeight: 600, color: brand.text}}>5 س 35 د</div>
          </div>
          {/* bars grow from the baseline; days read right → left; dashed line = her daily quota */}
          <div style={{position: 'relative', height: 190, marginTop: 18, display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', direction: 'rtl'}}>
            <div style={{position: 'absolute', left: 0, right: 0, bottom: 38 + QUOTA * 1.6, borderTop: `2px dashed ${rgba(brand.text, 0.35)}`, opacity: tween(f, tMin, 12)}} />
            {DAYS.map((d, i) => {
              const g = tween(f, tMin - 6 + i * 3, 22, easeOut);
              const today = i === DAYS.length - 1;
              return (
                <div key={d} style={{display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, width: 52}}>
                  <div style={{display: 'flex', alignItems: 'flex-end', height: 150}}>
                    <div style={{width: 26, height: KID[i] * 1.6 * g, borderRadius: 6, background: today ? brand.kids : '#5E6B7D'}} />
                  </div>
                  <div style={{fontSize: 20, color: today ? brand.text : brand.muted, whiteSpace: 'nowrap'}}>{d}</div>
                </div>
              );
            })}
          </div>
          {/* top channels */}
          <div style={{marginTop: 18}}>
            {channels.map(([c, m, label, letter, col], i) => {
              const t = sp(f, tTop - 4 + i * 6, {damping: 20});
              return (
                <div key={c} style={{display: 'flex', alignItems: 'center', gap: 14, height: 54, opacity: clamp(t * 1.4), transform: `translateX(${(1 - t) * -24}px)`}}>
                  <div style={{width: 34, height: 34, borderRadius: 99, background: col, display: 'grid', placeItems: 'center', fontSize: 18, fontWeight: 700, flex: 'none'}}>{letter}</div>
                  <div style={{flex: 1, fontSize: 25, fontWeight: 600, whiteSpace: 'nowrap'}}>{c}</div>
                  <div style={{width: 80, height: 8, borderRadius: 4, background: 'rgba(234,240,246,0.1)', direction: 'rtl', flex: 'none'}}>
                    <div style={{width: `${(m / 118) * 100 * t}%`, height: '100%', borderRadius: 4, background: brand.kids}} />
                  </div>
                  <div style={{width: 92, fontSize: 22, color: brand.muted, textAlign: 'left', whiteSpace: 'nowrap', flex: 'none'}}>{label}</div>
                </div>
              );
            })}
          </div>
          {/* vs last week */}
          <div
            style={{
              marginTop: 18,
              display: 'inline-flex',
              alignItems: 'center',
              gap: 12,
              padding: '10px 22px',
              borderRadius: 999,
              background: rgba(brand.primary, 0.13),
              color: brand.primaryBright,
              fontSize: 26,
              fontWeight: 600,
              opacity: tween(f, tCmp - 4, 12),
              transform: `translateY(${(1 - tween(f, tCmp - 4, 12)) * 10}px)`,
            }}
          >
            <Icon name="barChart" size={28} color={brand.primaryBright} />
            أكثر بـ16% من الأسبوع الماضي
          </div>
        </Card>
      </div>

      {/* no badges, no streaks */}
      <div style={{position: 'absolute', right: 90, top: 836, display: 'flex', gap: 22, direction: 'rtl'}}>
        {[
          ['star', 'نقاط', tNo - 4, tNo + 10],
          ['medal', 'أوسمة', tStreak - 8, tStreak + 4],
        ].map(([icon, label, from, strike]) => (
          <CrossedOut key={label as string} from={from as number} strikeAt={strike as number}>
            <div style={{display: 'flex', alignItems: 'center', gap: 12, padding: '10px 26px', borderRadius: 999, border: '1.5px solid rgba(234,240,246,0.24)', background: 'rgba(234,240,246,0.08)', fontSize: 28, fontWeight: 600}}>
              <Icon name={icon as 'medal'} size={34} color={brand.text} />
              {label as string}
            </div>
          </CrossedOut>
        ))}
      </div>
    </div>
  );
};
