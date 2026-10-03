import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {Icon, IconName} from '../components/Icons';
import {UpdateProgressOverlay} from '../components/Overlays';
import {Remote} from '../components/Remote';
import {fr, Screen, TvFrame} from '../components/Tv';
import {Chip, CrossedOut, FocusRing, Header} from '../components/ui';
import {clamp, easeInOut, sp, tween} from '../lib/anim';
import {FEATURE_TV} from '../lib/layout';
import {beatsFor, Scene} from '../lib/timeline';

// s15 — updates with one button: Settings → «فحص التحديثات», one press, and the app downloads and installs the new
// version itself (real captures: the page with its big button, checking, downloading, installing). No computer, no
// reinstalling.

const TV = FEATURE_TV;

export const S15Updates: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tNew = b.at('صدر');
  const tPress = b.at('فاضغط');
  const tOne = b.at('زرا واحدا');
  const tStart = b.at('فيبدأ');
  const tInst = b.at('والتثبيت');
  const tNoPc = b.at('بلا كمبيوتر');
  const tNoRe = b.at('وبلا إعادة');

  const tvIn = sp(f, 0, {damping: 24, stiffness: 90});
  const press = tOne + 8;
  const checkIn = tween(f, press + 6, 8);
  const downIn = tween(f, tStart - 2, 10);
  const instIn = tween(f, tInst - 2, 10);
  const dl = tween(f, tStart + 4, Math.max(20, tInst - tStart - 6), easeInOut);
  const remoteIn = sp(f, tPress - 10, {damping: 22});
  const sideOut = tween(f, tStart - 4, 12); // the remote leaves before the crossed-out chips arrive

  const crossed: Array<[string, number, IconName]> = [
    ['كمبيوتر', tNoPc, 'terminal'],
    ['إعادة تثبيت', tNoRe, 'refresh'],
  ];

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker="التحديث والأجهزة" index="١ / ٣" title="التحديث بضغطة واحدة" />

      <TvFrame x={TV.x + (1 - tvIn) * -60} y={TV.y} w={TV.w} opacity={clamp(tvIn * 1.4)}>
        <Screen name="updates_hub" />
        <Screen name="updates_checking" opacity={checkIn} />
        <div style={{position: 'absolute', inset: 0, opacity: downIn * (1 - instIn)}}>
          <Screen name="update_downloading" />
          <UpdateProgressOverlay p={dl} />
        </div>
        <Screen name="updates_installing" opacity={instIn} />
        <FocusRing track={[{f: tPress - 2, rect: fr('updates_hub', 'button'), r: 55}]} presses={[press]} until={press + 10} width={6} />
      </TvFrame>

      {/* the new version, then the one button */}
      <div style={{position: 'absolute', left: TV.x, top: TV.y - 74, opacity: tween(f, tNew - 4, 14) * (1 - downIn), transform: `translateY(${(1 - tween(f, tNew - 4, 14)) * 10}px)`}}>
        <Chip icon="download" label="إصدار جديد" tone="amber" size={24} />
      </div>
      <Remote x={1590} y={290} scale={0.95} rotate={-8} opacity={clamp(remoteIn * 1.5) * (1 - sideOut)} presses={[{btn: 'ok', f: press}]} />

      {/* what you do not need */}
      <div style={{position: 'absolute', right: 90, top: 330, display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 30, direction: 'rtl'}}>
        {crossed.map(([label, at, icon]) => (
          <CrossedOut key={label} from={at - 2} strikeAt={at + 16}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 16,
                padding: '16px 36px',
                borderRadius: 999,
                background: 'rgba(234,240,246,0.10)',
                border: '1.5px solid rgba(234,240,246,0.24)',
                fontSize: 40,
                fontWeight: 600,
                color: brand.text,
              }}
            >
              <Icon name={icon} size={40} color={brand.text} />
              {label}
            </div>
          </CrossedOut>
        ))}
      </div>
    </div>
  );
};
