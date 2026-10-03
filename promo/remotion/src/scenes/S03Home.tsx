import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {Icon, IconName} from '../components/Icons';
import {ScreenPatch} from '../components/Patches';
import {fr, Screen, TvFrame} from '../components/Tv';
import {atEnd, atStart, Chip, CrossedOut, featureTv, FocusRing, Header, mx} from '../components/ui';
import {clamp, easeInOut, easeOut, mix, sp, tween} from '../lib/anim';
import {rectToStage} from '../lib/layout';
import {dir, flowSign, isEn, t} from '../lib/lang';
import {focusRect} from '../lib/screens';
import {beatsFor, Scene} from '../lib/timeline';

// s03 — home: the newest videos of the channels you chose (no account), newest first; nothing is forced on you
// (no autoplay chain, no endless feed).

const TV = featureTv();
// the channels of the capture's first row (right → left in Arabic, left → right in English), so each RSS dot lands on
// its own card; the avatar's letter is the first letter of the name in the language of the render
const CHANNELS = [
  [t('عالم الطبيعة'), '#4A525C'],
  ['Space Lab', '#6B2748'],
  [t('قناة القرآن الكريم'), '#6B4721'],
  [t('مطبخ سلمى'), '#26386B'],
] as Array<[string, string]>;

export const S03Home: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tOpen = b.at('تفتح');
  const tChannels = b.at('القنوات التي');
  const tNewest = b.at('والأحدث');
  const tNone1 = b.at('لا شيء');
  const tNone2 = b.at('ولا شيء');

  const tvIn = sp(f, 0, {damping: 24, stiffness: 90});
  const reveal = tween(f, tOpen + 4, 34, easeOut); // home rows settle top → bottom
  const fCard = tNewest + 40;

  const avX = mx(1742);
  const avY = (i: number) => 330 + i * 118;
  const row1 = rectToStage(TV, focusRect('home_adult', 'row1'));
  const channelsOut = tween(f, tNone1 - 14, 14);

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker={t('جولة في «شاشة»')} title={t('الرئيسية')} />

      <TvFrame x={TV.x + (1 - tvIn) * -60 * flowSign} y={TV.y} w={TV.w} opacity={clamp(tvIn * 1.4)}>
        <div
          style={{
            position: 'absolute',
            inset: 0,
            maskImage: `linear-gradient(180deg, black ${reveal * 120 - 20}%, transparent ${reveal * 120}%)`,
            WebkitMaskImage: `linear-gradient(180deg, black ${reveal * 120 - 20}%, transparent ${reveal * 120}%)`,
          }}
        >
          <Screen name="home_adult" />
          <ScreenPatch name="home_adult" patch="unfocusFirstCard" opacity={tween(f, fCard, 10)} />
        </div>
        {/* new items arriving: soft teal pulse on the first row as the RSS dots land */}
        <div
          style={{
            position: 'absolute',
            left: fr('home_adult', 'row1')[0],
            top: fr('home_adult', 'row1')[1],
            width: fr('home_adult', 'row1')[2],
            height: fr('home_adult', 'row1')[3],
            borderRadius: 24,
            boxShadow: `0 0 0 4px ${rgba(brand.primary, 0.5)}, 0 0 60px ${rgba(brand.primary, 0.35)}`,
            opacity: Math.max(0, Math.sin(clamp((f - (tNewest + 26)) / 40) * Math.PI)) * 0.8,
          }}
        />
        <FocusRing track={[{f: fCard, rect: fr('home_adult', 'firstCard'), r: 24}]} width={6} />
      </TvFrame>

      {/* channels → RSS → home */}
      <div style={{opacity: 1 - channelsOut}}>
        <div style={{position: 'absolute', ...atStart(90), top: 244, fontSize: 26, fontWeight: 600, color: brand.muted, opacity: tween(f, tChannels - 6, 14)}}>
          {t('قنواتك')}
        </div>
        {CHANNELS.map(([name, c], i) => {
          const l = [...name][0];
          const k = sp(f, tChannels - 6 + i * 5, {damping: 20});
          return (
            <div
              key={name}
              style={{
                position: 'absolute',
                ...atStart(90),
                top: avY(i),
                display: 'flex',
                alignItems: 'center',
                gap: 20,
                direction: dir,
                opacity: clamp(k * 1.4),
                transform: `translateX(${(1 - k) * -30 * flowSign}px)`,
              }}
            >
              <div
                style={{
                  width: 84,
                  height: 84,
                  borderRadius: 999,
                  background: `radial-gradient(70% 70% at 35% 30%, ${c}, #0B0F14 120%)`,
                  display: 'grid',
                  placeItems: 'center',
                  fontSize: 38,
                  fontWeight: 700,
                  color: brand.text,
                  boxShadow: 'inset 0 0 0 2px rgba(255,255,255,0.08)',
                  position: 'relative',
                }}
              >
                {l}
                <div
                  style={{
                    position: 'absolute',
                    ...atEnd(-6),
                    bottom: -6,
                    width: 38,
                    height: 38,
                    borderRadius: 999,
                    background: brand.bg,
                    display: 'grid',
                    placeItems: 'center',
                    opacity: tween(f, tNewest - 4 + i * 4, 10),
                  }}
                >
                  <Icon name="rss" size={24} color={brand.primary} stroke={2.6} />
                </div>
              </div>
              <div style={{fontSize: 32, fontWeight: 600, color: brand.text}}>{name}</div>
            </div>
          );
        })}
        {/* RSS particles flying into the first row */}
        <svg width={1920} height={1080} style={{position: 'absolute', inset: 0, overflow: 'visible'}}>
          {CHANNELS.map((_, i) =>
            [0, 1, 2].map((k) => {
              const start = tNewest + i * 5 + k * 7;
              const u = tween(f, start, 30, easeInOut);
              if (u <= 0 || u >= 1) return null;
              const x0 = isEn ? avX + 30 : avX - 30;
              const y0 = avY(i) + 42;
              const x1 = row1[0] + row1[2] * (isEn ? 0.12 + i * 0.24 : 0.88 - i * 0.24);
              const y1 = row1[1] + row1[3] * 0.5;
              const cx = mix(x0, x1, 0.5);
              const cy = Math.min(y0, y1) - 120;
              const x = (1 - u) * (1 - u) * x0 + 2 * (1 - u) * u * cx + u * u * x1;
              const y = (1 - u) * (1 - u) * y0 + 2 * (1 - u) * u * cy + u * u * y1;
              return (
                <g key={`${i}${k}`} opacity={Math.sin(u * Math.PI)}>
                  <circle cx={x} cy={y} r={12} fill={rgba(brand.primary, 0.25)} />
                  <circle cx={x} cy={y} r={6} fill={brand.primaryBright} />
                </g>
              );
            }),
          )}
        </svg>
        <div style={{position: 'absolute', ...atStart(90), top: 820, display: 'flex', gap: 18, direction: dir}}>
          <Chip icon="clock" label={t('الأحدث أولاً')} size={26} t={sp(f, tNewest + 6, {damping: 20})} />
          <Chip icon="userSlash" label={t('بلا حساب')} size={26} t={sp(f, tChannels + 30, {damping: 20})} />
        </div>
      </div>

      {/* what is NOT forced on you, crossed out — in the right column after the channels */}
      <div style={{position: 'absolute', ...atStart(90), top: 330, display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 30, direction: dir}}>
        {(
          [
            [t('تشغيل تلقائي لا ينتهي'), tNone1 - 2, tNone1 + 18, 'play'],
            [t('تمرير بلا نهاية'), tNone2 - 2, tNone2 + 18, 'flame'],
          ] as Array<[string, number, number, IconName]>
        ).map(([label, from, strike, icon]) => (
          <CrossedOut key={label} from={from} strikeAt={strike}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 16,
                padding: '16px 36px',
                borderRadius: 999,
                background: 'rgba(234,240,246,0.10)',
                border: '1.5px solid rgba(234,240,246,0.24)',
                fontSize: 40,
                fontWeight: 600,
                color: brand.text,
              }}
            >
              <Icon name={icon} size={40} color={brand.text} />
              {label}
            </div>
          </CrossedOut>
        ))}
      </div>
    </div>
  );
};
