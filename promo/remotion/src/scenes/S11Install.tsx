import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {Icon} from '../components/Icons';
import {mirrorX, NoteChip, SectionCard, STEPS} from '../components/Install';
import {Laptop} from '../components/Laptop';
import {Launcher} from '../components/Launcher';
import {TvFrame} from '../components/Tv';
import {clamp, sp, tween} from '../lib/anim';
import {digits, dir, flowSign, isEn, t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// Installation, intro: what you need (a personal Windows or Mac computer on the TV's Wi-Fi), the eight steps.
// Arabic layout: the TV on the right (where reading starts) and the computer on the left; the English version mirrors it.
const TV_X = mirrorX(1010, 760);
const LAPTOP_X = mirrorX(130, 640);
/** Stage x of the Arabic layout's point x (mirrored in English). */
const mx = (x: number) => (isEn ? 1920 - x : x);

export const S11Install: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tNeed = b.at('تحتاج');
  const tPc = b.at('كمبيوتر شخصي');
  const tOs = b.at('ويندوز');
  const tNet = b.at('على شبكة');
  const D = scene.duration_frames;

  const cardIn = tween(f, 0, 18);
  const cardOut = tween(f, tNeed - 12, 14);
  const tvIn = sp(f, tNeed - 8, {damping: 22});
  const lapIn = sp(f, tPc - 4, {damping: 22});
  const wifi = tween(f, tNet - 2, 16);
  return (
    <div style={{position: 'absolute', inset: 0}}>
      <SectionCard title={t('التثبيت')} sub={t('{n} خطوات · نحو ربع ساعة · مرة واحدة', {n: digits(STEPS.length)})} t={cardIn} out={cardOut} />
      {/* the steps under the title */}
      <div style={{position: 'absolute', left: 260, right: 260, top: 760, display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14, direction: dir, fontFamily: brand.font, opacity: cardIn * (1 - cardOut)}}>
        {STEPS.map((s, i) => (
          <div key={i} style={{display: 'flex', alignItems: 'center', gap: 12, padding: '10px 18px', borderRadius: 99, background: 'rgba(234,240,246,0.06)', border: '1px solid rgba(234,240,246,0.1)', fontSize: 24, fontWeight: 600, color: brand.text, opacity: clamp(tween(f, 6 + i * 3, 12)), whiteSpace: 'nowrap'}}>
            <span style={{width: 34, height: 34, borderRadius: 99, background: rgba(brand.primary, 0.22), display: 'grid', placeItems: 'center', fontSize: 19, flex: 'none'}}>{digits(i + 1)}</span>
            {s.title}
          </div>
        ))}
      </div>
      {cardOut > 0 ? (
        <>
          {/* the TV (right, RTL start) and the computer (left), joined by the same Wi-Fi */}
          <div style={{opacity: clamp(tvIn * 1.4), transform: `translateX(${flowSign * (1 - tvIn) * 40}px)`}}>
            <TvFrame x={TV_X} y={250} w={760}>
              <Launcher />
            </TvFrame>
          </div>
          <div style={{position: 'absolute', ...(isEn ? {left: TV_X, width: 760, textAlign: 'center' as const} : {left: 1010 + 380 - 60}), top: 250 + 440 + (isEn ? 28 : 0), fontFamily: brand.font, fontSize: 34, fontWeight: 700, color: brand.text, direction: dir, opacity: clamp(tvIn * 1.4)}}>{t('التلفاز')}</div>
          <Laptop x={LAPTOP_X} y={270} w={640} opacity={clamp(lapIn * 1.4)}>
            <div style={{position: 'absolute', inset: 0, background: 'radial-gradient(120% 90% at 70% 20%, #1D4A7A, #0A1426)'}} />
          </Laptop>
          <div style={{position: 'absolute', left: LAPTOP_X, width: 640, top: 270 + 440, textAlign: 'center', fontFamily: brand.font, direction: dir, opacity: clamp(lapIn * 1.4)}}>
            <div style={{fontSize: 34, fontWeight: 700, color: brand.text}}>{t('كمبيوتر شخصي')}</div>
            <div style={{fontSize: 28, fontWeight: 600, color: brand.primaryBright, marginTop: 4, opacity: tween(f, tOs - 2, 10)}}>{t('ماك أو ويندوز')}</div>
          </div>
          {/* Wi-Fi link */}
          <svg width={1920} height={1080} style={{position: 'absolute', inset: 0}}>
            <path d={`M${mx(800)} 470 Q ${mx(905)} 380 ${mx(1000)} 470`} stroke={brand.primary} strokeWidth={5} fill="none" strokeDasharray="10 12" strokeLinecap="round" opacity={wifi} strokeDashoffset={-f * 1.5} />
          </svg>
          <div style={{position: 'absolute', left: mx(905) - 60, top: 330, width: 120, textAlign: 'center', opacity: wifi, transform: `scale(${0.8 + 0.2 * wifi})`}}>
            <div style={{width: 96, height: 96, margin: '0 auto', borderRadius: 99, background: rgba(brand.primary, 0.14), border: `2px solid ${brand.primary}`, display: 'grid', placeItems: 'center'}}>
              <Icon name="wifi" size={56} color={brand.primary} stroke={2.4} />
            </div>
          </div>
          <div style={{position: 'absolute', left: mx(905) - 200, width: 400, top: 540, textAlign: 'center', fontFamily: brand.font, fontSize: 28, fontWeight: 600, color: brand.text, direction: dir, opacity: wifi}}>{t('الشبكة نفسها')}</div>
          <NoteChip x={LAPTOP_X} y={830} t={sp(f, tOs + 16, {damping: 20})} icon="x" tone="amber" size={24}>
            {t('لا يصلح كمبيوتر عمل يمنع تشغيل البرامج')}
          </NoteChip>
          <NoteChip x={TV_X} y={830} t={sp(f, tNet + 20, {damping: 20})} icon="clock" size={24}>
            {t('نحو ربع ساعة، مرة واحدة فقط')}
          </NoteChip>
        </>
      ) : null}
    </div>
  );
};
