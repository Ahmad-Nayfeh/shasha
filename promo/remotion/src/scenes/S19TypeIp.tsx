import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {CLine, ConsoleView, Desktop, timed, Window} from '../components/Desktop';
import {COL_X, INSTALL_LAPTOP, NoteChip, StepHeader} from '../components/Install';
import {camMix, LaptopDesk} from '../components/InstallKit';
import {sp, tween} from '../lib/anim';
import {TV_IP} from '../lib/demo';
import {consoleAfterIp, IP_PROMPT_LABEL, ipPromptLines, openingLines, WIN_INSTALLER} from '../lib/installer';
import {isEn, t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// Step 5 (the prompt): the installer asks «اكتب عنوان IP للشاشة» → type the address noted in step 4 → Enter.
export const TYPE_RATE = 0.45; // chars per frame

export const S19TypeIp: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tAsk = b.at('سيسألك');
  const tType = b.at('اكتب العنوان');
  const tEnter = b.at('ثم اضغط');

  const typeAt = tType + 6;
  const enterAt = Math.max(tEnter + 8, typeAt + Math.ceil(TV_IP.length / TYPE_RATE) + 6);
  const C = consoleAfterIp(`${TV_IP}:5555`);
  const lines: CLine[] = [
    ...openingLines([0, 0, 0]),
    {t: 0, text: isEn ? '    "C:\\Users\\me\\Downloads\\Shasha-Setup\\tools\\windows\\adb.exe"' : '    C:\\Users\\me\\Downloads\\Shasha-Setup\\tools\\windows\\adb.exe', kind: 'dim'}, // the English version quotes the path, as the installer prints it
    {t: 0, text: '    Android Debug Bridge version 1.0.41', kind: 'dim'},
    ...ipPromptLines(tAsk),
    {t: tAsk + 4, text: TV_IP, kind: 'input', type: TYPE_RATE, typeFrom: typeAt, prompt: IP_PROMPT_LABEL},
    ...timed(enterAt + 6, C.connect.filter((l) => l.kind !== 'dim'), 3), // «Connecting to …» and, in the Arabic version, its Arabic line
  ];
  // zoomed view keeps the whole title bar in frame (visible desktop height 800/1.32 ≈ 606 px from y ≈ 23); the English
  // prompt has the installer's long «Google TV» line, so its console is set smaller and zoomed less (86 characters fit)
  const cam = camMix({s: 1, cx: 640, cy: 400}, {s: isEn ? 1.18 : 1.32, cx: 520, cy: 326}, tween(f, tAsk - 6, 16));

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <StepHeader step={5} sub={t('اكتب عنوان التلفاز')} />
      <LaptopDesk x={INSTALL_LAPTOP.x} y={INSTALL_LAPTOP.y} w={INSTALL_LAPTOP.w} cam={cam}>
        <Desktop os="win" bare>
          <Window os="win" x={40} y={30} w={1200} h={680} title={WIN_INSTALLER} dark>
            <ConsoleView lines={lines} fontSize={isEn ? 19 : 21} lineH={isEn ? 31 : 33} />
          </Window>
        </Desktop>
      </LaptopDesk>
      <NoteChip x={COL_X} y={420} t={sp(f, typeAt, {damping: 20})} icon="keyboard" size={30}>
        <span style={{fontFamily: brand.mono, direction: 'ltr'}}>{TV_IP}</span>
      </NoteChip>
      <NoteChip x={COL_X} y={520} t={sp(f, enterAt - 4, {damping: 20})} icon="arrowRight" size={30}>
        {t('ثم Enter ⏎')}
      </NoteChip>
    </div>
  );
};
