import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {HAS_WORDMARK, LogoMark, Wordmark, WORDMARK_RATIO} from '../components/Brand';
import {Icon} from '../components/Icons';
import {Screen, TvFrame} from '../components/Tv';
import {atEnd, Chip, Strike} from '../components/ui';
import {clamp, easeInOut, easeOut, mix, sp, tween} from '../lib/anim';
import {dir, flowSign, isEn, t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// s01 — logo reveal, then "this is Shasha": the TV with the home feed, ads and the sign-in crossed out,
// and the family features (kids' profiles, a set time, the phone as remote) as chips.
// English version: the brand mark alone (no Latin wordmark exists, see HAS_WORDMARK), parked top left; the ad badges
// sit on the mirrored corners of the TV (the English home capture is the mirror image of the Arabic one).

const TV = {x: 448, y: 176, w: 1024};
const TVH = (TV.w * 9) / 16;

/** A sign-in form over the TV that gets crossed out: «بلا حاجة إلى تسجيل الدخول». */
const SignIn: React.FC<{f: number; from: number; strike: number; out: number}> = ({f, from, strike, out}) => {
  const appear = sp(f, from, {damping: 20});
  const gone = tween(f, out, 14);
  if (appear <= 0.01 || gone >= 1) return null;
  const s = tween(f, strike, 10, easeInOut);
  const field = (label: string, w: number) => (
    <div style={{height: 64, borderRadius: 14, background: 'rgba(234,240,246,0.07)', border: '1.5px solid rgba(234,240,246,0.16)', display: 'flex', alignItems: 'center', padding: '0 22px', fontSize: 28, color: brand.muted, width: w, direction: dir}}>
      {label}
    </div>
  );
  return (
    <div style={{position: 'absolute', left: TV.x + TV.w / 2 - 270, top: TV.y + TVH / 2 - 235, width: 540, opacity: clamp(appear * 1.4) * (1 - gone), transform: `scale(${mix(0.92, 1, appear)})`}}>
      <div style={{position: 'relative', padding: '34px 40px 38px', borderRadius: 28, background: 'rgba(14,19,26,0.94)', border: '1.5px solid rgba(234,240,246,0.2)', boxShadow: '0 30px 70px rgba(0,0,0,0.5)', direction: dir, fontFamily: brand.font}}>
        <div style={{display: 'flex', alignItems: 'center', gap: 16, marginBottom: 24}}>
          <div style={{width: 64, height: 64, borderRadius: 999, background: 'rgba(234,240,246,0.1)', display: 'grid', placeItems: 'center'}}>
            <Icon name="user" size={36} color={brand.text} stroke={2.2} />
          </div>
          <div style={{fontSize: 38, fontWeight: 700, color: brand.text}}>{t('تسجيل الدخول')}</div>
        </div>
        <div style={{display: 'flex', flexDirection: 'column', gap: 16}}>
          {field(t('البريد الإلكتروني'), 460)}
          {field(t('كلمة المرور'), 460)}
        </div>
        <Strike p={s} color={brand.amber} width={8} />
      </div>
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

  // park the lockup top-right just before "يوتيوب" (top left in English, where the mark stands alone)
  const parkStart = b.at('يوتيوب') - 16;
  const park = tween(f, parkStart, 30, easeInOut);
  const float = Math.sin(f / 19) * 3 * (1 - park);

  const markW = mix(300, 62, park); // visible screen width in px
  const S = (markW / 751) * 1024;
  const cx = mix(960, isEn ? 1920 - 1792 : 1792, park);
  // with no wordmark below it, the mark is centred on the stage (its screen sits 139 box units under the mark's centre)
  const cy = mix(HAS_WORDMARK ? 446 : 540 + (139 * ((300 / 751) * 1024)) / 1024, 104, park);
  const wmW = mix(340, 122, park);
  const wmH = wmW * WORDMARK_RATIO;
  const wmCx = mix(960, isEn ? cx + markW / 2 + 20 + wmW / 2 : cx - markW / 2 - 20 - wmW / 2, park);
  const wmCy = mix(700, cy - 6, park);

  // --- TV -------------------------------------------------------------------------------------------
  const tvIn = sp(f, b.at('يوتيوب') - 2, {damping: 22, stiffness: 80});
  const adsFrom = b.at('بلا إعلانات') - 6;
  const adsStrike = b.at('إعلانات') + 4;
  const adsOut = b.at('وبلا حاجة') + 2;
  const loginFrom = b.at('وبلا حاجة') - 2;
  const loginStrike = b.at('تسجيل الدخول') + 14;
  const loginOut = b.at('ومعه') + 4;

  // feature montage inside the TV, in sync with the chips
  const feats = [
    {at: b.at('ملفات خاصة'), icon: 'star' as const, label: t('ملفات للأطفال'), tone: 'kids' as const, screen: 'kids_home'},
    {at: b.at('ووقت محدد'), icon: 'hourglass' as const, label: t('وقت محدد'), tone: 'teal' as const, screen: 'quota_warning'},
    {at: b.at('وريموت'), icon: 'phone' as const, label: t('ريموت من جوالك'), tone: 'teal' as const, screen: 'remote_qr'},
  ];
  const screens = ['home_adult', ...feats.map((x) => x.screen)];
  const starts = [0, ...feats.map((x) => x.at)];

  return (
    <div style={{position: 'absolute', inset: 0}}>
      {/* TV */}
      <TvFrame
        x={TV.x + (1 - tvIn) * -240 * flowSign}
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
            {x: TV.x + 72, y: TV.y + 166, label: t('إعلان'), icon: true, delay: 0},
            {x: TV.x + 70, y: TV.y + TVH - 140, label: t('تخطَّ الإعلان  ◂'), icon: false, delay: 5},
          ].map((ad, i) => {
            const k = sp(f, adsFrom + ad.delay, {damping: 20});
            const s = tween(f, adsStrike + i * 3, 8, easeInOut);
            const o = clamp(k * 1.5) * mix(1, 0.3, tween(f, adsStrike + 10, 12)) * (1 - tween(f, adsOut, 14));
            return (
              <div
                key={i}
                style={{
                  position: 'absolute',
                  ...atEnd(ad.x), // the TV is centred: the mirror image of `left: x` is `right: x`
                  top: ad.y,
                  opacity: o,
                  transform: `scale(${mix(0.9, 1, k)})`,
                  transformOrigin: isEn ? 'right center' : 'left center',
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
                    direction: dir,
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

      <SignIn f={f} from={loginFrom} strike={loginStrike} out={loginOut} />

      {/* feature chips under the TV (enter from the left, first chip on the right; mirrored in English) */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: TV.y + TVH + 96,
          display: 'flex',
          justifyContent: 'center',
          gap: 22,
          direction: dir,
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

