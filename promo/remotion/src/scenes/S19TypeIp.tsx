import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {CLine, ConsoleView, Desktop, Window} from '../components/Desktop';
import {INSTALL_LAPTOP, NoteChip, StepHeader} from '../components/Install';
import {camMix, LaptopDesk} from '../components/InstallKit';
import {sp, tween} from '../lib/anim';
import {TV_IP} from '../lib/demo';
import {IP_PROMPT, WIN_INSTALLER} from '../lib/installer';
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
  const lines: CLine[] = [
    {t: 0, text: '  Shasha installer / مثبّت «شاشة»', kind: 'banner'},
    {t: 0, text: '==> Checking adb ...', kind: 'say'},
    {t: 0, text: 'التحقق من وجود adb ...', kind: 'ar'},
    {t: 0, text: '    C:\\Users\\me\\Downloads\\Shasha-Setup\\tools\\windows\\adb.exe', kind: 'dim'},
    {t: 0, text: '    Android Debug Bridge version 1.0.41', kind: 'dim'},
    {t: tAsk - 4, text: `==> ${IP_PROMPT.en}`, kind: 'say'},
    {t: tAsk - 1, text: IP_PROMPT.ar, kind: 'ar'},
    {t: tAsk + 2, text: `    ${IP_PROMPT.example}`, kind: 'dim'},
    {t: tAsk + 4, text: TV_IP, kind: 'input', type: TYPE_RATE, typeFrom: typeAt, prompt: `    ${IP_PROMPT.label}`},
    {t: enterAt + 6, text: `==> Connecting to ${TV_IP}:5555 ...`, kind: 'say'},
    {t: enterAt + 9, text: `الاتصال بالشاشة ${TV_IP}:5555 ...`, kind: 'ar'},
  ];
  // zoomed view keeps the whole title bar in frame (visible desktop height 800/1.32 ≈ 606 px from y ≈ 23)
  const cam = camMix({s: 1, cx: 640, cy: 400}, {s: 1.32, cx: 520, cy: 326}, tween(f, tAsk - 6, 16));

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <StepHeader step={5} sub="اكتب عنوان التلفاز" />
      <LaptopDesk x={INSTALL_LAPTOP.x} y={INSTALL_LAPTOP.y} w={INSTALL_LAPTOP.w} cam={cam}>
        <Desktop os="win" bare>
          <Window os="win" x={40} y={30} w={1200} h={680} title={WIN_INSTALLER} dark>
            <ConsoleView lines={lines} />
          </Window>
        </Desktop>
      </LaptopDesk>
      <NoteChip x={1330} y={420} t={sp(f, typeAt, {damping: 20})} icon="keyboard" size={30}>
        <span style={{fontFamily: brand.mono, direction: 'ltr'}}>{TV_IP}</span>
      </NoteChip>
      <NoteChip x={1330} y={520} t={sp(f, enterAt - 4, {damping: 20})} icon="arrowRight" size={30}>
        ثم Enter ⏎
      </NoteChip>
    </div>
  );
};
