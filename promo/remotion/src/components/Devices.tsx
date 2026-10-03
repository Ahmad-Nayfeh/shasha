import React from 'react';
import {brand, rgba} from '../brand';
import {LogoMark} from './Brand';

// Flat device drawings for the "which TVs" scenes: a TV, and an HDMI stick (Android TV / Fire TV stick). Plain
// shapes, no logos: the names of the makers are written next to them as text.

/** A flat TV: slim bezel, a stand, and `children` on the screen (screen = w x w*9/16 px). */
export const TvGlyph: React.FC<{x: number; y: number; w: number; glow?: string; opacity?: number; children?: React.ReactNode}> = ({
  x,
  y,
  w,
  glow = brand.primary,
  opacity = 1,
  children,
}) => {
  const h = (w * 9) / 16;
  const bz = Math.max(8, w * 0.012);
  return (
    <div style={{position: 'absolute', left: x - bz, top: y - bz, width: w + bz * 2, height: h + bz * 2 + w * 0.12, opacity}}>
      <div style={{position: 'absolute', left: -w * 0.18, right: -w * 0.18, top: -h * 0.25, bottom: -h * 0.1, background: `radial-gradient(50% 50% at 50% 50%, ${rgba(glow, 0.12)}, transparent 70%)`}} />
      <div style={{position: 'absolute', left: '50%', top: h + bz * 2 - 2, width: w * 0.07, height: w * 0.06, transform: 'translateX(-50%)', background: 'linear-gradient(90deg, #0A0E12, #1B232C 45%, #0C1116)'}} />
      <div style={{position: 'absolute', left: '50%', top: h + bz * 2 + w * 0.05, width: w * 0.3, height: w * 0.014, borderRadius: 99, transform: 'translateX(-50%)', background: 'linear-gradient(180deg, #232C36, #0A0E12)'}} />
      <div style={{position: 'absolute', left: 0, top: 0, width: w + bz * 2, height: h + bz * 2, borderRadius: w * 0.016, background: '#05080B', boxShadow: 'inset 0 0 0 1.5px rgba(255,255,255,0.1), 0 30px 70px rgba(0,0,0,0.55)'}} />
      <div style={{position: 'absolute', left: bz, top: bz, width: w, height: h, borderRadius: w * 0.008, overflow: 'hidden', background: '#0B0F14'}}>{children}</div>
    </div>
  );
};

/** An HDMI stick: a slim body with the HDMI plug on its left end. (x, y) = the plug's tip, centre line. */
export const StickGlyph: React.FC<{x: number; y: number; scale?: number; glow?: number; opacity?: number; rotate?: number}> = ({
  x,
  y,
  scale = 1,
  glow = 0,
  opacity = 1,
  rotate = 0,
}) => (
  <div style={{position: 'absolute', left: x, top: y - 36 * scale, width: 330 * scale, height: 72 * scale, opacity, transform: `rotate(${rotate}deg)`, transformOrigin: '0 50%', filter: `drop-shadow(0 18px 26px rgba(0,0,0,0.55)) drop-shadow(0 0 ${28 * glow}px ${rgba(brand.primary, 0.6 * glow)})`}}>
    {/* the plug */}
    <div style={{position: 'absolute', left: 0, top: 14 * scale, width: 70 * scale, height: 44 * scale, borderRadius: 6 * scale, background: 'linear-gradient(180deg, #8E99A8, #5C6675)'}} />
    <div style={{position: 'absolute', left: 6 * scale, top: 22 * scale, width: 58 * scale, height: 28 * scale, borderRadius: 4 * scale, background: '#2B323C'}} />
    {/* the body */}
    <div style={{position: 'absolute', left: 66 * scale, top: 0, width: 264 * scale, height: 72 * scale, borderRadius: 22 * scale, background: 'linear-gradient(180deg, #2A333E, #151B22)', boxShadow: 'inset 0 0 0 1.5px rgba(255,255,255,0.14)'}} />
    <div style={{position: 'absolute', left: 96 * scale, top: 28 * scale, width: 100 * scale, height: 16 * scale, borderRadius: 99, background: 'rgba(234,240,246,0.12)'}} />
    <div style={{position: 'absolute', left: 290 * scale, top: 28 * scale, width: 16 * scale, height: 16 * scale, borderRadius: 99, background: brand.primary, boxShadow: `0 0 ${12 * scale}px ${brand.primary}`}} />
  </div>
);

/** The mark on a TV screen: Shasha is running. */
export const ShashaOnScreen: React.FC<{w: number}> = ({w}) => (
  <div style={{position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', background: 'radial-gradient(70% 70% at 50% 40%, #12202A, #0B0F14)'}}>
    <LogoMark size={w * 0.34} />
  </div>
);
