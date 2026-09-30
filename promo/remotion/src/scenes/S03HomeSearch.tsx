import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {Icon} from '../components/Icons';
import {Remote} from '../components/Remote';
import {ScreenPatch} from '../components/Patches';
import {fr, Screen, TvFrame} from '../components/Tv';
import {Chip, CrossedOut, FocusRing, Header} from '../components/ui';
import {clamp, easeInOut, easeOut, mix, sp, tween} from '../lib/anim';
import {FEATURE_TV, rectToStage} from '../lib/layout';
import {focusRect} from '../lib/screens';
import {beatsFor, Scene} from '../lib/timeline';

// s03 — home = latest videos of your channels (RSS, no account), no recommendations/trending;
// search exists in the adult profile.

const TV = FEATURE_TV;
// the channels of the capture's first row (right → left), so each RSS dot lands on its own card
const CHANNELS: Array<[string, string, string]> = [
  ['ع', 'عالم الطبيعة', '#4A525C'],
  ['S', 'Space Lab', '#6B2748'],
  ['ق', 'قناة القرآن الكريم', '#6B4721'],
  ['م', 'مطبخ سلمى', '#26386B'],
];

export const S03HomeSearch: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tChannels = b.at('قنواتك');
  const tRss = b.at('والأحدث');
  const tNoAcc = b.at('بلا أي حساب');
  const tNoRec = b.at('لا مقترحات');
  const tTrend = b.at('رائج');
  const tSearch = b.at('والبحث');
  const tAdult = b.at('ملف الكبار');

  const tvIn = sp(f, 0, {damping: 24, stiffness: 90});
  const reveal = tween(f, 6, 34, easeOut); // home rows settle top → bottom

  // search: the D-pad focus starts on the first card (where the app has it), then right → the rail's «home»,
  // down → «search», OK press, cut to the search screen
  const fCard = 22;
  const fFocus = tSearch - 2;
  const fPress = tSearch + 22;
  const fCut = fPress + 6;
  const toSearch = tween(f, fCut, 10);

  const colOut = tween(f, tSearch - 10, 14);
  const remoteIn = sp(f, tSearch - 14, {damping: 22});

  // avatars in the right column
  const avX = 1742;
  const avY = (i: number) => 330 + i * 118;
  const row1 = rectToStage(TV, focusRect('home_adult', 'row1'));

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker="جولة في الميزات" index="١ / ٨" title="الرئيسية والبحث" />

      <TvFrame x={TV.x + (1 - tvIn) * -60} y={TV.y} w={TV.w} opacity={clamp(tvIn * 1.4)}>
        <div
          style={{
            position: 'absolute',
            inset: 0,
            maskImage: `linear-gradient(180deg, black ${reveal * 120 - 20}%, transparent ${reveal * 120}%)`,
            WebkitMaskImage: `linear-gradient(180deg, black ${reveal * 120 - 20}%, transparent ${reveal * 120}%)`,
          }}
        >
          <Screen name="home_adult" opacity={1 - toSearch} />
          <ScreenPatch name="home_adult" patch="unfocusFirstCard" opacity={tween(f, fCard, 10) * (1 - toSearch)} />
        </div>
        <Screen name="search_adult" opacity={toSearch} />
        {/* new items arriving: soft teal pulse on the first row as RSS dots land */}
        <div
          style={{
            position: 'absolute',
            left: fr('home_adult', 'row1')[0],
            top: fr('home_adult', 'row1')[1],
            width: fr('home_adult', 'row1')[2],
            height: fr('home_adult', 'row1')[3],
            borderRadius: 24,
            boxShadow: `0 0 0 4px ${rgba(brand.primary, 0.5)}, 0 0 60px ${rgba(brand.primary, 0.35)}`,
            opacity: Math.max(0, Math.sin(clamp((f - (tRss + 26)) / 40) * Math.PI)) * 0.8 * (1 - toSearch),
          }}
        />
        <FocusRing
          track={[
            {f: fCard, rect: fr('home_adult', 'firstCard'), r: 24},
            {f: fFocus - 8, rect: fr('home_adult', 'home'), r: 44},
            {f: fFocus, rect: fr('home_adult', 'search'), r: 44},
          ]}
          until={fCut}
          presses={[fPress]}
          width={6}
        />
      </TvFrame>

      {/* channels → RSS → home */}
      <div style={{opacity: 1 - colOut}}>
        <div style={{opacity: 1 - tween(f, tNoRec - 12, 12)}}>
        <div
          style={{
            position: 'absolute',
            right: 90,
            top: 244,
            fontSize: 26,
            fontWeight: 600,
            color: brand.muted,
            opacity: tween(f, tChannels - 6, 14),
          }}
        >
          قنواتك
        </div>
        {CHANNELS.map(([l, name, c], i) => {
          const t = sp(f, tChannels - 6 + i * 5, {damping: 20});
          return (
            <div
              key={name}
              style={{
                position: 'absolute',
                right: 90,
                top: avY(i),
                display: 'flex',
                alignItems: 'center',
                gap: 20,
                direction: 'rtl',
                opacity: clamp(t * 1.4),
                transform: `translateX(${(1 - t) * -30}px)`,
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
                {/* RSS badge */}
                <div
                  style={{
                    position: 'absolute',
                    left: -6,
                    bottom: -6,
                    width: 38,
                    height: 38,
                    borderRadius: 999,
                    background: brand.bg,
                    display: 'grid',
                    placeItems: 'center',
                    opacity: tween(f, tRss - 4 + i * 4, 10),
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
              const start = tRss + i * 5 + k * 7;
              const t = tween(f, start, 30, easeInOut);
              if (t <= 0 || t >= 1) return null;
              const x0 = avX - 30;
              const y0 = avY(i) + 42;
              const x1 = row1[0] + row1[2] * (0.88 - i * 0.24);
              const y1 = row1[1] + row1[3] * 0.5;
              const cx = mix(x0, x1, 0.5);
              const cy = Math.min(y0, y1) - 120;
              const x = (1 - t) * (1 - t) * x0 + 2 * (1 - t) * t * cx + t * t * x1;
              const y = (1 - t) * (1 - t) * y0 + 2 * (1 - t) * t * cy + t * t * y1;
              return (
                <g key={`${i}${k}`} opacity={Math.sin(t * Math.PI)}>
                  <circle cx={x} cy={y} r={12} fill={rgba(brand.primary, 0.25)} />
                  <circle cx={x} cy={y} r={6} fill={brand.primaryBright} />
                </g>
              );
            }),
          )}
        </svg>
        </div>
        {/* chips: RSS + no account */}
        <div style={{position: 'absolute', right: 90, top: 820, display: 'flex', gap: 18, direction: 'rtl'}}>
          <Chip icon="rss" label="من خلاصات RSS" size={26} t={sp(f, tRss + 6, {damping: 20})} />
          <Chip icon="userSlash" label="بلا أي حساب" size={26} t={sp(f, tNoAcc - 2, {damping: 20})} />
        </div>
      </div>

      {/* recommendations / trending, crossed out — in the right column after the channels */}
      <div
        style={{
          position: 'absolute',
          right: 90,
          top: 360,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'flex-start',
          gap: 26,
          direction: 'rtl',
          opacity: 1 - tween(f, tSearch - 12, 12),
        }}
      >
        {[
          ['مقترح لك', tNoRec - 2, b.at('مقترحات') + 8, 'sparkle'],
          ['رائج الآن', tTrend - 6, tTrend + 6, 'flame'],
        ].map(([label, from, strike, icon]) => (
          <CrossedOut key={label as string} from={from as number} strikeAt={strike as number}>
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
              <Icon name={icon as 'flame'} size={40} color={brand.text} />
              {label as string}
            </div>
          </CrossedOut>
        ))}
      </div>

      {/* remote for the search press */}
      <Remote
        x={1590}
        y={330}
        scale={0.95}
        rotate={-8}
        opacity={clamp(remoteIn * 1.5)}
        presses={[
          {btn: 'right', f: fFocus - 8},
          {btn: 'down', f: fFocus},
          {btn: 'ok', f: fPress},
        ]}
      />

      {/* «ملف الكبار فقط» tag, top-left of the TV (RTL end) */}
      <div
        style={{
          position: 'absolute',
          left: TV.x,
          top: TV.y - 74,
          opacity: tween(f, tAdult - 4, 14),
          transform: `translateY(${(1 - tween(f, tAdult - 4, 14)) * 10}px)`,
        }}
      >
        <Chip icon="lock" label="ملف الكبار فقط" size={24} t={1} />
      </div>
    </div>
  );
};

