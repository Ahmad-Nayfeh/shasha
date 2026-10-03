import React from 'react';
import {useCurrentFrame} from 'remotion';
import {
  ConsoleView,
  Desktop,
  FileManager,
  MAC_NOT_OPENED,
  MAC_OPEN_ANYWAY,
  MAC_SETTINGS,
  macSidebarY,
  MacNotOpened,
  MacSettings,
  Pointer,
  Window,
} from '../components/Desktop';
import {INSTALL_LAPTOP, SideSteps, StepHeader} from '../components/Install';
import {LaptopDesk} from '../components/InstallKit';
import {tween} from '../lib/anim';
import {KIT_DIR, KIT_FILES, KIT_ROW_H, MAC_INSTALLER, openingLines} from '../lib/installer';
import {isEn, t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// Step 5 (Mac, macOS 15 and later): double-click the Mac installer; when the Mac refuses («Not Opened» → Done), open
// System Settings → Privacy & Security → «Open Anyway».
const MAC_ROW = KIT_FILES.findIndex((k) => k.kind === 'command');
const PITCH = KIT_ROW_H + 4;

export const S18RunMac: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tDbl = b.at('انقر مرتين');
  const tRefuse = b.at('وإن رفض');
  const tSettings = b.at('فافتح إعدادات');
  const tPriv = b.at('ثم الخصوصية');
  const tAnyway = b.at('ثم فتح');

  const dbl = tDbl + 22; // the double-click (two clicks 7 frames apart)
  const dlg = tween(f, tRefuse - 2, 8); // «Not Opened»
  const clickDone = tSettings - 4;
  const dlgOut = tween(f, clickDone + 2, 6);
  const win = tween(f, tSettings + 2, 12); // System Settings
  const clickPriv = tPriv + 8;
  const go = tween(f, clickPriv + 2, 8); // the Privacy & Security pane
  const clickAnyway = tAnyway + 16;
  const winOut = tween(f, clickAnyway + 6, 8);
  const con = tween(f, clickAnyway + 12, 10);
  const press = (c: number) => Math.max(0, Math.sin(Math.min(1, Math.max(0, (f - c) / 8)) * Math.PI));
  // window bar 40 + path bar 52 + column header 36, then the rows (margin 2 + 54 + 2)
  const rowY = 60 + 40 + 52 + 36 + MAC_ROW * PITCH + 2 + KIT_ROW_H / 2;
  const files = KIT_FILES.map((k, i) => ({...k, highlight: i === MAC_ROW ? tween(f, tDbl - 2, 8) : 0}));
  const privY = macSidebarY(MAC_SETTINGS.privacyRow);

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <StepHeader step={5} sub={t('شغّل المثبّت: ماك')} />
      <LaptopDesk x={INSTALL_LAPTOP.x} y={INSTALL_LAPTOP.y} w={INSTALL_LAPTOP.w}>
        <Desktop os="mac" app={f >= tSettings ? 'System Settings' : 'Finder'}>
          <Window os="mac" x={70} y={60} w={1140} h={600} title={KIT_DIR}>
            <FileManager os="mac" path={[KIT_DIR]} files={files} selected={f >= dbl - 2 ? MAC_ROW : -1} rowH={KIT_ROW_H} />
          </Window>
          {dlg > 0 && dlgOut < 1 ? (
            <div style={{position: 'absolute', inset: 0, opacity: 1 - dlgOut}}>
              <MacNotOpened t={dlg} file={MAC_INSTALLER} pressed={press(clickDone)} />
            </div>
          ) : null}
          {win > 0 && winOut < 1 ? (
            <div style={{position: 'absolute', inset: 0, opacity: 1 - winOut}}>
              <MacSettings t={win} go={go} file={MAC_INSTALLER} pressed={press(clickAnyway)} glow={tween(f, tAnyway, 10) * (f < clickAnyway ? 1 : 0)} />
            </div>
          ) : null}
          {con > 0 ? (
            <Window os="mac" x={160} y={100} w={960} h={560} title="Terminal" dark t={con}>
              <ConsoleView
                lines={openingLines([clickAnyway + 14, clickAnyway + 18, clickAnyway + 20])}
              />
            </Window>
          ) : null}
          <Pointer
            keys={[
              {f: 0, x: 900, y: 640},
              {f: dbl - 8, x: 330, y: rowY},
              {f: tRefuse + 6, x: 330, y: rowY},
              {f: clickDone - 4, x: MAC_NOT_OPENED.doneX, y: MAC_NOT_OPENED.doneY},
              {f: clickDone + 4, x: MAC_NOT_OPENED.doneX, y: MAC_NOT_OPENED.doneY},
              {f: clickPriv - 5, x: 280, y: privY},
              {f: clickPriv + 6, x: 280, y: privY},
              {f: clickAnyway - 6, x: MAC_OPEN_ANYWAY.x, y: MAC_OPEN_ANYWAY.y},
            ]}
            clicks={[dbl, dbl + 7, clickDone, clickPriv, clickAnyway]}
          />
        </Desktop>
      </LaptopDesk>
      <SideSteps
        items={[
          {at: tDbl, label: t('انقر مرتين على مثبّت ماك'), sub: MAC_INSTALLER},
          {at: tRefuse, label: t('إن رفض ماك فتحه: إعدادات النظام'), sub: isEn ? undefined : 'System Settings'}, // the English label is the TV's/Mac's own text
          {at: tPriv, label: t('الخصوصية والأمان'), sub: isEn ? undefined : 'Privacy & Security'},
          {at: tAnyway, label: t('«فتح على أي حال»'), sub: isEn ? undefined : 'Open Anyway'},
        ]}
      />
    </div>
  );
};
