import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {Icon, IconName} from '../components/Icons';
import {Remote} from '../components/Remote';
import {PinDotsOverlay} from '../components/Overlays';
import {fr, Screen, TvFrame} from '../components/Tv';
import {Card, FocusRing, Header} from '../components/ui';
import {clamp, easeInOut, mix, sp, tween} from '../lib/anim';
import {FEATURE_TV, tvH} from '../lib/layout';
import {beatsFor, Scene} from '../lib/timeline';

// s05 — profiles: the adult profile is protected by a PIN; the child profile only shows the channels you
// chose — no search, no typing, no exit without the PIN.

const TV = FEATURE_TV;

const PinDots: React.FC<{filled: number; c?: string}> = ({filled, c = brand.primary}) => (
  <div style={{display: 'flex', gap: 22, direction: 'ltr'}}>
    {[0, 1, 2, 3].map((i) => {
      const t = clamp(filled - i);
      return (
        <div
          key={i}
          style={{
            width: 34,
            height: 34,
            borderRadius: 999,
            border: `3px solid ${t > 0 ? c : 'rgba(234,240,246,0.3)'}`,
            background: t > 0 ? c : 'transparent',
            transform: `scale(${1 + Math.sin(t * Math.PI) * 0.25})`,
          }}
        />
      );
    })}
  </div>
);

const NoTile: React.FC<{icon: IconName; label: string; t: number; lock?: boolean}> = ({icon, label, t, lock}) => (
  <div
    style={{
      display: 'flex',
      alignItems: 'center',
      gap: 24,
      opacity: clamp(t * 1.4),
      transform: `translateX(${(1 - t) * -40}px) scale(${mix(0.9, 1, t)})`,
      direction: 'rtl',
    }}
  >
    <div
      style={{
        position: 'relative',
        width: 104,
        height: 104,
        borderRadius: 30,
        border: `3px solid ${brand.kids}`,
        background: rgba(brand.kids, 0.08),
        display: 'grid',
        placeItems: 'center',
      }}
    >
      <Icon name={icon} size={54} color={brand.kids} stroke={2.2} />
      <div
        style={{
          position: 'absolute',
          left: -12,
          top: -12,
          width: 42,
          height: 42,
          borderRadius: 999,
          background: lock ? brand.kids : brand.amber,
          display: 'grid',
          placeItems: 'center',
          boxShadow: '0 6px 16px rgba(0,0,0,0.4)',
          transform: `scale(${clamp(t * 1.2)})`,
        }}
      >
        <Icon name={lock ? 'lock' : 'x'} size={26} color="#2B1A00" stroke={3} />
      </div>
    </div>
    <div style={{fontSize: 36, fontWeight: 600, color: brand.text}}>{label}</div>
  </div>
);

export const S05Profiles: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tPin = b.at('يحميه');
  const tKid = b.at('أما ملف الطفل');
  const tChosen = b.at('القنوات التي');
  const tNo1 = b.at('بحث');
  const tNo2 = b.at('كتابة');
  const tNo3 = b.at('خروج');
  const tCode = b.at('بالرمز');

  const tvIn = 1; // continuity: same TV as the previous scene (pure cross-fade, no entrance)
  const fPress = tPin - 8;
  const pinIn = tween(f, fPress + 6, 10);
  const kidsIn = tween(f, tKid + 6, 12);
  const wash = tween(f, tKid - 4, 26, easeInOut);
  const warm = tween(f, tKid, 30);

  // PIN card (right column), then allow-list card, then the three "no" tiles
  const pinCardIn = sp(f, fPress + 2, {damping: 22});
  const pinCardOut = tween(f, tKid - 6, 12);
  const filled = clamp((f - (fPress + 14)) / 7, 0, 4);
  const allowIn = sp(f, tKid + 14, {damping: 22});
  const allowOut = tween(f, tNo1 - 16, 12);

  // a channel from outside the allow-list meets an invisible wall at the TV's edge
  const intr = tween(f, tChosen + 12, 26, easeInOut);
  const bounce = tween(f, tChosen + 38, 16, easeInOut);
  const intrOut = tween(f, tChosen + 50, 16);
  const wallX = TV.x + TV.w + 30;
  const cardX = mix(1500, wallX + 8, intr) + bounce * 70;
  const wallFlash = Math.max(0, Math.sin(clamp((f - (tChosen + 36)) / 22) * Math.PI));

  // what the child's home (kids_home capture) shows: two playlists + a channel from the allow-list
  const channels = ['تعلم الحروف مع أرنوب', 'تجارب علمية للصغار', 'حكايات قبل النوم'];
  return (
    <div style={{position: 'absolute', inset: 0}}>
      <AbsoluteFill style={{background: `radial-gradient(60% 60% at 35% 45%, ${rgba(brand.kids, 0.06)}, transparent 70%)`, opacity: warm}} />
      <Header kicker="جولة في الميزات" index="٣ / ٨" title="الملفات: كبار وطفل" />

      <TvFrame
        x={TV.x + (1 - tvIn) * -60}
        y={TV.y}
        w={TV.w}
        opacity={clamp(tvIn * 1.4)}
        glowColor={warm > 0.5 ? brand.kids : brand.primary}
        glow={mix(1, 0.8, warm)}
      >
        <Screen name="profile_picker" />
        <div style={{position: 'absolute', inset: 0, opacity: pinIn * (1 - kidsIn)}}>
          <Screen name="pin_entry" />
          <PinDotsOverlay name="pin_entry" filled={filled} />
        </div>
        <Screen name="kids_home" opacity={kidsIn} />
        <FocusRing track={[{f: 10, rect: fr('profile_picker', 'adult'), r: 150}]} presses={[fPress]} until={fPress + 8} width={6} />
        {/* kids-yellow wash sweeping right → left */}
        {wash > 0 && wash < 1 ? (
          <div
            style={{
              position: 'absolute',
              top: -100,
              bottom: -100,
              left: mix(1920, -900, wash),
              width: 900,
              background: `linear-gradient(90deg, transparent, ${rgba(brand.kids, 0.35)} 50%, transparent)`,
              transform: 'skewX(-12deg)',
            }}
          />
        ) : null}
      </TvFrame>

      {/* invisible wall */}
      <div
        style={{
          position: 'absolute',
          left: wallX - 3,
          top: TV.y + 40,
          width: 6,
          height: tvH(TV.w) - 80,
          borderRadius: 3,
          background: brand.kids,
          opacity: wallFlash * 0.9,
          boxShadow: `0 0 30px ${rgba(brand.kids, 0.8)}`,
        }}
      />
      {intr > 0 && intrOut < 1 ? (
        <div
          style={{
            position: 'absolute',
            left: cardX,
            top: TV.y + 250,
            opacity: clamp(intr * 3) * (1 - intrOut),
            display: 'flex',
            alignItems: 'center',
            gap: 16,
            padding: '16px 24px',
            borderRadius: 22,
            background: '#1A2029',
            border: '1.5px solid rgba(234,240,246,0.12)',
            filter: 'grayscale(1)',
            direction: 'rtl',
          }}
        >
          <div style={{width: 120, height: 68, borderRadius: 12, background: 'linear-gradient(135deg, #3A4452, #202833)'}} />
          <div style={{fontSize: 26, fontWeight: 600, color: brand.muted}}>قناة غير مسموحة</div>
        </div>
      ) : null}

      <Remote x={1600} y={340} scale={0.9} rotate={-8} opacity={clamp(tvIn) * (1 - tween(f, fPress + 4, 12))} presses={[{btn: 'ok', f: fPress}]} />

      {/* PIN card */}
      <div
        style={{
          position: 'absolute',
          right: 90,
          top: 330,
          opacity: clamp(pinCardIn * 1.4) * (1 - pinCardOut),
          transform: `translateX(${(1 - pinCardIn) * -40}px)`,
        }}
      >
        <Card style={{width: 460, padding: '36px 40px', boxSizing: 'border-box'}} tone="teal" glow={filled >= 4 ? 1 : 0}>
          <div style={{display: 'flex', alignItems: 'center', gap: 18}}>
            <Icon name={filled >= 4 ? 'unlock' : 'lock'} size={48} color={brand.primary} stroke={2.2} />
            <div style={{fontSize: 36, fontWeight: 700}}>ملف الكبار</div>
          </div>
          <div style={{fontSize: 28, color: brand.muted, margin: '14px 0 26px'}}>يحميه رمز سري (PIN)</div>
          <PinDots filled={filled} />
        </Card>
      </div>

      {/* allow-list card */}
      <div
        style={{
          position: 'absolute',
          right: 90,
          top: 300,
          opacity: clamp(allowIn * 1.4) * (1 - allowOut),
          transform: `translateX(${(1 - allowIn) * -40}px)`,
        }}
      >
        <Card style={{width: 460, padding: '32px 36px', boxSizing: 'border-box'}} tone="kids">
          <div style={{display: 'flex', alignItems: 'center', gap: 16}}>
            <Icon name="star" size={44} color={brand.kids} stroke={2.2} />
            <div style={{fontSize: 34, fontWeight: 700}}>قائمة الطفل</div>
          </div>
          <div style={{fontSize: 26, color: brand.muted, margin: '10px 0 18px'}}>فقط ما اخترته أنت</div>
          {channels.map((c, i) => {
            const t = sp(f, tChosen - 4 + i * 6, {damping: 20});
            return (
              <div key={c} style={{display: 'flex', alignItems: 'center', gap: 16, height: 70, opacity: clamp(t * 1.4), transform: `translateX(${(1 - t) * -24}px)`}}>
                <div style={{width: 46, height: 46, borderRadius: 999, background: rgba(brand.kids, 0.16), display: 'grid', placeItems: 'center'}}>
                  <Icon name="check" size={28} color={brand.kids} stroke={3} />
                </div>
                <div style={{fontSize: 30, fontWeight: 600}}>{c}</div>
              </div>
            );
          })}
        </Card>
      </div>

      {/* no search, no typing, no exit without the PIN */}
      <div style={{position: 'absolute', right: 90, top: 290, display: 'flex', flexDirection: 'column', gap: 34}}>
        <NoTile icon="search" label="لا بحث" t={sp(f, tNo1 - 6, {damping: 18})} />
        <NoTile icon="keyboard" label="لا كتابة" t={sp(f, tNo2 - 6, {damping: 18})} />
        <NoTile icon="exit" label="لا خروج إلا بالرمز" t={sp(f, tNo3 - 6, {damping: 18})} lock={f > tCode} />
      </div>
    </div>
  );
};
