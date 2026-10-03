import React from 'react';
import {useCurrentFrame} from 'remotion';
import {CLine, ConsoleView, Desktop, timed, Window} from '../components/Desktop';
import {INSTALL_LAPTOP, NoteChip, SideSteps, StepHeader} from '../components/Install';
import {camMix, LaptopDesk} from '../components/InstallKit';
import {sp, tween} from '../lib/anim';
import {consoleAfterIp, WIN_INSTALLER} from '../lib/installer';
import {beatsFor, Scene} from '../lib/timeline';

// Step 7 (automatic): the installer installs Shasha, grants its permissions, asks about the official YouTube app
// (y/n), starts Shasha and prints its summary. Press any key to close.
export const S22Automatic: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tInstall = b.at('يثبت');
  const tGrant = b.at('ويمنحه');
  const tAsk = b.at('ثم يسألك');
  const tY = b.at('حرف واي');
  const tSummary = b.at('ثم يعرض');
  const C = consoleAfterIp();
  const yAt = tY + 12;
  const lines: CLine[] = [
    ...timed(0, C.connect.slice(0, 2), 0),
    ...timed(0, C.connected, 0),
    ...timed(tInstall - 4, C.install.slice(0, 2), 3),
    {t: tInstall + 30, text: '    Success', kind: 'ok'},
    ...timed(tGrant - 2, C.grant, 4),
    ...timed(tAsk - 2, C.youtube, 3),
    {t: tAsk + 10, text: 'y', kind: 'input', type: 0.3, typeFrom: yAt, prompt: `    ${C.youtubeAnswer}`},
    {t: yAt + 10, text: '    Disabled. To bring it back: / للإعادة:  (see the guide)', kind: 'dim'},
    ...timed(yAt + 16, C.start, 3),
    ...timed(tSummary - 2, C.summary, 3),
  ];
  const cam = camMix({s: 1, cx: 640, cy: 400}, {s: 1.12, cx: 640, cy: 470}, tween(f, tSummary - 6, 18));

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <StepHeader step={7} sub="البرنامج يكمل وحده" />
      <LaptopDesk x={INSTALL_LAPTOP.x} y={INSTALL_LAPTOP.y} w={INSTALL_LAPTOP.w} cam={cam}>
        <Desktop os="win" bare>
          <Window os="win" x={30} y={24} w={1220} h={700} title={WIN_INSTALLER} dark>
            <ConsoleView lines={lines} fontSize={19} lineH={30} maxLines={21} />
          </Window>
        </Desktop>
      </LaptopDesk>
      <SideSteps
        items={[
          {at: tInstall, label: 'يثبّت التطبيق', icon: 'download', done: tInstall + 30},
          {at: tGrant, label: 'يمنحه صلاحياته', icon: 'shieldCheck', done: tGrant + 22},
          {at: tAsk, label: 'يوتيوب الرسمي؟ اكتب y أو n', icon: 'keyboard', done: yAt + 8},
          {at: tSummary, label: 'الملخص: تمّ', icon: 'list', done: tSummary + 24},
        ]}
      />
      <NoteChip x={1330} y={790} t={sp(f, tSummary + 30, {damping: 20})} icon="keyboard" size={24}>
        اضغط أي زر لإغلاق النافذة
      </NoteChip>
    </div>
  );
};
