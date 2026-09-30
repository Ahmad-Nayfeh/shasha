import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {LogoMark, Wordmark, WORDMARK_RATIO} from '../components/Brand';
import {Screen, TvFrame} from '../components/Tv';
import {Chip, Strike} from '../components/ui';
import {clamp, easeInOut, easeOut, mix, sp, tween} from '../lib/anim';
import {beatsFor, Scene} from '../lib/timeline';

// s01 — logo reveal, then "this is Shasha": the TV with the home feed, ads & algorithm crossed out,
// and the four features as chips.

const TV = {x: 448, y: 176, w: 1024};
const TVH = (TV.w * 9) / 16;

const Tangle: React.FC<{f: number; from: number; untangle: number; out: number}> = ({f, from, untangle, out}) => {
  const N = 46;
  const appear = tween(f, from, 14);
  const gone = tween(f, out, 14);
  if (appear <= 0 || gone >= 1) return null;
  const x0 = TV.x + 90;
  const x1 = TV.x + TV.w - 90;
  const yLine = TV.y + TVH * 0.52;
  const pts: Array<[number, number]> = [];
  for (let i = 0; i < N; i++) {
    const u = i / (N - 1);
    // tangled curve: loops made of summed sines
    const tx = TV.x + TV.w * (0.5 + 0.34 * Math.sin(u * 17.3 + 0.6) * Math.cos(u * 5.1));
    const ty = TV.y + TVH * (0.5 + 0.32 * Math.sin(u * 13.7 + 1.9) * Math.sin(u * 3.3 + 0.4));
    // straight, time-ordered (newest at the right — RTL)
    const sx = mix(x1, x0, u);
    const t = clamp(tween(f, untangle + i * 0.35, 26, easeInOut));
    pts.push([mix(tx, sx, t), mix(ty, yLine, t)]);
  }
  const d = pts.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' ');
  const straight = tween(f, untangle + 18, 20);
  const labels = ['الآن', 'منذ ساعتين', 'أمس', 'منذ يومين', 'الأسبوع الماضي'];
  return (
    <div style={{position: 'absolute', inset: 0, opacity: appear * (1 - gone)}}>
      <svg width={1920} height={1080} style={{position: 'absolute', inset: 0}}>
        <defs>
          <filter id="tglow" x="-20%" y="-20%" width="140%" height="140%">
            <feGaussianBlur stdDeviation="6" />
          </filter>
        </defs>
        <rect x={TV.x} y={TV.y} width={TV.w} height={TVH} fill="rgba(5,8,11,0.55)" opacity={appear} />
        <path d={d} fill="none" stroke={straight > 0.5 ? brand.primary : brand.amber} strokeWidth={10} opacity={0.35} filter="url(#tglow)" />
        <path d={d} fill="none" stroke={straight > 0.5 ? brand.primary : brand.amber} strokeWidth={5} strokeLinecap="round" strokeLinejoin="round" />
        {labels.map((_, i) => {
          const x = mix(x1, x0, i / (labels.length - 1));
          return <circle key={i} cx={x} cy={yLine} r={11 * straight} fill={brand.primary} stroke={brand.bg} strokeWidth={4} />;
        })}
      </svg>
      {labels.map((l, i) => {
        const x = mix(x1, x0, i / (labels.length - 1));
        const t = tween(f, untangle + 22 + i * 3, 14);
        return (
          <div
            key={l}
            style={{
              position: 'absolute',
              left: x - 110,
              width: 220,
              top: yLine + 26,
              textAlign: 'center',
              fontFamily: brand.font,
              fontSize: 26,
              fontWeight: 600,
              color: i === 0 ? brand.primary : brand.text,
              opacity: t,
              transform: `translateY(${(1 - t) * 10}px)`,
              direction: 'rtl',
            }}
          >
            {l}
          </div>
        );
      })}
    </div>
  );
};

export const S01Intro: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);

  // --- logo reveal ----------------------------------------------------------------------------------
  const screenIn = sp(f, 4, {damping: 18, stiffness: 90});
  const tri = tween(f, 22, 26, easeOut);
  const dot = (start: number) => {
    const s = sp(f, start, {damping: 14, stiffness: 120});
    return {dy: (1 - s) * -170, opacity: clamp(s * 2.2), scale: mix(0.7, 1, clamp(s))};
  };
  const dots: [ReturnType<typeof dot>, ReturnType<typeof dot>, ReturnType<typeof dot>] = [dot(26), dot(34), dot(42)];
  const wm = tween(f, 46, 22);

  // park the lockup top-right just before "يوتيوب"
  const parkStart = b.at('يوتيوب') - 16;
  const park = tween(f, parkStart, 30, easeInOut);
  const float = Math.sin(f / 19) * 3 * (1 - park);

  const markW = mix(300, 62, park); // visible screen width in px
  const S = (markW / 751) * 1024;
  const cx = mix(960, 1792, park);
  const cy = mix(446, 104, park);
  const wmW = mix(340, 122, park);
  const wmH = wmW * WORDMARK_RATIO;
  const wmCx = mix(960, cx - markW / 2 - 20 - wmW / 2, park);
  const wmCy = mix(700, cy - 6, park);

  // --- TV -------------------------------------------------------------------------------------------
  const tvIn = sp(f, b.at('يوتيوب') - 2, {damping: 22, stiffness: 80});
  const adsFrom = b.at('بلا إعلانات') - 6;
  const adsStrike = b.at('إعلانات') + 4;
  const adsOut = b.at('وبلا خوارزمية') + 2;
  const algoFrom = b.at('وبلا خوارزمية') - 4;
  const algoUntangle = b.at('خوارزمية') + 6;
  const algoOut = b.at('ومعه') + 4;

  // feature montage inside the TV, in sync with the chips
  const feats = [
    {at: b.at('حصص للوقت'), icon: 'hourglass' as const, label: 'حصص للوقت', tone: 'teal' as const, screen: 'quota_warning'},
    {at: b.at('وملف للأطفال'), icon: 'star' as const, label: 'ملف للأطفال', tone: 'kids' as const, screen: 'kids_home'},
    {at: b.at('وقفل للفيديو'), icon: 'lock' as const, label: 'قفل للفيديو', tone: 'teal' as const, screen: 'kids_player_locked'},
    {at: b.at('وريموت من جوالك'), icon: 'phone' as const, label: 'ريموت من جوالك', tone: 'teal' as const, screen: 'remote_qr'},
  ];
  const screens = ['home_adult', ...feats.map((x) => x.screen)];
  const starts = [0, ...feats.map((x) => x.at)];

  return (
    <div style={{position: 'absolute', inset: 0}}>
      {/* TV */}
      <TvFrame
        x={TV.x + (1 - tvIn) * -240}
        y={TV.y}
        w={TV.w}
        opacity={clamp(tvIn * 1.6)}
        glow={tvIn}
      >
        {screens.map((s, i) => {
          const o = i === 0 ? 1 : tween(f, starts[i], 12);
          const next = i < screens.length - 1 ? tween(f, starts[i + 1] + 12, 1) : 0;
          if (o <= 0 || next >= 1) return null;
          return <Screen key={s} name={s} opacity={o} />;
        })}
      </TvFrame>

      {/* ads, crossed out */}
      {f >= adsFrom - 2 && f < adsOut + 16 ? (
        <>
          {[
            // on the capture's top-left video thumbnail (a "sponsored" badge), then the skip-ad button
            {x: TV.x + 72, y: TV.y + 166, label: 'إعلان', icon: true, delay: 0},
            {x: TV.x + 70, y: TV.y + TVH - 140, label: 'تخطَّ الإعلان  ◂', icon: false, delay: 5},
          ].map((ad, i) => {
            const t = sp(f, adsFrom + ad.delay, {damping: 20});
            const s = tween(f, adsStrike + i * 3, 8, easeInOut);
            const o = clamp(t * 1.5) * mix(1, 0.3, tween(f, adsStrike + 10, 12)) * (1 - tween(f, adsOut, 14));
            return (
              <div
                key={i}
                style={{
                  position: 'absolute',
                  left: ad.x,
                  top: ad.y,
                  opacity: o,
                  transform: `scale(${mix(0.9, 1, t)})`,
                  transformOrigin: 'left center',
                }}
              >
                <div
                  style={{
                    position: 'relative',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 12,
                    padding: i === 0 ? '10px 26px' : '14px 30px',
                    borderRadius: i === 0 ? 12 : 10,
                    background: i === 0 ? brand.amber : 'rgba(10,12,16,0.82)',
                    border: i === 0 ? 'none' : '2px solid rgba(234,240,246,0.3)',
                    color: i === 0 ? brand.onAmber : brand.text,
                    fontFamily: brand.font,
                    fontSize: i === 0 ? 34 : 30,
                    fontWeight: 700,
                    direction: 'rtl',
                    boxShadow: '0 14px 30px rgba(0,0,0,0.4)',
                  }}
                >
                  {ad.label}
                  <Strike p={s} color={i === 0 ? '#0B0F14' : brand.amber} width={6} />
                </div>
              </div>
            );
          })}
        </>
      ) : null}

      <Tangle f={f} from={algoFrom} untangle={algoUntangle} out={algoOut} />

      {/* feature chips under the TV (enter from the left, first chip on the right) */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: TV.y + TVH + 96,
          display: 'flex',
          justifyContent: 'center',
          gap: 22,
          direction: 'rtl',
        }}
      >
        {feats.map((c) => (
          <Chip key={c.label} icon={c.icon} label={c.label} tone={c.tone} size={30} t={sp(f, c.at - 4, {damping: 20, stiffness: 120})} />
        ))}
      </div>

      {/* logo: mark + wordmark, reveal then park top-right */}
      <div
        style={{
          position: 'absolute',
          left: cx - 512 * (S / 1024),
          top: cy - 651 * (S / 1024),
          width: S,
          height: S,
          transform: `translateY(${float}px)`,
        }}
      >
        <LogoMark
          size={S}
          screenScale={mix(0.85, 1, screenIn)}
          screenOpacity={clamp(screenIn * 1.5)}
          triangleScale={mix(0.55, 1, tri)}
          dots={[
            {...dots[0], dy: dots[0].dy + Math.sin(f / 21) * 6 * (1 - park)},
            {...dots[1], dy: dots[1].dy + Math.sin(f / 21 + 1) * 6 * (1 - park)},
            {...dots[2], dy: dots[2].dy + Math.sin(f / 21 + 2) * 6 * (1 - park)},
          ]}
        />
      </div>
      <div
        style={{
          position: 'absolute',
          left: wmCx - wmW / 2,
          top: wmCy - wmH / 2 + (1 - wm) * 24,
          opacity: wm,
        }}
      >
        <Wordmark width={wmW} />
      </div>
    </div>
  );
};

