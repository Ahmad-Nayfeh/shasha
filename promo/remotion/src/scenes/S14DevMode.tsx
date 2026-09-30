import React from 'react';
import {useCurrentFrame} from 'remotion';
import {StepHeader, NoteChip} from '../components/Install';
import {Press} from '../components/Remote';
import {Crumbs, PressCounter, sx, sy, TvStepStage} from '../components/TvStep';
import {easeInOut, keys, sp, tween} from '../lib/anim';
import {beatsFor, Scene} from '../lib/timeline';
import {ABOUT_ROW, P_ABOUT, P_SETTINGS, P_SYSTEM, SETTINGS_ROW, SYSTEM_ROW} from '../lib/tvPanels';

// Step 2 (TV): Settings → System → About → «Android TV OS build» → the centre button ×7 → "You are now a developer!"
export const S14DevMode: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tOpen = b.at('افتح الإعدادات');
  const tSystem = b.at('ثم النظام');
  const tAbout = b.at('ثم حول');
  const tDown = b.at('انزل');
  const tPress = b.at('واضغط الزر');
  const tShow = b.at('حتى تظهر');

  const open = tween(f, tOpen - 4, 16, easeInOut);
  const downs0 = [0, 1, 2, 3, 4].map((i) => tOpen + 12 + i * 5);
  const okSystem = Math.max(tSystem + 2, downs0[4] + 8);
  const okAbout = Math.max(tAbout + 2, okSystem + 18);
  const downs2 = Array.from({length: ABOUT_ROW.build}, (_, i) => tDown + 4 + i * 4);
  const gap = Math.max(7, Math.min(11, Math.floor((tShow - tPress - 4) / 7)));
  const taps = Array.from({length: 7}, (_, i) => tPress + 4 + i * gap);
  const panel = tween(f, okSystem + 2, 14, easeInOut) + tween(f, okAbout + 2, 14, easeInOut);
  const focus = [
    keys(f, [[downs0[0], 0], [downs0[4] + 6, SETTINGS_ROW.system]], easeInOut),
    SYSTEM_ROW.about,
    keys(f, [[downs2[0], 0], [downs2[downs2.length - 1] + 5, ABOUT_ROW.build]], easeInOut),
  ];
  const nTaps = taps.filter((t) => f >= t).length;
  const toastText = nTaps >= 7 ? 'You are now a developer!' : nTaps >= 3 ? `You are now ${7 - nTaps} step${7 - nTaps === 1 ? '' : 's'} away from being a developer.` : '';
  const presses: Press[] = [
    ...downs0.map((t) => ({btn: 'down' as const, f: t})),
    {btn: 'ok', f: okSystem},
    {btn: 'ok', f: okAbout},
    ...downs2.map((t) => ({btn: 'down' as const, f: t})),
    ...taps.map((t) => ({btn: 'ok' as const, f: t})),
  ];
  const tvIn = sp(f, 0, {damping: 24, stiffness: 90});
  const devChip = sp(f, taps[6] + 4, {damping: 20});

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <StepHeader step={2} />
      <TvStepStage panels={[P_SETTINGS, P_SYSTEM(false), P_ABOUT]} panel={panel} focus={focus} open={open} presses={presses} tvIn={tvIn} toast={toastText ? {text: toastText, opacity: 1} : null} />
      <Crumbs
        items={[
          {ar: 'الإعدادات', en: 'Settings', at: tOpen},
          {ar: 'النظام', en: 'System', at: okSystem},
          {ar: 'حول', en: 'About', at: okAbout},
          {ar: 'إصدار النظام', en: 'Android TV OS build', at: tDown + 8},
        ]}
      />
      {f >= tPress ? <PressCounter taps={taps} /> : null}
      <NoteChip x={sx(60)} y={sy(420)} t={devChip} icon="check" en="You are now a developer!">
        أصبحت مطوّراً
      </NoteChip>
      <NoteChip x={sx(60)} y={sy(420)} t={tween(f, tPress - 6, 10) * (1 - tween(f, taps[6], 6))} icon="arrowRight" size={24}>
        اضغط الزر الأوسط (OK) سبع مرات
      </NoteChip>
    </div>
  );
};
