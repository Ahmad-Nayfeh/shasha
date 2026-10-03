import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {clamp, sp, tween} from '../lib/anim';
import {digits, dir, flowSign, isEn} from '../lib/lang';
import {GoogleTvSettings, GtvPanel} from './GoogleTv';
import {INSTALL_TV, mirrorX} from './Install';
import {Press, remoteSize, Remote} from './Remote';
import {TvFrame} from './Tv';

// Stage for the TV steps of the installation: the TV with the redrawn Google TV settings, the remote with its
// presses, and helper chips over the dimmed launcher (left part of the screen). The TV's own screen is the real
// (left-to-right) Google TV in both versions; around it the English version mirrors the stage.

export const TV = INSTALL_TV;
export const K = TV.w / 1920;
/** Screen-space → stage px */
export const sx = (x: number) => TV.x + x * K;
export const sy = (y: number) => TV.y + y * K;

/** The remote beside the TV: right of it in Arabic, left of it (and tilted the other way) in English. */
export const REMOTE_SCALE = 0.95;
export const REMOTE_X = mirrorX(1560, remoteSize.w * REMOTE_SCALE);

export const TvStepStage: React.FC<{
  panels: GtvPanel[];
  panel: number;
  focus: number[];
  open: number;
  presses: Press[];
  toast?: {text: string; opacity: number} | null;
  tvIn?: number;
  remoteOpacity?: number;
  children?: React.ReactNode; // screen-space overlays (inside the TV)
}> = ({panels, panel, focus, open, presses, toast, tvIn = 1, remoteOpacity = 1, children}) => (
  <>
    <TvFrame x={TV.x + flowSign * (1 - tvIn) * -60} y={TV.y} w={TV.w} opacity={clamp(tvIn * 1.4)}>
      <GoogleTvSettings panels={panels} panel={panel} focus={focus} open={open} toast={toast} />
      {children}
    </TvFrame>
    <Remote x={REMOTE_X} y={292} scale={REMOTE_SCALE} rotate={flowSign * -8} opacity={clamp(tvIn * 1.4) * remoteOpacity} presses={presses} />
  </>
);

/**
 * Breadcrumb over the dimmed launcher. Arabic version: the Arabic step names with the TV's English labels under them.
 * English version: the TV's own labels, one line.
 */
export const Crumbs: React.FC<{items: Array<{ar: string; en: string; at: number}>; x?: number; y?: number; out?: number}> = ({items, x = sx(56), y = sy(52), out}) => {
  const f = useCurrentFrame();
  const shown = items.filter((it) => f >= it.at - 2);
  if (!shown.length) return null;
  const gone = out !== undefined ? tween(f, out, 10) : 0;
  const first = sp(f, items[0].at - 2, {damping: 22});
  return (
    <div
      style={{
        position: 'absolute',
        left: x,
        top: y,
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        padding: '12px 22px',
        borderRadius: 22,
        background: 'rgba(11,15,20,0.9)',
        border: '1.5px solid rgba(234,240,246,0.14)',
        boxShadow: '0 16px 40px rgba(0,0,0,0.45)',
        direction: dir,
        fontFamily: brand.font,
        opacity: clamp(first * 1.5) * (1 - gone),
        transform: `translateX(${flowSign * (1 - first) * -20}px)`,
      }}
    >
      {shown.map((it, i) => {
        const p = sp(f, it.at - 2, {damping: 20});
        const last = i === shown.length - 1;
        return (
          <React.Fragment key={i}>
            {i ? <span style={{fontSize: isEn ? 22 : 26, color: brand.faint, opacity: clamp(p * 2)}}>{isEn ? '→' : '←'}</span> : null}
            <div style={{display: 'flex', flexDirection: 'column', alignItems: 'center', opacity: clamp(p * 1.6), transform: `translateX(${flowSign * (1 - p) * -14}px)`}}>
              <span style={{fontSize: isEn ? 24 : 30, fontWeight: 700, color: last ? brand.primaryBright : brand.text, whiteSpace: 'nowrap'}}>{isEn ? it.en : it.ar}</span>
              {isEn ? null : <span style={{fontSize: 18, fontWeight: 500, color: brand.muted, direction: 'ltr', whiteSpace: 'nowrap'}}>{it.en}</span>}
            </div>
          </React.Fragment>
        );
      })}
    </div>
  );
};

/** ١..٧ press counter. */
export const PressCounter: React.FC<{taps: number[]; x?: number; y?: number; out?: number}> = ({taps, x = sx(60), y = sy(250), out}) => {
  const f = useCurrentFrame();
  const gone = out !== undefined ? tween(f, out, 12) : 0;
  return (
    <div style={{position: 'absolute', left: x - 14, top: y - 12, display: 'flex', gap: 14, direction: dir, opacity: 1 - gone, padding: '12px 14px', borderRadius: 99, background: 'rgba(11,15,20,0.88)', border: '1.5px solid rgba(234,240,246,0.12)'}}>
      {taps.map((t, i) => {
        const p = sp(f, t, {damping: 16, stiffness: 160});
        const last = i === taps.length - 1;
        return (
          <div
            key={i}
            style={{
              width: 64,
              height: 64,
              borderRadius: 99,
              background: last && f >= t ? brand.primary : rgba(brand.primary, 0.18),
              border: `2.5px solid ${brand.primary}`,
              color: last && f >= t ? brand.onPrimary : brand.text,
              display: 'grid',
              placeItems: 'center',
              fontSize: 32,
              fontWeight: 700,
              fontFamily: brand.font,
              transform: `scale(${p})`,
              opacity: clamp(p * 2),
              boxShadow: f >= t && f < t + 12 ? `0 0 26px ${rgba(brand.primary, 0.7)}` : undefined,
            }}
          >
            {digits(i + 1)}
          </div>
        );
      })}
    </div>
  );
};

/** Amber ring around a settings row (screen space rect), e.g. the IP address. */
export const RowHalo: React.FC<{rect: [number, number, number, number]; t: number; color?: string}> = ({rect, t, color = brand.amber}) => (
  <div
    style={{
      position: 'absolute',
      left: rect[0] - 10,
      top: rect[1] - 10,
      width: rect[2] + 20,
      height: rect[3] + 20,
      borderRadius: 28,
      border: `7px solid ${color}`,
      boxShadow: `0 0 50px ${rgba(color, 0.55)}`,
      opacity: t,
    }}
  />
);
