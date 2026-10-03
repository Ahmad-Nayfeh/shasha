import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {ShashaOnScreen, StickGlyph, TvGlyph} from '../components/Devices';
import {CheckBadge, Header} from '../components/ui';
import {clamp, mix, sp, tween} from '../lib/anim';
import {beatsFor, Scene} from '../lib/timeline';

// s16 — which TVs: Google TV and Android TV sets of any maker, and Amazon Fire TV devices. Drawn devices, names as
// plain text (no logos).

const CARD_W = 640;
const CARD_H = 560;
const TOP = 270;

const DeviceCard: React.FC<{left: number; t: number; check: number; title: React.ReactNode; sub: string; children: React.ReactNode}> = ({
  left,
  t,
  check,
  title,
  sub,
  children,
}) => (
  <div
    style={{
      position: 'absolute',
      left,
      top: TOP,
      width: CARD_W,
      height: CARD_H,
      borderRadius: 36,
      background: 'linear-gradient(180deg, #19212C 0%, #131920 100%)',
      border: `1.5px solid ${rgba(brand.primary, 0.4)}`,
      boxShadow: '0 40px 90px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.04)',
      opacity: clamp(t * 1.4),
      transform: `translateY(${(1 - t) * 40}px) scale(${mix(0.96, 1, t)})`,
      direction: 'rtl',
      fontFamily: brand.font,
      color: brand.text,
      overflow: 'hidden',
    }}
  >
    <div style={{position: 'absolute', inset: 0}}>{children}</div>
    <div style={{position: 'absolute', left: 0, right: 0, top: 372, textAlign: 'center'}}>
      <div style={{fontSize: 44, fontWeight: 700, direction: 'ltr'}}>{title}</div>
      <div style={{fontSize: 27, color: brand.muted, marginTop: 14, direction: 'ltr'}}>{sub}</div>
    </div>
    <div style={{position: 'absolute', left: 28, top: 28}}>
      <CheckBadge t={check} size={64} />
    </div>
  </div>
);

export const S16Tvs: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tGoogle = b.at('جوجل');
  const tAndroid = b.at('أندرويد');
  const tAmazon = b.at('أمازون');

  const aIn = sp(f, tGoogle - 14, {damping: 22});
  const bIn = sp(f, tAmazon - 14, {damping: 22});
  const aCheck = sp(f, tAndroid + 16, {damping: 14, stiffness: 140});
  const bCheck = sp(f, tAmazon + 26, {damping: 14, stiffness: 140});
  const android = tween(f, tAndroid - 2, 12);
  const float = (k: number) => Math.sin((f + k) / 26) * 4;

  const leftA = 990;
  const leftB = 290;
  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker="التحديث والأجهزة" index="٢ / ٣" title="على أي تلفاز؟" />

      <DeviceCard
        left={leftA}
        t={aIn}
        check={aCheck}
        title={
          <>
            Google TV
            <span style={{opacity: android}}> · Android TV</span>
          </>
        }
        sub="Sony · TCL · Hisense · Philips · Xiaomi · Shield"
      >
        <div style={{position: 'absolute', left: 0, top: float(0)}}>
          <TvGlyph x={(CARD_W - 450) / 2} y={64} w={450}>
            <ShashaOnScreen w={450} />
          </TvGlyph>
        </div>
      </DeviceCard>

      <DeviceCard left={leftB} t={bIn} check={bCheck} title="Amazon Fire TV" sub="Fire TV Stick · Cube · Fire TV sets">
        <div style={{position: 'absolute', left: 0, top: float(9)}}>
          <StickGlyph x={(CARD_W - 360) / 2} y={190} scale={1.1} glow={tween(f, tAmazon + 10, 20)} />
        </div>
      </DeviceCard>
    </div>
  );
};
