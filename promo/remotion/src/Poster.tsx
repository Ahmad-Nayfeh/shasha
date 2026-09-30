import React from 'react';
import {AbsoluteFill} from 'remotion';
import {brand, rgba} from './brand';
import {Background} from './components/Background';
import {LogoMark, Wordmark} from './components/Brand';
import {Screen, TvFrame} from './components/Tv';
import {Chip} from './components/ui';
import {loadFonts} from './fonts';

loadFonts();

// docs/video/poster.jpg — the title frame shown before the video plays: the TV with the real home screen on the
// left, the brand, the promise and what the video covers on the right (RTL start).
export const Poster: React.FC = () => {
  const markW = 170;
  const S = (markW / 751) * 1024;
  return (
    <AbsoluteFill style={{backgroundColor: brand.bg, direction: 'rtl', fontFamily: brand.font, color: brand.text}}>
      <Background />
      <TvFrame x={96} y={236} w={940} glow={1.4}>
        <Screen name="home_adult" />
      </TvFrame>
      <div style={{position: 'absolute', right: 110, top: 150, width: 700, display: 'flex', flexDirection: 'column', alignItems: 'flex-start'}}>
        <div style={{display: 'flex', alignItems: 'center', gap: 34}}>
          <div style={{width: markW, height: markW * 1.06, position: 'relative'}}>
            <div style={{position: 'absolute', left: -((S - markW) / 2) - S * 0.0, top: -S * 0.11}}>
              <LogoMark size={S} />
            </div>
          </div>
          <Wordmark width={330} />
        </div>
        <div style={{fontSize: 64, fontWeight: 700, lineHeight: 1.3, marginTop: 40}}>
          يوتيوب على التلفاز،
          <br />
          <span style={{color: brand.primaryBright}}>بهدوء، وبنيّة واضحة.</span>
        </div>
        <div style={{display: 'flex', flexWrap: 'wrap', gap: 14, marginTop: 36}}>
          <Chip icon="x" label="بلا إعلانات" size={26} tone="amber" />
          <Chip icon="star" label="ملف للأطفال" size={26} tone="kids" />
          <Chip icon="hourglass" label="حصص للوقت" size={26} />
          <Chip icon="phone" label="ريموت من جوالك" size={26} />
        </div>
        <div style={{marginTop: 44, display: 'flex', alignItems: 'center', gap: 18, padding: '16px 26px', borderRadius: 22, background: rgba(brand.primary, 0.1), border: `1.5px solid ${rgba(brand.primary, 0.4)}`}}>
          <svg width={44} height={44} viewBox="0 0 24 24">
            <circle cx={12} cy={12} r={11} fill={brand.primary} />
            <path d="M10 8l6 4-6 4z" fill={brand.onPrimary} />
          </svg>
          <div>
            <div style={{fontSize: 30, fontWeight: 700}}>الفيديو التعريفي</div>
            <div style={{fontSize: 24, color: brand.muted, marginTop: 2}}>الميزات، ثم التثبيت خطوة بخطوة</div>
          </div>
        </div>
      </div>
      <div style={{position: 'absolute', left: 0, right: 0, bottom: 34, textAlign: 'center', fontSize: 22, color: brand.faint}}>مشروع شخصي — غير تابع لـ YouTube أو Google</div>
    </AbsoluteFill>
  );
};
