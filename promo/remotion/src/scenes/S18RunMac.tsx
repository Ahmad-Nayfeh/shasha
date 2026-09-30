import React from 'react';
import {useCurrentFrame} from 'remotion';
import {ConsoleView, ContextMenu, Desktop, FileManager, MacOpenDialog, Pointer, Window} from '../components/Desktop';
import {INSTALL_LAPTOP, SideSteps, StepHeader} from '../components/Install';
import {LaptopDesk} from '../components/InstallKit';
import {tween} from '../lib/anim';
import {KIT_DIR, KIT_FILES, MAC_INSTALLER} from '../lib/installer';
import {Beats, beatsFor, Scene} from '../lib/timeline';

// Step 5 (Mac): right-click the Mac installer → «Open», then «Open» again in the prompt.
const MAC_ROW = KIT_FILES.findIndex((k) => k.kind === 'command');
const tryAt = (b: Beats, p: string, fb: number) => {
  try {
    return b.at(p);
  } catch {
    return fb;
  }
};

export const S18RunMac: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tRight = b.at('انقر على');
  const tOpen = b.at('ثم اختر');  // «... أمر الفتح مرتين»
  const tOpen2 = tryAt(b, 'مرتين', tOpen + 26) + 12;

  const rc = tRight + 10;
  const menu = tween(f, rc + 1, 6);
  const click1 = tOpen + 8;
  const dlg = tween(f, click1 + 8, 8);
  const click2 = Math.max(tOpen2 + 8, click1 + 22);
  const dlgOut = tween(f, click2 + 4, 6);
  const con = tween(f, click2 + 8, 8);
  const rowY = 60 + 40 + 52 + 36 + MAC_ROW * 66 + 31;
  const files = KIT_FILES.map((k, i) => ({...k, highlight: i === MAC_ROW ? tween(f, tRight - 4, 8) : 0}));

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <StepHeader step={5} sub="شغّل المثبّت: ماك" />
      <LaptopDesk x={INSTALL_LAPTOP.x} y={INSTALL_LAPTOP.y} w={INSTALL_LAPTOP.w}>
        <Desktop os="mac">
          <Window os="mac" x={70} y={60} w={1140} h={600} title={KIT_DIR}>
            <FileManager os="mac" path={[KIT_DIR]} files={files} selected={f >= rc - 2 ? MAC_ROW : -1} rowH={62} />
          </Window>
          <ContextMenu os="mac" x={360} y={rowY + 10} t={menu * (f < click1 + 4 ? 1 : 0)} active={f >= tOpen - 4 ? 0 : -1} items={['Open', 'Open With', '-', 'Move to Trash', '-', 'Get Info', 'Rename', 'Compress', 'Duplicate']} />
          {dlg > 0 && dlgOut < 1 ? (
            <div style={{position: 'absolute', inset: 0, opacity: 1 - dlgOut}}>
              <MacOpenDialog t={dlg} file={MAC_INSTALLER} pressed={f >= click2 ? 1 : 0} />
            </div>
          ) : null}
          {con > 0 ? (
            <Window os="mac" x={160} y={100} w={960} h={560} title="Terminal" dark t={con}>
              <ConsoleView
                lines={[
                  {t: click2 + 12, text: '  Shasha installer / مثبّت «شاشة»', kind: 'banner'},
                  {t: click2 + 16, text: '==> Checking adb ...', kind: 'say'},
                  {t: click2 + 18, text: 'التحقق من وجود adb ...', kind: 'ar'},
                ]}
              />
            </Window>
          ) : null}
          <Pointer
            keys={[
              {f: 0, x: 900, y: 640},
              {f: rc - 6, x: 340, y: rowY},
              {f: click1 - 4, x: 400, y: rowY + 10 + 8 + 18},
              {f: click2 - 6, x: 400, y: rowY + 10 + 8 + 18},
              {f: click2 - 2, x: 780, y: 150 + 30 + 64 + 16 + 80 + 40 + 22 + 30},
            ]}
            right={[rc]}
            clicks={[click1, click2]}
          />
        </Desktop>
      </LaptopDesk>
      <SideSteps
        items={[
          {at: tRight, label: 'زر أيمن على مثبّت ماك', sub: MAC_INSTALLER},
          {at: tOpen, label: '«فتح»', sub: 'Open'},
          {at: tOpen2, label: 'ثم «فتح» مرة أخرى', sub: 'Open'},
        ]}
      />
    </div>
  );
};
