import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {AllowDebugDialog} from '../components/GoogleTv';
import {Icon} from '../components/Icons';
import {NoteChip, StepHeader} from '../components/Install';
import {Launcher} from '../components/Launcher';
import {Press, Remote} from '../components/Remote';
import {REMOTE_SCALE, REMOTE_X, sx, sy, TV} from '../components/TvStep';
import {TvFrame} from '../components/Tv';
import {clamp, sp, tween} from '../lib/anim';
import {dir, flowSign, isEn, t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// Step 6 (TV): «Allow USB debugging?» → tick «Always allow from this computer» → «OK» (within 60 s).
export const S20Allow: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tMsg = b.at('ستسألك');
  const tTick = b.at('ضع علامة');
  const tOk = b.at('ثم اضغط');
  const D = scene.duration_frames;

  const dlgIn = tween(f, tMsg - 4, 12);
  const tickAt = tTick + 16;
  const toOk = tOk - 4;
  const okAt = tOk + 12;
  const presses: Press[] = [
    {btn: 'ok', f: tickAt},
    {btn: 'down', f: toOk - 6},
    {btn: 'right', f: toOk},
    {btn: 'ok', f: okAt},
  ];
  const dlgOut = tween(f, okAt + 10, 10);
  const connected = sp(f, okAt + (isEn ? 12 : 16), {damping: 20}); // the English narration leaves less time after the click
  const secs = Math.max(0, 60 - Math.floor(Math.max(0, f - (tMsg - 4)) / 30));
  const ring = clamp((f - (tMsg - 4)) / (60 * 30));

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <StepHeader step={6} />
      <TvFrame x={TV.x} y={TV.y} w={TV.w}>
        <Launcher />
        {dlgOut < 1 ? (
          <div style={{position: 'absolute', inset: 0, opacity: 1 - dlgOut}}>
            <AllowDebugDialog t={dlgIn} checked={f >= tickAt ? 1 : 0} focus={f >= toOk ? 'allow' : 'check'} pressed={Math.sin(clamp((f - okAt) / 8) * Math.PI)} />
          </div>
        ) : null}
      </TvFrame>
      <Remote x={REMOTE_X} y={292} scale={REMOTE_SCALE} rotate={flowSign * -8} presses={presses} />
      {/* translation of the prompt, over the dimmed launcher */}
      <NoteChip x={sx(60)} y={sy(56)} t={sp(f, tMsg, {damping: 20}) * (1 - dlgOut)} icon="warning" tone="amber" size={26} en="Allow USB debugging?">
        {t('هل تسمح بتصحيح الأخطاء؟')}
      </NoteChip>
      <NoteChip x={sx(60)} y={sy(860)} t={sp(f, tTick - 4, {damping: 20}) * (1 - dlgOut)} icon="check" size={26} en="Always allow from this computer">
        {t('ضع علامة: السماح دائماً من هذا الكمبيوتر')}
      </NoteChip>
      {/* 60 s countdown */}
      <div style={{position: 'absolute', left: sx(1640), top: sy(60), opacity: dlgIn * (1 - dlgOut), display: 'flex', alignItems: 'center', gap: 12, direction: dir, fontFamily: brand.font}}>
        <svg width={70} height={70} viewBox="0 0 70 70">
          <circle cx={35} cy={35} r={29} stroke="rgba(234,240,246,0.18)" strokeWidth={6} fill="rgba(11,15,20,0.8)" />
          <circle cx={35} cy={35} r={29} stroke={brand.amber} strokeWidth={6} fill="none" strokeDasharray={`${(1 - ring) * 182} 182`} transform="rotate(-90 35 35)" strokeLinecap="round" />
          <text x={35} y={42} textAnchor="middle" fontSize={20} fontWeight={700} fill={brand.text} fontFamily="IBM Plex Mono">
            {secs}
          </text>
        </svg>
      </div>
      <div style={{position: 'absolute', left: sx(560), top: sy(430), opacity: clamp(connected * 1.4) * (1 - tween(f, D - (isEn ? 8 : 16), 10)), transform: `scale(${0.9 + 0.1 * connected})`}}>
        <div style={{display: 'flex', alignItems: 'center', gap: 16, padding: '18px 34px', borderRadius: 99, background: rgba(brand.primary, 0.16), border: `2px solid ${brand.primary}`, direction: dir, fontFamily: brand.font, fontSize: 36, fontWeight: 700, color: brand.text, boxShadow: `0 0 40px ${rgba(brand.primary, 0.35)}`}}>
          <Icon name="check" size={40} color={brand.primary} stroke={3} />
          {t('تم الاتصال بالكمبيوتر')}
        </div>
      </div>
    </div>
  );
};
