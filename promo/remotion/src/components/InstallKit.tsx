import React from 'react';
import {clamp, mix} from '../lib/anim';
import {DH, DW} from './Desktop';
import {Laptop, laptopScreen} from './Laptop';

/** Zoom/pan inside the desktop screen space: s = scale, (cx, cy) = the point kept centred (clamped to edges). */
export type Cam = {s: number; cx: number; cy: number};
export const camMix = (a: Cam, b: Cam, t: number): Cam => ({s: mix(a.s, b.s, t), cx: mix(a.cx, b.cx, t), cy: mix(a.cy, b.cy, t)});

export const Zoom: React.FC<{cam?: Cam; children: React.ReactNode}> = ({cam, children}) => {
  const s = cam?.s ?? 1;
  const tx = cam ? clamp(DW / 2 - cam.cx * s, DW - DW * s, 0) : 0;
  const ty = cam ? clamp(DH / 2 - cam.cy * s, DH - DH * s, 0) : 0;
  return (
    <div style={{position: 'absolute', left: 0, top: 0, width: DW, height: DH, overflow: 'hidden'}}>
      <div style={{position: 'absolute', left: 0, top: 0, width: DW, height: DH, transform: `translate(${tx}px, ${ty}px) scale(${s})`, transformOrigin: '0 0'}}>{children}</div>
    </div>
  );
};

/** The laptop with a DWxDH desktop scaled into its screen. */
export const LaptopDesk: React.FC<{x: number; y: number; w: number; cam?: Cam; opacity?: number; children: React.ReactNode}> = ({x, y, w, cam, opacity = 1, children}) => {
  const {sw} = laptopScreen(w);
  return (
    <Laptop x={x} y={y} w={w} opacity={opacity}>
      <div style={{position: 'absolute', left: 0, top: 0, width: DW, height: DH, transform: `scale(${sw / DW})`, transformOrigin: '0 0'}}>
        <Zoom cam={cam}>{children}</Zoom>
      </div>
    </Laptop>
  );
};

/** Desktop-space point → stage px for a laptop at (x, y, w) with an optional camera. */
export const deskToStage = (lap: {x: number; y: number; w: number}, cam: Cam | undefined, px: number, py: number): [number, number] => {
  const {bz, sw} = laptopScreen(lap.w);
  const s = cam?.s ?? 1;
  const tx = cam ? clamp(DW / 2 - cam.cx * s, DW - DW * s, 0) : 0;
  const ty = cam ? clamp(DH / 2 - cam.cy * s, DH - DH * s, 0) : 0;
  const k = sw / DW;
  return [lap.x + bz + (tx + px * s) * k, lap.y + bz + (ty + py * s) * k];
};
