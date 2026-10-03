import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {Callout, MobileHeader, PhoneStage, TapAt} from '../components/MobileStage';
import {BrowserPage, InstallPrompt, UnknownSourceDialog, UnknownSourcesPage} from '../components/MobileSystem';
import {easeInOut, sp, tween} from '../lib/anim';
import {beatsFor, Scene} from '../lib/timeline';

// m03 — the first install from this source: the phone asks (allow installs from this source once), the switch goes
// on, «تثبيت», done. It never asks again.

export const M03Allow: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tAsk = b.at('سيسألك');
  const tQ = b.at('هل تسمح');
  const tAllow = b.at('اسمح له');
  const tPress = b.at('اضغط تثبيت');
  const tNever = b.at('ولن');

  const dialog = sp(f, tAsk - 4, {damping: 22}) * (1 - tween(f, tAllow + 10, 8));
  const tapSettings = tAllow + 4;
  const page = tween(f, tapSettings + 8, 10) * (1 - tween(f, tPress - 12, 10));
  const sw = tween(f, tAllow + 18, 12, easeInOut);
  const prompt = tween(f, tPress - 12, 10);
  const tapInstall = tPress + 10;
  const installing = tween(f, tapInstall + 6, 4);
  const done = tween(f, tNever - 2, 6);

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <MobileHeader kicker="تثبيت التطبيق" index="٢ / ٢" title="اسمح لمرة واحدة" />
      <PhoneStage f={f} from={-60} glow={brand.primary}>
        <BrowserPage scroll={150} hiAsset={0} snack={1} />
        <div style={{position: 'absolute', inset: 0}}>
          <UnknownSourceDialog t={dialog} hi={tween(f, tAllow - 4, 8)} />
        </div>
        <div style={{position: 'absolute', inset: 0, opacity: page}}>
          <UnknownSourcesPage p={sw} glow={Math.max(0, Math.sin(Math.min(1, Math.max(0, (f - tAllow - 14) / 24)) * Math.PI))} />
        </div>
        <div style={{position: 'absolute', inset: 0, opacity: prompt}}>
          <InstallPrompt state={done > 0.5 ? 'done' : installing > 0.5 ? 'installing' : 'ask'} tapInstall={Math.max(0, 1 - Math.abs(f - tapInstall) / 8)} />
        </div>
        <TapAt f={f} at={tapSettings} x={290} y={438} />
        <TapAt f={f} at={tAllow + 14} x={338} y={176} />
        <TapAt f={f} at={tapInstall} x={320} y={776} />
      </PhoneStage>

      <Callout f={f} at={tQ - 4} icon="unlock" title="السماح بالتثبيت" sub="من هذا المصدر" top={300} />
      <Callout f={f} at={tPress - 6} icon="apk" title="ثم «تثبيت»" top={470} />
      <Callout f={f} at={tNever - 4} icon="check" title="مرة واحدة فقط" sub="ولن يسألك مرة أخرى" top={640} />
    </div>
  );
};
