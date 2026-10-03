import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {PlayerPage} from '../components/MobileScreens';
import {Callout, MobileHeader, PhoneStage, TapAt} from '../components/MobileStage';
import {NotesPage, PipWindow} from '../components/MobileSystem';
import {easeInOut, mix, tween} from '../lib/anim';
import {digits, isEn, t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// m08 — picture in picture: one tap on the player's button, and the video becomes a small floating window above
// whatever else the phone is doing (here a shopping list that scrolls).

export const M08Pip: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tTap = b.at('وبلمسة');
  const tWin = b.at('نافذة');
  const tPip = b.at('صورة داخل صورة');
  const tFollow = b.at('فتتابعه');

  const glow = tween(f, tTap - 10, 8) * (1 - tween(f, tTap + 10, 8));
  const shrink = tween(f, tTap + 8, 22, easeInOut);
  const pageOut = tween(f, tTap + 6, 14);
  // the English narration is shorter: the scroll and the drag are squeezed into the time left after «so you keep watching»
  const pace = isEn ? 0.74 : 1;
  const scroll = tween(f, tFollow, 80 * pace, easeInOut) * 420;
  const drag = tween(f, tFollow + 70 * pace, 30 * pace, easeInOut);
  // the window: from the full-width video (0, 34, 390) to the corner window
  const w = mix(390, 176, shrink);
  const x = mix(0, mix(200, 24, drag), shrink);
  const y = mix(34, mix(612, 480, drag), shrink);

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <MobileHeader kicker={t('المشغّل')} index={digits('2 / 4')} title={t('صورة داخل صورة')} />
      <PhoneStage f={f} from={-60} glow={brand.primary}>
        <div style={{position: 'absolute', inset: 0, opacity: 1 - pageOut}}>
          <PlayerPage time={150 + f / 30} pipGlow={glow} />
        </div>
        <div style={{position: 'absolute', inset: 0, opacity: pageOut}}>
          <NotesPage scroll={scroll} />
        </div>
        {shrink > 0 ? <PipWindow x={x} y={y} w={w} seed={0} /> : null}
        <TapAt f={f} at={tTap} x={isEn ? 320 : 69} y={63} />
      </PhoneStage>

      <Callout f={f} at={tWin - 8} icon="layers" title={t('نافذة صغيرة عائمة')} top={300} />
      <Callout f={f} at={tPip - 4} icon="play" title={t('صورة داخل صورة')} tone="amber" top={470} />
      <Callout f={f} at={tFollow - 2} icon="user" title={t('تتابعه وأنت تعمل')} top={640} />
    </div>
  );
};
