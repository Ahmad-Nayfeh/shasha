import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {Icon} from '../components/Icons';
import {clamp, mix, sp, tween} from '../lib/anim';
import {beatsFor, Scene} from '../lib/timeline';

// s15 — honestly: against YouTube's terms, personal use only; the lock is best effort, not unbreakable.

export const S15Disclaimer: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const lines: Array<[number, string]> = [
    [b.at('يخالف'), 'يخالف شروط استخدام يوتيوب'],
    [b.at('للاستخدام'), 'للاستخدام الشخصي فقط'],
    [b.at('والحارس'), 'الحارس أفضل جهد — ليس منيعاً'],
  ];
  const cardIn = sp(f, 4, {damping: 24, stiffness: 80});
  const kicker = tween(f, b.at('وبصراحة') - 4, 14);
  const tri = sp(f, 14, {damping: 16});
  return (
    <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center'}}>
      <AbsoluteFill style={{background: `radial-gradient(50% 50% at 50% 48%, ${rgba(brand.amber, 0.05)}, transparent 70%)`, opacity: cardIn}} />
      <div
        style={{
          width: 1180,
          padding: '64px 80px 70px',
          boxSizing: 'border-box',
          borderRadius: 40,
          background: 'linear-gradient(180deg, rgba(25,31,40,0.92), rgba(17,22,29,0.92))',
          border: `2px solid ${rgba(brand.amber, 0.55)}`,
          boxShadow: `0 40px 100px rgba(0,0,0,0.5), 0 0 60px ${rgba(brand.amber, 0.08)}`,
          opacity: clamp(cardIn * 1.3),
          transform: `translateY(${(1 - cardIn) * 24}px) scale(${mix(0.97, 1, cardIn)})`,
          display: 'flex',
          gap: 60,
          alignItems: 'flex-start',
          direction: 'rtl',
          marginTop: -40,
        }}
      >
        <div
          style={{
            width: 150,
            height: 150,
            borderRadius: 44,
            background: rgba(brand.amber, 0.12),
            display: 'grid',
            placeItems: 'center',
            flex: 'none',
            transform: `scale(${mix(0.7, 1, tri)})`,
            opacity: clamp(tri * 1.5),
          }}
        >
          <Icon name="warning" size={96} color={brand.amber} stroke={1.9} />
        </div>
        <div style={{flex: 1}}>
          <div style={{fontSize: 34, fontWeight: 600, color: brand.amber, opacity: kicker, transform: `translateY(${(1 - kicker) * 10}px)`}}>بصراحة</div>
          <div style={{marginTop: 18, display: 'flex', flexDirection: 'column', gap: 22}}>
            {lines.map(([t, text]) => {
              const p = sp(f, t - 4, {damping: 22});
              return (
                <div
                  key={text}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 22,
                    fontSize: 56,
                    fontWeight: 600,
                    color: brand.text,
                    opacity: clamp(p * 1.4),
                    transform: `translateX(${(1 - p) * -36}px)`,
                    lineHeight: 1.35,
                  }}
                >
                  <span style={{width: 14, height: 14, borderRadius: 99, background: brand.amber, flex: 'none', boxShadow: `0 0 14px ${rgba(brand.amber, 0.6)}`}} />
                  {text}
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </AbsoluteFill>
  );
};
