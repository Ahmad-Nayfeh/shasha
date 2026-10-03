import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {HistoryPage, LibraryPage, PlayerPage} from '../components/MobileScreens';
import {Callout, MobileHeader, PhoneStage, TapAt} from '../components/MobileStage';
import {tween} from '../lib/anim';
import {digits, isEn, t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// m13 — history: «السجل» in the library, everything watched with its progress bar, tap one and it carries on from
// where it stopped.

export const M13History: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tHist = b.at('سجل');
  const tSeen = b.at('شاهدته');
  const tResume = b.at('فتكمل');
  const tStopped = b.at('توقفت');

  const tapHist = tHist + 4;
  const toHist = tween(f, tapHist + 6, 10);
  const tapRow = tResume + 6;
  const toPlayer = tween(f, tapRow + 8, 12);
  const hiRow = tween(f, tResume - 2, 8);

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <MobileHeader kicker={t('مكتبتك')} index={digits('3 / 3')} title={t('سجل المشاهدة')} />
      <PhoneStage f={f} from={-60} glow={brand.primary}>
        <LibraryPage hi={tween(f, tHist - 2, 8)} hiIndex={f >= tHist - 2 ? 1 : -1} />
        <div style={{position: 'absolute', inset: 0, opacity: toHist}}>
          <HistoryPage shown={5} hiRow={1} hi={hiRow} />
        </div>
        <div style={{position: 'absolute', inset: 0, opacity: toPlayer}}>
          <PlayerPage time={0.8 * 754} resume={tween(f, tapRow + 12, 10)} controls={1} />
        </div>
        <TapAt f={f} at={tapHist} x={isEn ? 288 : 102} y={163} />
        <TapAt f={f} at={tapRow} x={200} y={262} />
      </PhoneStage>

      <Callout f={f} at={tHist - 4} icon="clock" title={t('كل ما شاهدته')} sub={t('في السجل')} top={300} />
      <Callout f={f} at={tResume - 4} icon="play" title={t('تكمل من حيث توقفت')} top={470} />
      <Callout f={f} at={tStopped - 2} icon="check" title={t('بلا بحث من جديد')} top={640} tone="amber" />
    </div>
  );
};
