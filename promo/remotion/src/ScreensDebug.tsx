import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {brand} from './brand';
import {PhoneParentPage, PhoneParentPinPage, PhoneRemotePage, PhoneScreen, PhoneTakeoutPage, PW, PH} from './components/Phone';
import {Screen} from './components/Tv';
import {loadFonts} from './fonts';
import manifest from './screens.json';
import {focusRects, screenSrc} from './lib/screens';

loadFonts();

// Tuning aid for src/screens.json: frame N shows manifest screen N (real capture or placeholder) with every
// focus rectangle outlined and labelled.   npx remotion still src/index.ts ScreensDebug out/s.png --frame=3
const entries = Object.entries((manifest as any).screens) as Array<[string, any]>;
export const screensDebugCount = entries.length;

export const ScreensDebug: React.FC = () => {
  const f = useCurrentFrame();
  const [name, info] = entries[Math.min(f, entries.length - 1)];
  const phone = info.device === 'phone';
  const W = phone ? PW : 1920;
  const H = phone ? PH : 1080;
  const scale = phone ? 1000 / PH : 1;
  const rects: Array<[string, number[]]> = [];
  for (const k of Object.keys(info.focus ?? {})) {
    const list = focusRects(name, k);
    list.forEach((r, i) => rects.push([list.length > 1 ? `${k}[${i}]` : k, r]));
  }
  return (
    <AbsoluteFill style={{background: '#000', fontFamily: brand.mono}}>
      <div style={{position: 'absolute', left: phone ? 760 : 0, top: phone ? 40 : 0, width: W, height: H, transform: `scale(${scale})`, transformOrigin: '0 0'}}>
        {phone ? (
          <PhoneScreen name={name}>
            {name === 'phone_remote_main' ? <PhoneRemotePage typed={13} results={1} link={1} /> : null}
            {name === 'phone_parent_pin' ? <PhoneParentPinPage filled={2} /> : null}
            {name === 'phone_parent' ? <PhoneParentPage /> : null}
            {name === 'phone_takeout_import' ? <PhoneTakeoutPage drop={1} progress={0.6} done={1} /> : null}
          </PhoneScreen>
        ) : (
          <Screen name={name} />
        )}
        {rects.map(([k, r]) => (
          <div key={k} style={{position: 'absolute', left: r[0] * W, top: r[1] * H, width: r[2] * W, height: r[3] * H, border: '3px solid #FF3BD4', boxSizing: 'border-box'}}>
            <span style={{position: 'absolute', left: 0, top: -26, fontSize: phone ? 12 : 20, color: '#FF3BD4', background: 'rgba(0,0,0,0.7)', padding: '1px 6px', whiteSpace: 'nowrap'}}>{k}</span>
          </div>
        ))}
      </div>
      <div style={{position: 'absolute', left: 20, bottom: 16, fontSize: 24, color: '#fff', background: 'rgba(0,0,0,0.7)', padding: '6px 12px'}}>
        #{f} {name} → public/screens/{info.file} {screenSrc(name) ? '(capture)' : '(PLACEHOLDER)'}
      </div>
    </AbsoluteFill>
  );
};

