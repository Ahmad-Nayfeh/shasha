import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {SettingsPage, UpdatePage} from '../components/MobileScreens';
import {Callout, MobileHeader, PhoneStage, TapAt} from '../components/MobileStage';
import {easeInOut, tween} from '../lib/anim';
import {t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// m14 — updates with one button: Settings → «فحص التحديثات» → «نزّل وثبّت»; the download and the install go on
// by themselves (the system may ask for a confirmation).

export const M14Update: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tNew = b.at('صدر');
  const tPress = b.at('فاضغط');
  const tOne = b.at('زرا');
  const tStart = b.at('فيبدأ');
  const tInst = b.at('والتثبيت');

  const tapRow = tPress + 4;
  const toUpdate = tween(f, tapRow + 6, 10);
  const tapBtn = tOne + 12;
  const glow = tween(f, tPress + 12, 10) * (1 - tween(f, tapBtn + 6, 8));
  const dl = tween(f, tStart, Math.max(24, tInst - tStart - 8), easeInOut);
  const state = f >= tInst + 4 ? 'installing' : f >= tapBtn + 6 ? 'downloading' : 'available';

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <MobileHeader kicker={t('التحديث')} title={t('التحديث بضغطة واحدة')} />
      <PhoneStage f={f} from={-60} glow={brand.primary}>
        <SettingsPage hiUpdate={tween(f, tPress - 2, 8)} />
        <div style={{position: 'absolute', inset: 0, opacity: toUpdate}}>
          <UpdatePage state={state} progress={dl} glow={glow} />
        </div>
        <TapAt f={f} at={tapRow} x={195} y={632} />
        <TapAt f={f} at={tapBtn} x={195} y={373} />
      </PhoneStage>

      <Callout f={f} at={tNew - 4} icon="download" title={t('إصدار جديد')} sub={t('١٫٢٫٠')} top={300} tone="amber" />
      <Callout f={f} at={tOne - 4} icon="check" title={t('زر واحد')} sub={t('«نزّل وثبّت»')} top={470} />
      <Callout f={f} at={tStart - 2} icon="refresh" title={t('وحدهما')} sub={t('التنزيل ثم التثبيت')} top={640} />
    </div>
  );
};
