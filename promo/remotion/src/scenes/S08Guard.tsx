import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {Icon, IconName} from '../components/Icons';
import {Launcher} from '../components/Launcher';
import {Remote} from '../components/Remote';
import {Screen, TvFrame} from '../components/Tv';
import {Chip, Header} from '../components/ui';
import {clamp, easeInOut, easeOut, mix, sp, tween} from '../lib/anim';
import {FEATURE_TV} from '../lib/layout';
import {beatsFor, Scene} from '../lib/timeline';

// s08 — the guard: Home pressed during a lock → the launcher appears → the guard covers it → back to Shasha.

const TV = FEATURE_TV;

const Step: React.FC<{n: string; icon: IconName; label: string; on: number; t: number; color: string}> = ({n, icon, label, on, t, color}) => (
  <div style={{display: 'flex', alignItems: 'center', gap: 22, opacity: clamp(t * 1.4) * mix(0.45, 1, on), transform: `translateX(${(1 - t) * -30}px)`}}>
    <div
      style={{
        width: 84,
        height: 84,
        borderRadius: 26,
        background: rgba(color, 0.12 + on * 0.1),
        border: `2px solid ${rgba(color, 0.3 + on * 0.6)}`,
        display: 'grid',
        placeItems: 'center',
        boxShadow: on > 0.5 ? `0 0 26px ${rgba(color, 0.35)}` : undefined,
      }}
    >
      <Icon name={icon} size={44} color={color} stroke={2.2} />
    </div>
    <div>
      <div style={{fontSize: 22, color: brand.muted, fontWeight: 600}}>{n}</div>
      <div style={{fontSize: 32, fontWeight: 600, color: brand.text}}>{label}</div>
    </div>
  </div>
);

export const S08Guard: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tHome = b.at('زر الرئيسية');
  const tCover = b.at('يغطي');
  const tGuard = b.at('الحارس');
  const tBack = b.at('ويعيد');

  const tvIn = 1; // continuity: same TV as the previous scene (pure cross-fade, no entrance)
  const press = tHome + 8;
  const launcher = tween(f, press + 4, 16, easeOut);
  const sweep = tween(f, tCover - 2, 26, easeInOut); // teal shield wipe right → left
  const guardIn = tween(f, tCover + 12, 12);
  const back = tween(f, tBack + 6, 14);

  const remoteOut = tween(f, tCover - 14, 12);
  const stepsIn = (i: number) => sp(f, tCover - 10 + i * 6, {damping: 22});
  const on1 = 1;
  const on2 = tween(f, tGuard - 4, 10);
  const on3 = tween(f, tBack, 10);

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker="جولة في الميزات" index="٦ / ٨" title="الحارس" />

      <TvFrame x={TV.x + (1 - tvIn) * -60} y={TV.y} w={TV.w} opacity={clamp(tvIn * 1.4)} glowColor={brand.kids}>
        <Screen name="kids_video" />
        {/* another app (the launcher) slides up over it */}
        {launcher > 0 && back < 1 ? (
          <div style={{position: 'absolute', inset: 0, transform: `translateY(${(1 - launcher) * 1080}px)`, opacity: 1 - back}}>
            <Launcher />
          </div>
        ) : null}
        <Screen name="guard_overlay" opacity={guardIn * (1 - back)} />
        {/* shield sweep */}
        {sweep > 0 && sweep < 1 ? (
          <div
            style={{
              position: 'absolute',
              top: 0,
              bottom: 0,
              left: mix(1920, -700, sweep),
              width: 700,
              background: `linear-gradient(90deg, transparent, ${rgba(brand.primary, 0.5)} 60%, ${rgba(brand.primary, 0.85)})`,
              display: 'grid',
              placeItems: 'center',
            }}
          >
            <Icon name="shield" size={260} color="#EAF0F6" stroke={1.6} />
          </div>
        ) : null}
        {/* back to Shasha: a soft teal edge flash */}
        <div style={{position: 'absolute', inset: 0, boxShadow: `inset 0 0 0 10px ${brand.primary}`, opacity: Math.sin(clamp((f - tBack - 6) / 24) * Math.PI) * 0.8}} />
      </TvFrame>

      <Remote x={1600} y={300} scale={0.95} rotate={-8} opacity={clamp(tvIn) * (1 - remoteOut)} presses={[{btn: 'home', f: press, color: brand.amber}]} />

      <div style={{position: 'absolute', right: 90, top: 300, display: 'flex', flexDirection: 'column', gap: 38, opacity: 1 - 0}}>
        <Step n="١" icon="home" label="ضغط زر Home" on={on1 * (1 - on2 * 0.6)} t={stepsIn(0) * remoteOut} color={brand.amber} />
        <Step n="٢" icon="shieldCheck" label="الحارس يغطي التطبيق الآخر" on={on2 * (1 - on3 * 0.6)} t={stepsIn(1) * remoteOut} color={brand.primary} />
        <Step n="٣" icon="tv" label="العودة إلى «شاشة»" on={on3} t={stepsIn(2) * remoteOut} color={brand.primary} />
      </div>
      <div style={{position: 'absolute', right: 90, top: 740}}>
        <Chip icon="warning" label="أفضل جهد — يعتمد على صلاحيات ADB" tone="amber" size={24} t={sp(f, tBack + 10, {damping: 20})} />
      </div>
    </div>
  );
};
