import React from 'react';
import {AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig, Easing} from 'remotion';
import {brand} from './brand';
import {loadFonts} from './fonts';
import {LogoMark} from './components/Brand';

loadFonts();

export const TestArabic: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const pop = spring({frame, fps, config: {damping: 14}});
  const dots = interpolate(frame, [10, 40], [30, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: Easing.out(Easing.cubic)});
  const titleIn = interpolate(frame, [25, 50], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const subIn = interpolate(frame, [45, 75], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const chips = ['بلا إعلانات', 'بلا خوارزمية', 'حصص وقت', 'ملف أطفال', 'قفل الفيديو', 'ريموت من الجوال'];
  return (
    <AbsoluteFill style={{backgroundColor: brand.bg, direction: 'rtl', fontFamily: brand.font, color: brand.text, alignItems: 'center', justifyContent: 'center'}}>
      <div style={{transform: `scale(${pop})`}}>
        <LogoMark size={260} dots={[{dy: -dots * 4, opacity: Math.min(1, frame / 25)}, {dy: -dots * 4, opacity: Math.min(1, frame / 25)}, {dy: -dots * 4, opacity: Math.min(1, frame / 25)}]} />
      </div>
      <div style={{fontSize: 150, fontWeight: 700, lineHeight: 1.1, opacity: titleIn, transform: `translateY(${(1 - titleIn) * 30}px)`}}>شاشة</div>
      <div style={{fontSize: 52, fontWeight: 500, opacity: subIn, color: brand.primary, marginTop: 10}}>
        يوتيوب على التلفزيون، بهدوء وبنيّة واضحة.
      </div>
      <div style={{display: 'flex', gap: 22, marginTop: 50, flexDirection: 'row'}}>
        {chips.map((c, i) => {
          const t = interpolate(frame, [70 + i * 8, 85 + i * 8], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
          return (
            <div key={c} style={{opacity: t, transform: `translateY(${(1 - t) * 20}px)`, background: brand.surface, border: `2px solid ${i === 3 ? brand.kids : brand.primary}`, borderRadius: 999, padding: '14px 30px', fontSize: 34, fontWeight: 600}}>
              {c}
            </div>
          );
        })}
      </div>
      <div style={{position: 'absolute', bottom: 40, left: 60, fontSize: 26, color: brand.amber, direction: 'rtl'}}>
        اختبار عرض: الإطار {frame + 1} من 150 — ١٩٢٠×١٠٨٠ • ٣٠ إطاراً/ثانية
      </div>
    </AbsoluteFill>
  );
};
