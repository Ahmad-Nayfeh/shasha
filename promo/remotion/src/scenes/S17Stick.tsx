import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {StickGlyph, TvGlyph} from '../components/Devices';
import {Screen} from '../components/Tv';
import {CheckBadge, Chip, Header} from '../components/ui';
import {clamp, easeInOut, easeOut, mix, sp, tween} from '../lib/anim';
import {beatsFor, Scene} from '../lib/timeline';

// s17 — Samsung, LG and the others: a small Android TV stick on an HDMI port turns the TV into one «شاشة» runs on.

const TV = {x: 560, y: 262, w: 820};
const TVH = (TV.w * 9) / 16;
const PLUG = {x: TV.x + TV.w + 14, y: TV.y + TVH * 0.62}; // where the stick's plug meets the TV's HDMI port

// a generic "smart TV" home of the other maker (plain coloured tiles)
const Tiles: React.FC = () => (
  <div style={{position: 'absolute', inset: 0, background: 'linear-gradient(135deg, #1B2A4A, #0E1626)', padding: 40, boxSizing: 'border-box'}}>
    <div style={{display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 18, marginTop: 140}}>
      {['#7A3B3B', '#2F5F7A', '#5C4A82', '#3B6B4A', '#8A6A2E', '#2F4F7A', '#7A3B6B', '#3B5F5F'].map((c, i) => (
        <div key={i} style={{height: 118, borderRadius: 14, background: `linear-gradient(135deg, ${c}, ${rgba(c, 0.55)})`}} />
      ))}
    </div>
  </div>
);

export const S17Stick: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tSam = b.at('سامسونج');
  const tLg = b.at('إل جي');
  const tStick = b.at('فتكفيك');
  const tPort = b.at('بمنفذ');
  const tWorks = b.at('وتعمل');
  const tAll = b.at('بكل');

  const tvIn = sp(f, 0, {damping: 24, stiffness: 90});
  const fly = tween(f, tStick, 38, easeOut); // the stick arrives from the right
  const plugged = tween(f, tPort + 6, 14, easeInOut);
  const glow = Math.max(0, Math.sin(clamp((f - tPort) / 40) * Math.PI)) * 0.6 + tween(f, tWorks, 20);
  const works = tween(f, tWorks + 4, 22);
  const check = sp(f, tWorks + 20, {damping: 14, stiffness: 140});
  const hdmi = sp(f, tPort - 4, {damping: 20});

  const stickX = mix(PLUG.x + 520, PLUG.x, fly) + plugged * 0;
  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker="التحديث والأجهزة" index="٣ / ٣" title="Samsung وLG وغيرهما" />

      <div style={{opacity: clamp(tvIn * 1.4), transform: `translateX(${(1 - tvIn) * -50}px)`}}>
        <TvGlyph x={TV.x} y={TV.y} w={TV.w} glow={works > 0.4 ? brand.primary : '#6B8FD6'}>
          <Tiles />
          <div style={{position: 'absolute', left: 0, top: 0, width: 1920, height: 1080, transformOrigin: '0 0', transform: `scale(${TV.w / 1920})`, opacity: works}}>
            <Screen name="home_adult" />
          </div>
        </TvGlyph>
      </div>

      {/* the makers */}
      <div style={{position: 'absolute', left: 130, top: TV.y + 40, display: 'flex', flexDirection: 'column', gap: 26, direction: 'ltr'}}>
        <Chip label="Samsung" size={38} t={sp(f, tSam - 6, {damping: 20})} tone="neutral" dim={works * 0.8} mono />
        <Chip label="LG" size={38} t={sp(f, tLg - 6, {damping: 20})} tone="neutral" dim={works * 0.8} mono />
      </div>

      {/* the stick with its HDMI plug */}
      {fly > 0 ? <StickGlyph x={stickX} y={PLUG.y} scale={1.05} glow={glow} opacity={clamp(fly * 3)} /> : null}
      <div style={{position: 'absolute', left: PLUG.x - 6, top: PLUG.y + 56, opacity: clamp(hdmi * 1.4), transform: `translateY(${(1 - hdmi) * 14}px)`}}>
        <Chip icon="plug" label="HDMI" size={26} t={1} mono />
      </div>

      {/* it works */}
      <div style={{position: 'absolute', left: TV.x + TV.w - 56, top: TV.y - 30}}>
        <CheckBadge t={check} size={72} />
      </div>
      <div style={{position: 'absolute', left: TV.x, width: TV.w, top: TV.y + TVH + 138, display: 'flex', justifyContent: 'center', opacity: tween(f, tAll - 4, 14), transform: `translateY(${(1 - tween(f, tAll - 4, 14)) * 12}px)`}}>
        <Chip icon="check" label="«شاشة» بكل ميزاتها" size={34} t={1} />
      </div>
    </div>
  );
};
