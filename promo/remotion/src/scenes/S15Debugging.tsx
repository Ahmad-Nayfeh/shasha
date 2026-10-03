import React from 'react';
import {useCurrentFrame} from 'remotion';
import {NoteChip, StepHeader} from '../components/Install';
import {Press} from '../components/Remote';
import {Crumbs, RowHalo, sx, sy, TvStepStage} from '../components/TvStep';
import {easeInOut, keys, sp, tween} from '../lib/anim';
import {beatsFor, Scene} from '../lib/timeline';
import {DEV_ROW, P_ABOUT, P_DEVELOPER, P_SYSTEM, rowRect, SYSTEM_ROW} from '../lib/tvPanels';

// Step 3 (TV): back to System → Developer options → «USB debugging» on, then «Network debugging» if the TV has it.
export const S15Debugging: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tBack = b.at('ارجع');
  const tOpenDev = b.at('وافتح خيارات');
  const tUsb = b.at('فعل تصحيح');
  const tNet = b.at('وفعل تصحيح'); // «وفعّل تصحيح الشبكة إن وجدته»
  const tNetOn = tNet + 10;

  const backAt = tBack + 2;
  const downs1 = Array.from({length: SYSTEM_ROW.developer}, (_, i) => Math.min(backAt + 14 + i * 5, tOpenDev + i * 4));
  const okDev = Math.max(tOpenDev + 10, downs1[downs1.length - 1] + 8);
  const downs2 = [okDev + 18, okDev + 24];
  const okUsb = Math.max(tUsb + 8, downs2[1] + 8);
  const downNet = tNet + 6;
  const okNet = Math.max(tNetOn + 2, downNet + 10);
  const usb = tween(f, okUsb + 2, 6);
  const net = tween(f, okNet + 2, 6);
  const panel = tween(f, backAt + 2, 14, easeInOut) + tween(f, okDev + 2, 14, easeInOut);
  const focus = [
    7,
    keys(f, [[downs1[0], 0], [downs1[downs1.length - 1] + 5, SYSTEM_ROW.developer]], easeInOut),
    keys(f, [[downs2[0], 0], [downs2[1] + 5, DEV_ROW.usb], [downNet, DEV_ROW.usb], [downNet + 6, DEV_ROW.net]], easeInOut),
  ];
  const presses: Press[] = [
    {btn: 'back', f: backAt},
    ...downs1.map((t) => ({btn: 'down' as const, f: t})),
    {btn: 'ok', f: okDev},
    ...downs2.map((t) => ({btn: 'down' as const, f: t})),
    {btn: 'ok', f: okUsb},
    {btn: 'down', f: downNet},
    {btn: 'ok', f: okNet},
  ];
  const onDev = tween(f, okDev + 8, 10);

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <StepHeader step={3} />
      <TvStepStage panels={[P_ABOUT, P_SYSTEM(true), P_DEVELOPER(usb, net)]} panel={panel} focus={focus} open={1} presses={presses}>
        <RowHalo rect={rowRect(DEV_ROW.usb)} t={onDev * tween(f, okUsb, 8) * (1 - tween(f, downNet, 8)) * 0.9} color="#2EC4B6" />
        <RowHalo rect={rowRect(DEV_ROW.net)} t={tween(f, okNet, 8) * 0.9} color="#2EC4B6" />
      </TvStepStage>
      <Crumbs
        items={[
          {ar: 'الإعدادات', en: 'Settings', at: 0},
          {ar: 'النظام', en: 'System', at: backAt},
          {ar: 'خيارات المطوّر', en: 'Developer options', at: okDev},
        ]}
      />
      <NoteChip x={sx(60)} y={sy(300)} t={sp(f, okUsb, {damping: 20})} icon="check" en="USB debugging">
        فعّل: تصحيح أخطاء USB
      </NoteChip>
      <NoteChip x={sx(60)} y={sy(300) + 96} t={sp(f, okNet, {damping: 20})} icon="check" en="Network debugging">
        وفعّل تصحيح الشبكة إن وجدته
      </NoteChip>
    </div>
  );
};
