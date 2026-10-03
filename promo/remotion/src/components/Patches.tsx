import React from 'react';
import {Img} from 'remotion';
import {brand} from '../brand';
import {captureSize, isEnglishScreen, PatchOp, screenPatch, screenSrc} from '../lib/screens';
import {Icon, IconName} from './Icons';
import {QrCode} from './QrCode';

// Patches drawn over a real capture (see screens.json "patches"): they neutralise details of a screenshot —
// the app's own focus highlight where our D-pad ring takes over, an error line — in the capture's own pixels.

const hex = (h: string) => {
  const n = parseInt(h.replace('#', ''), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
};

/** Linear per-channel map with from -> to and white -> white (sRGB), as feFuncX slope/intercept. */
const levels = (from: string, to: string) => {
  const a = hex(from);
  const b = hex(to);
  return a.map((av, i) => {
    const slope = (255 - b[i]) / (255 - av);
    return {slope, intercept: b[i] / 255 - (slope * av) / 255};
  });
};

const num = (v: unknown, d = 0) => (typeof v === 'number' ? v : d);
const str = (v: unknown, d = '') => (typeof v === 'string' ? v : d);

const Op: React.FC<{op: PatchOp; src: string | null; W: number; H: number; id: string; ltr?: boolean}> = ({op, src, W, H, id, ltr}) => {
  const x = num(op.x);
  const y = num(op.y);
  const w = num(op.w);
  const h = num(op.h);
  const r = typeof op.r === 'string' ? op.r : num(op.r);
  const feather = num(op.feather);
  switch (op.op) {
    case 'rect': {
      const fill = str(op.fill, '#000');
      return (
        <div
          style={{
            position: 'absolute',
            left: x,
            top: y,
            width: w,
            height: h,
            borderRadius: r,
            background: fill,
            border: op.stroke ? `${num(op.strokeWidth, 1)}px solid ${str(op.stroke)}` : undefined,
            boxSizing: 'border-box',
            boxShadow: feather ? `0 0 ${feather}px ${feather / 2}px ${fill}` : undefined,
          }}
        />
      );
    }
    case 'band': {
      const color = str(op.color, '#000');
      return (
        <div
          style={{
            position: 'absolute',
            left: x,
            top: y,
            width: w,
            height: h,
            borderRadius: r,
            border: `${num(op.width, 8)}px solid ${color}`,
            boxSizing: 'border-box',
            boxShadow: feather ? `0 0 ${feather}px ${feather / 2}px ${color}` : undefined,
          }}
        />
      );
    }
    case 'crop': {
      if (!src) return null;
      const [sx, sy, sw, sh] = (op.src as number[]) ?? [0, 0, 0, 0];
      const [dx, dy] = (op.dst as number[]) ?? [sx, sy];
      const lv = Array.isArray(op.levels) ? levels(String(op.levels[0]), String(op.levels[1])) : null;
      const fid = `patch-lv-${id}`;
      return (
        <>
          {lv ? (
            <svg width={0} height={0} style={{position: 'absolute'}}>
              <filter id={fid} colorInterpolationFilters="sRGB">
                <feComponentTransfer>
                  <feFuncR type="linear" slope={lv[0].slope} intercept={lv[0].intercept} />
                  <feFuncG type="linear" slope={lv[1].slope} intercept={lv[1].intercept} />
                  <feFuncB type="linear" slope={lv[2].slope} intercept={lv[2].intercept} />
                </feComponentTransfer>
              </filter>
            </svg>
          ) : null}
          <div style={{position: 'absolute', left: dx, top: dy, width: sw, height: sh, overflow: 'hidden', borderRadius: r, filter: lv ? `url(#${fid})` : undefined}}>
            <Img src={src} style={{position: 'absolute', left: -sx, top: -sy, width: W, height: H, maxWidth: 'none'}} />
          </div>
        </>
      );
    }
    case 'text': {
      if (op.baseline) {
        // Latin text placed by its baseline (x = pen origin), e.g. re-lettering part of a monospace line
        return (
          <svg width={W} height={H} style={{position: 'absolute', left: 0, top: 0, overflow: 'visible'}}>
            <text x={x} y={y} fontFamily={str(op.font, brand.mono)} fontSize={num(op.size, 32)} fill={str(op.color, brand.text)} textAnchor="start" style={{direction: 'ltr', unicodeBidi: 'isolate'}}>
              {str(op.text)}
            </text>
          </svg>
        );
      }
      const anchor = str(op.anchor, 'middle');
      const tx = anchor === 'right' ? '-100%' : anchor === 'left' ? '0' : '-50%';
      return (
        <div
          style={{
            position: 'absolute',
            left: x,
            top: y,
            transform: `translate(${tx}, -50%)`,
            fontFamily: brand.font,
            fontSize: num(op.size, 32),
            fontWeight: num(op.weight, 500),
            color: str(op.color, brand.text),
            direction: ltr ? 'ltr' : 'rtl', // the English capture's patches carry English text
            whiteSpace: 'nowrap',
            lineHeight: 1,
          }}
        >
          {str(op.text)}
        </div>
      );
    }
    case 'qr': {
      // the video's own pairing QR (data/qr.json = lib/demo.ts PAIR_URL), module-exact over the capture's
      const size = num(op.size, 462);
      return (
        <div style={{position: 'absolute', left: x, top: y, width: size, height: size}}>
          <QrCode size={size} quiet={0} radius={0} fg={str(op.fg, '#000000')} bg={str(op.bg, '#FFFFFF')} />
        </div>
      );
    }
    case 'icon': {
      const size = num(op.size, 32);
      return (
        <div style={{position: 'absolute', left: x - size / 2, top: y - size / 2}}>
          <Icon name={str(op.name) as IconName} size={size} color={str(op.color, brand.text)} stroke={num(op.stroke, 2)} />
        </div>
      );
    }
    default:
      return null;
  }
};

/**
 * Draws the patch `patch` of screen `name` over the capture (only when the real capture exists). Laid out in the
 * capture's own pixels and scaled to `width` (the width the screen is drawn at: 1920 for the TV, 390 for phones).
 */
export const ScreenPatch: React.FC<{name: string; patch: string; opacity?: number; width?: number}> = ({name, patch, opacity = 1, width}) => {
  const src = screenSrc(name);
  const ops = screenPatch(name, patch);
  if (!src || !ops.length || opacity <= 0) return null;
  const [W, H] = captureSize(name);
  const k = (width ?? W) / W;
  const ltr = isEnglishScreen(name);
  return (
    <div style={{position: 'absolute', left: 0, top: 0, width: W, height: H, transform: `scale(${k})`, transformOrigin: '0 0', opacity, pointerEvents: 'none'}}>
      {ops.map((op, i) => (
        <Op key={i} op={op} src={src} W={W} H={H} id={`${name}-${patch}-${i}`} ltr={ltr} />
      ))}
    </div>
  );
};
