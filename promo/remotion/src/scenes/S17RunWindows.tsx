import React from 'react';
import {useCurrentFrame} from 'remotion';
import {ConsoleView, Desktop, FileManager, Pointer, SmartScreen, Window} from '../components/Desktop';
import {INSTALL_LAPTOP, SideSteps, StepHeader} from '../components/Install';
import {LaptopDesk} from '../components/InstallKit';
import {tween} from '../lib/anim';
import {KIT_DIR, KIT_FILES, KIT_ROW_H, WIN_INSTALLER} from '../lib/installer';
import {beatsFor, Scene} from '../lib/timeline';

// Step 5 (Windows): double-click the Windows installer; SmartScreen → «More info» → «Run anyway».
export const WIN_ROW = KIT_FILES.findIndex((k) => k.kind === 'bat');

export const S17RunWindows: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tDbl = b.at('انقر مرتين');
  const tWarn = b.at('وإن ظهر');
  const tMore = b.at('فاختر مزيد');
  const tRun = b.at('ثم تشغيل');

  const dbl = tDbl + 10;
  const ss = tween(f, Math.max(tWarn - 2, dbl + 14), 8);
  const clickMore = tMore + 10;
  const more = tween(f, clickMore + 2, 6);
  const clickRun = tRun + 14;
  const ssOut = tween(f, clickRun + 4, 6);
  const con = tween(f, clickRun + 8, 8);
  const rowY = 50 + 44 + 52 + 36 + WIN_ROW * (KIT_ROW_H + 4) + 2 + KIT_ROW_H / 2;
  const files = KIT_FILES.map((k, i) => ({...k, highlight: i === WIN_ROW ? tween(f, tDbl - 6, 8) : 0}));

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <StepHeader step={5} sub="شغّل المثبّت: ويندوز" />
      <LaptopDesk x={INSTALL_LAPTOP.x} y={INSTALL_LAPTOP.y} w={INSTALL_LAPTOP.w}>
        <Desktop os="win">
          <Window os="win" x={60} y={50} w={1160} h={640} title={KIT_DIR}>
            <FileManager os="win" path={['This PC', 'Downloads', KIT_DIR]} files={files} selected={f >= dbl ? WIN_ROW : -1} rowH={KIT_ROW_H} />
          </Window>
          {ss > 0 && ssOut < 1 ? (
            <div style={{position: 'absolute', inset: 0, opacity: 1 - ssOut}}>
              <SmartScreen t={ss} more={more} file={WIN_INSTALLER} runPressed={Math.max(0, Math.sin(Math.min(1, Math.max(0, (f - clickRun) / 8)) * Math.PI))} />
            </div>
          ) : null}
          {con > 0 ? (
            <Window os="win" x={140} y={80} w={1000} h={600} title={WIN_INSTALLER} dark t={con}>
              <ConsoleView
                lines={[
                  {t: clickRun + 12, text: '  Shasha installer / مثبّت «شاشة»', kind: 'banner'},
                  {t: clickRun + 16, text: '==> Checking adb ...', kind: 'say'},
                  {t: clickRun + 18, text: 'التحقق من وجود adb ...', kind: 'ar'},
                ]}
              />
            </Window>
          ) : null}
          <Pointer
            keys={[
              {f: 0, x: 900, y: 600},
              {f: dbl - 8, x: 330, y: rowY},
              {f: clickMore - 8, x: 330, y: rowY},
              {f: clickMore - 2, x: 260, y: 90 + 46 + 40 + 24 + 90 + 30},
              {f: clickRun - 8, x: 260, y: 270},
              {f: clickRun - 2, x: 900, y: 90 + 560 - 40 - 24},
            ]}
            clicks={[dbl, dbl + 7, clickMore, clickRun]}
          />
        </Desktop>
      </LaptopDesk>
      <SideSteps
        items={[
          {at: tDbl, label: 'انقر مرتين على مثبّت ويندوز', sub: WIN_INSTALLER},
          {at: tMore, label: '«مزيد من المعلومات»', sub: 'More info'},
          {at: tRun, label: '«تشغيل على أي حال»', sub: 'Run anyway'},
        ]}
      />
    </div>
  );
};
