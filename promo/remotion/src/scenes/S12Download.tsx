import React from 'react';
import {useCurrentFrame} from 'remotion';
import {Browser, ContextMenu, Desktop, FileManager, Pointer, RepoFilePage, RepoPage, RepoRow, Window} from '../components/Desktop';
import {INSTALL_LAPTOP, SideSteps, StepHeader} from '../components/Install';
import {camMix, LaptopDesk} from '../components/InstallKit';
import {tween} from '../lib/anim';
import {KIT_DIR, KIT_ZIP, KIT_ZIP_SIZE, REPO_PATH} from '../lib/installer';
import {beatsFor, Scene} from '../lib/timeline';

// Step 1 (a): on the repository page click «Shasha-Setup.zip» → the download button → extract it
// (Windows: right-click → «Extract All…»; Mac: double-click).
const ROWS: RepoRow[] = [
  {name: '.github', kind: 'dir', msg: 'CI: build and release', when: '2 days ago'},
  {name: 'Shasha-Setup', kind: 'dir', msg: 'Installer kit v1.0.0', when: '2 days ago'},
  {name: 'app', kind: 'dir', msg: 'Phase 7: in-app updater', when: '2 days ago'},
  {name: 'docs', kind: 'dir', msg: 'Install guide and manual', when: 'yesterday'},
  {name: 'remote', kind: 'dir', msg: 'Phone remote page', when: 'last week'},
  {name: 'youtube', kind: 'dir', msg: 'Extractor update', when: 'last week'},
  {name: 'MAINTENANCE.md', kind: 'file', msg: 'Maintenance guide', when: '2 days ago'},
  {name: 'README.md', kind: 'file', msg: 'Install steps', when: 'yesterday'},
  {name: KIT_ZIP, kind: 'zip', msg: 'Installer kit v1.0.0 (one download)', when: '2 days ago'},
];
const ZIP_ROW = ROWS.length - 1;

export const S12Download: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tPage = b.at('من صفحة');
  const tFile = b.at('اضغط الملف');
  const tBtn = b.at('ثم زر');
  const tUnzip = b.at('ثم فك');
  const tWin = b.at('في ويندوز');
  const tRight = b.at('بالزر الأيمن');
  const tExtract = b.at('ثم استخراج');
  const tMac = b.at('وفي ماك');
  const tTwice = b.at('بنقرتين');

  // browser part
  const clickZip = tFile + 10;
  const toFile = tween(f, clickZip + 4, 8);
  const clickDl = tBtn + 10;
  const dl = tween(f, clickDl + 2, 34);
  // explorer part (Windows)
  const toExplorer = tween(f, tUnzip - 8, 10);
  const rightClick = tRight + 6;
  const menu = tween(f, rightClick + 1, 6);
  const clickExtract = tExtract + 12;
  const extracted = f >= clickExtract + 10;
  // mac part
  const toMac = tween(f, tMac - 6, 10);
  const dbl = tTwice + 6;
  const macDone = f >= dbl + 14;

  // window bar 44 + browser bar 54 + repo header 70 + tabs 44 + table margin 18 + table header 46 + rows of 46 (desktop space)
  const zipRowY = 44 + 54 + 70 + 44 + 18 + 46 + 46 * ZIP_ROW + 23;
  const winFiles = [
    ...(extracted ? [{name: KIT_DIR, kind: 'folder' as const, size: '', date: '30/09/2026', highlight: tween(f, clickExtract + 10, 8)}] : []),
    {name: KIT_ZIP, kind: 'zip' as const, size: KIT_ZIP_SIZE, date: '30/09/2026'},
    {name: 'holiday-photos.jpg', kind: 'pdf' as const, size: '3.1 MB', date: '12/09/2026'},
  ];
  const macFiles = [
    ...(macDone ? [{name: KIT_DIR, kind: 'folder' as const, size: '--', date: 'Today', highlight: tween(f, dbl + 14, 8)}] : []),
    {name: KIT_ZIP, kind: 'zip' as const, size: KIT_ZIP_SIZE, date: 'Today'},
  ];
  const zipIdx = extracted ? 1 : 0;
  const cam = camMix({s: 1, cx: 640, cy: 400}, {s: 1.35, cx: 420, cy: 300}, tween(f, rightClick - 6, 14) * (1 - tween(f, tMac - 10, 10)));
  const winOn = toExplorer * (1 - toMac);

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <StepHeader step={1} />
      <LaptopDesk x={INSTALL_LAPTOP.x} y={INSTALL_LAPTOP.y} w={INSTALL_LAPTOP.w} cam={toMac < 1 ? cam : undefined}>
        <Desktop os={toMac > 0.5 ? 'mac' : 'win'}>
          {/* browser (maximised) */}
          {toExplorer < 1 ? (
            <div style={{position: 'absolute', left: 0, top: 0, width: 1280, height: 740, opacity: 1 - toExplorer}}>
              <Window os="win" x={0} y={0} w={1280} h={740} title={`${REPO_PATH}`}>
                <Browser url={`https://${REPO_PATH}`}>
                  <div style={{position: 'absolute', inset: 0, opacity: 1 - toFile}}>
                    <RepoPage owner="Ahmad-Nayfeh" repo="shasha" rows={ROWS.map((r, i) => ({...r, highlight: i === ZIP_ROW ? tween(f, tFile - 4, 8) : 0}))} hover={f >= clickZip - 8 && f < clickZip + 6 ? ZIP_ROW : -1} />
                  </div>
                  <div style={{position: 'absolute', inset: 0, opacity: toFile}}>
                    <RepoFilePage owner="Ahmad-Nayfeh" repo="shasha" file={KIT_ZIP} size={KIT_ZIP_SIZE} pressed={Math.max(0, Math.sin(Math.min(1, Math.max(0, (f - clickDl) / 8)) * Math.PI))} done={dl} />
                  </div>
                </Browser>
              </Window>
              <Pointer
                keys={[
                  {f: tPage, x: 700, y: 420},
                  {f: clickZip - 6, x: 250, y: zipRowY},
                  {f: tBtn - 2, x: 250, y: zipRowY},
                  {f: clickDl - 4, x: 1205, y: 44 + 54 + 66 + 10 + 30},
                ]}
                clicks={[clickZip, clickDl]}
              />
            </div>
          ) : null}
          {/* Windows file explorer: Downloads */}
          {winOn > 0 ? (
            <div style={{position: 'absolute', inset: 0, opacity: winOn}}>
              <Window os="win" x={70} y={60} w={1140} h={560} title="Downloads">
                <FileManager os="win" path={['This PC', 'Downloads']} files={winFiles} selected={f >= rightClick - 4 && !extracted ? zipIdx : -1} rowH={60} />
              </Window>
              <ContextMenu os="win" x={330} y={196} t={menu * (f < clickExtract + 4 ? 1 : 0)} active={f >= tExtract - 2 ? 2 : -1} items={['Open', 'Open in new window', 'Extract All...', '-', 'Pin to Start', 'Copy as path', 'Properties']} />
              <Pointer
                keys={[
                  {f: tUnzip - 8, x: 700, y: 480},
                  {f: rightClick - 4, x: 300, y: 60 + 44 + 52 + 36 + 30 + (extracted ? 62 : 0)},
                  // menu at (330,196): padding 8 + items of ~40 px → «Extract All...» (index 2) centred near y 304
                  {f: tExtract - 2, x: 420, y: 196 + 8 + 2 * 40 + 16},
                ]}
                right={[rightClick]}
                clicks={[clickExtract]}
              />
            </div>
          ) : null}
          {/* Mac Finder: Downloads */}
          {toMac > 0 ? (
            <div style={{position: 'absolute', inset: 0, opacity: toMac}}>
              <Window os="mac" x={120} y={90} w={1040} h={460} title="Downloads">
                <FileManager os="mac" path={['Downloads']} files={macFiles} selected={f >= dbl - 2 && !macDone ? 0 : -1} rowH={60} />
              </Window>
              <Pointer keys={[{f: tMac, x: 760, y: 560}, {f: dbl - 6, x: 330, y: 90 + 40 + 52 + 36 + 30}]} clicks={[dbl, dbl + 7]} />
            </div>
          ) : null}
        </Desktop>
      </LaptopDesk>
      <SideSteps
        items={[
          {at: tFile, label: 'اضغط الملف', sub: KIT_ZIP},
          {at: tBtn, label: 'ثم زر التنزيل ⬇', icon: 'download'},
          {at: tWin, label: 'ويندوز: زر أيمن ← «Extract All»', icon: 'box'},
          {at: tMac, label: 'ماك: نقرتان على الملف', icon: 'box'},
        ]}
        size={27}
      />
    </div>
  );
};
