import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {DownloadSheet, PlayerPage} from '../components/MobileScreens';
import {Callout, MobileHeader, PhoneStage, SidePill, TapAt} from '../components/MobileStage';
import {sp, tween} from '../lib/anim';
import {digits, isEn, t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// m11 — downloads: the sheet offers the whole video (MP4 with sound) or the sound only (M4A); afterwards it plays
// without the internet, anywhere.

export const M11Downloads: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tDl = b.at('وللتنزيل');
  const tVideo = b.at('الفيديو');
  const tAudio = b.at('الصوت');
  const tNet = b.at('إنترنت');
  const tAny = b.at('مكان');

  const sheet = sp(f, tDl + 2, {damping: 22});
  const hiVideo = tween(f, tVideo - 2, 10) * (1 - tween(f, tAudio - 4, 10));
  const hiAudio = tween(f, tAudio - 2, 10);
  const dlGlow = tween(f, tDl - 10, 8) * (1 - tween(f, tDl + 8, 8));

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <MobileHeader kicker={t('مكتبتك')} index={digits('1 / 3')} title={t('التنزيل')} />
      <PhoneStage f={f} from={-60} glow={brand.primary}>
        <PlayerPage time={150 + f / 30} dlGlow={dlGlow} />
        <div style={{position: 'absolute', inset: 0}}>
          <DownloadSheet t={sheet} hiVideo={hiVideo} hiAudio={hiAudio} />
        </div>
        <TapAt f={f} at={tDl - 8} x={isEn ? 70 : 330} y={400} />
      </PhoneStage>

      <Callout f={f} at={tVideo - 6} icon="tv" title={t('الفيديو كاملاً')} sub={t('MP4 بالصوت')} top={300} />
      <Callout f={f} at={tAudio - 6} icon="mic" title={t('الصوت فقط')} sub={t('M4A: أخف وأسرع')} top={470} tone="amber" />
      <SidePill f={f} at={tNet - 8} icon="wifi" label={t('إنترنت')} top={640} crossAt={tNet + 14} />
      <Callout f={f} at={tAny - 4} icon="home" title={t('في أي مكان')} top={770} width={420} />
    </div>
  );
};
