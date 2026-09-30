import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {Desktop, FileIcon, FileManager, Window} from '../components/Desktop';
import {Icon} from '../components/Icons';
import {INSTALL_LAPTOP, NoteChip, StepHeader} from '../components/Install';
import {LaptopDesk} from '../components/InstallKit';
import {Card} from '../components/ui';
import {clamp, sp, tween} from '../lib/anim';
import {KIT_DIR, KIT_FILES} from '../lib/installer';
import {beatsFor, Scene} from '../lib/timeline';

// Step 1 (b): the extracted folder «Shasha-Setup» and what is in it — one folder, nothing else to download.
export const S13Folder: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tAll = b.at('وفيه كل');
  const tApp = b.at('التطبيق');
  const tMac = b.at('لماك');
  const tWin = b.at('وويندوز');
  const tGuide = b.at('ودليل');
  const D = scene.duration_frames;
  const beatOf: Record<string, number> = {apk: tApp, bat: tWin, command: tMac, pdf: tGuide, folder: tGuide + 24};
  const files = KIT_FILES.map((k) => {
    const at = beatOf[k.kind];
    const lit = at !== undefined ? tween(f, at - 2, 8) : 0;
    return {...k, highlight: at !== undefined ? lit * (1 - 0.6 * tween(f, at + 26, 12)) : 0, label: k.label && at !== undefined && f >= at ? k.label : undefined};
  });
  const labels = 1;
  const card = sp(f, tAll - 4, {damping: 22});

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <StepHeader step={1} sub="مجلد واحد فيه كل شيء" />
      <LaptopDesk x={INSTALL_LAPTOP.x} y={INSTALL_LAPTOP.y} w={INSTALL_LAPTOP.w}>
        <Desktop os="win">
          <Window os="win" x={60} y={50} w={1160} h={640} title={KIT_DIR}>
            <FileManager os="win" path={['This PC', 'Downloads', KIT_DIR]} files={files} rowH={62} labels={labels} />
          </Window>
        </Desktop>
      </LaptopDesk>
      <div style={{position: 'absolute', left: 1330, top: 300, opacity: clamp(card * 1.4), transform: `translateX(${(1 - card) * -30}px)`}}>
        <Card style={{width: 500, padding: '30px 34px', boxSizing: 'border-box'}} tone="teal">
          <div style={{display: 'flex', alignItems: 'center', gap: 18}}>
            <FileIcon kind="folder" size={84} />
            <div>
              <div style={{fontSize: 34, fontWeight: 700, fontFamily: brand.mono, direction: 'ltr', textAlign: 'right'}}>{KIT_DIR}</div>
              <div style={{fontSize: 26, color: brand.muted, marginTop: 4}}>كل ما تحتاجه في مجلد واحد</div>
            </div>
          </div>
          <div style={{height: 1, background: 'rgba(234,240,246,0.08)', margin: '24px 0 18px'}} />
          {[
            ['التطبيق نفسه', 'apk', tApp],
            ['مثبّت ماك', 'command', tMac],
            ['مثبّت ويندوز', 'bat', tWin],
            ['دليل مصوّر (PDF)', 'pdf', tGuide],
          ].map(([label, kind, at]) => {
            const t = sp(f, (at as number) - 2, {damping: 20});
            return (
              <div key={label as string} style={{display: 'flex', alignItems: 'center', gap: 14, height: 58, opacity: clamp(t * 1.5) * 0.95 + 0.05, transform: `translateX(${(1 - t) * -16}px)`}}>
                <FileIcon kind={kind as 'apk'} size={40} />
                <span style={{fontSize: 28, fontWeight: 600, flex: 1}}>{label as string}</span>
                <div style={{width: 34, height: 34, borderRadius: 99, background: rgba(brand.primary, f >= (at as number) ? 1 : 0.15), display: 'grid', placeItems: 'center'}}>
                  {f >= (at as number) ? <Icon name="check" size={22} color={brand.onPrimary} stroke={3} /> : null}
                </div>
              </div>
            );
          })}
        </Card>
      </div>
      <NoteChip x={1330} y={790} t={sp(f, Math.min(tGuide + 40, D - 40), {damping: 20})} icon="check">
        لا شيء آخر للتنزيل
      </NoteChip>
    </div>
  );
};
