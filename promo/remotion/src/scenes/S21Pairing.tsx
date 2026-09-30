import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {ConsoleView} from '../components/Desktop';
import {GoogleTvSettings, PairCodeDialog} from '../components/GoogleTv';
import {NoteChip, StepHeader} from '../components/Install';
import {Crumbs, sx, sy, TV} from '../components/TvStep';
import {TvFrame} from '../components/Tv';
import {Card} from '../components/ui';
import {clamp, easeInOut, keys, sp, tween} from '../lib/anim';
import {PAIRING} from '../lib/installer';
import {beatsFor, Scene} from '../lib/timeline';
import {P_WIRELESS, WIRELESS_ROW} from '../lib/tvPanels';

// Step 6, fallback: TVs that only offer «Wireless debugging» → «Pair device with pairing code»; the installer
// asks for the pairing address and the 6-digit code shown on the TV.
export const S21Pairing: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tWireless = b.at('تصحيح الأخطاء اللاسلكي');
  const tPair = b.at('فاختر الاقتران');
  const tEnter = b.at('وأدخل');

  const focus = [keys(f, [[tPair - 2, 0], [tPair + 10, WIRELESS_ROW.pair]], easeInOut)];
  const dlg = tween(f, tPair + 18, 10);
  const card = sp(f, tEnter - 10, {damping: 22});
  const code = PAIRING.code.replace(' ', '');
  return (
    <div style={{position: 'absolute', inset: 0}}>
      <StepHeader step={6} sub="إن لم يظهر إلا الاقتران اللاسلكي" />
      <TvFrame x={TV.x} y={TV.y} w={TV.w}>
        <GoogleTvSettings panels={[P_WIRELESS]} panel={0} focus={focus} open={tween(f, tWireless - 10, 14)} />
        <PairCodeDialog t={dlg} code={PAIRING.code} address={PAIRING.pairAddress} />
      </TvFrame>
      <Crumbs
        items={[
          {ar: 'خيارات المطوّر', en: 'Developer options', at: 0},
          {ar: 'تصحيح الأخطاء اللاسلكي', en: 'Wireless debugging', at: tWireless - 6},
          {ar: 'الاقتران برمز', en: 'Pair device with pairing code', at: tPair + 8},
        ]}
        out={tPair + 20}
      />
      <div style={{position: 'absolute', left: 1330, top: 300, opacity: clamp(card * 1.4), transform: `translateX(${(1 - card) * -30}px)`}}>
        <Card style={{width: 500, height: 330, overflow: 'hidden', padding: 0, position: 'relative'}}>
          <div style={{position: 'absolute', left: 0, right: 0, top: 0, height: 44, background: '#1A1F27', color: '#D8DEE8', fontSize: 18, display: 'flex', alignItems: 'center', padding: '0 18px', direction: 'ltr', fontFamily: brand.mono}}>
            المثبّت / installer
          </div>
          <div style={{position: 'absolute', left: 0, right: 0, top: 44, bottom: 0}}>
            <ConsoleView
              fontSize={17}
              lineH={30}
              pad={16}
              lines={[
                {t: tEnter - 8, text: '==> Pairing with a code', kind: 'say'},
                {t: tEnter - 6, text: 'الإقران برمز (Wireless debugging)', kind: 'ar'},
                {t: tEnter - 2, text: PAIRING.pairAddress, kind: 'input', type: 0.8, typeFrom: tEnter + 4, prompt: 'Address / العنوان: '},
                {t: tEnter + 30, text: code, kind: 'input', type: 0.5, typeFrom: tEnter + 34, prompt: 'Code / الرمز: '},
                {t: tEnter + 54, text: 'Paired. / تم الإقران.', kind: 'ok'},
              ]}
            />
          </div>
        </Card>
      </div>
      <NoteChip x={sx(60)} y={sy(56)} t={sp(f, tPair + 24, {damping: 20})} icon="keyboard" size={26}>
        اكتب في المثبّت: العنوان ثم الرمز
      </NoteChip>
    </div>
  );
};
