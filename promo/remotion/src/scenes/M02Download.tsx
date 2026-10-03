import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {Callout, MobileHeader, PhoneStage, TapAt} from '../components/MobileStage';
import {BrowserPage} from '../components/MobileSystem';
import {easeInOut, tween} from '../lib/anim';
import {beatsFor, Scene} from '../lib/timeline';

// m02 — the APK from the release page on GitHub: tap the phone app's file, the download finishes, open it.

export const M02Download: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tStart = b.at('ابدأ');
  const tFile = b.at('ملف التطبيق');
  const tGh = b.at('غيت هب');
  const tOpen = b.at('وافتحه');

  const scroll = tween(f, tStart + 4, 36, easeInOut) * 150;
  const hiAsset = tween(f, tFile - 2, 12);
  const tapFile = tFile + 14;
  const tapGlow = Math.max(0, Math.sin(Math.min(1, Math.max(0, (f - tapFile) / 16)) * Math.PI));
  const snack = tween(f, tGh + 14, 12);
  const tapOpen = tOpen + 8;

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <MobileHeader kicker="تثبيت التطبيق" index="١ / ٢" title="نزّل ملف التطبيق" />
      <PhoneStage f={f} from={-60} glow={brand.primary}>
        <BrowserPage scroll={scroll} hiAsset={hiAsset} tap={tapGlow} snack={snack} />
        <TapAt f={f} at={tapFile} x={200} y={314 - scroll} />
        <TapAt f={f} at={tapOpen} x={46} y={790} />
      </PhoneStage>

      <Callout f={f} at={tFile - 8} icon="download" title="١ · نزّل الملف" sub="من صفحة «شاشة» على GitHub" top={300} />
      <Callout f={f} at={tOpen - 6} icon="apk" title="٢ · افتحه" sub="من إشعار التنزيل" top={470} />
    </div>
  );
};
