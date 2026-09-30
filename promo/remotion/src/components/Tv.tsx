import React from 'react';
import {AbsoluteFill, Img, staticFile} from 'remotion';
import {brand, rgba} from '../brand';
import {clamp, mix} from '../lib/anim';
import {focusRect, Rect, screenInfo, screenSrc} from '../lib/screens';
import {Placeholder} from './Placeholder';
import {ScreenPatch} from './Patches';
import {FocusRing} from './ui';

export const SW = 1920; // screen space (the app's capture size)
export const SH = 1080;

/** Convert a relative manifest rect to screen-space px. */
export const px = (r: Rect): [number, number, number, number] => [r[0] * SW, r[1] * SH, r[2] * SW, r[3] * SH];
export const fr = (name: string, key: string, index = 0) => px(focusRect(name, key, index));

export type TvFocus = {f: number; key: string; screen?: string; r?: number};

/**
 * Sleek flat-panel TV: near-black bezel, slim pedestal stand with a floor shadow, glass reflection and a
 * soft bias-light glow on the wall.
 *
 *   <TvFrame x y w screenshot="stats" focus={[{f: 30, key: 'chart'}, {f: 90, key: 'channels'}]}
 *            presses={[120]} camera={{s: 1.3, cx: 0.7, cy: 0.4}} />
 *
 * `screenshot` = a name from src/screens.json (real capture or labelled placeholder); `focus` = D-pad focus
 * ring keyframes using the manifest's focus rectangles (relative coords); `camera` = zoom/pan inside the
 * screen (cx, cy relative). Children are laid out in the 1920x1080 screen space (on top of the screenshot,
 * inside the camera) for layered/cross-faded screens.
 */
export const TvFrame: React.FC<{
  x: number; // screen left (stage px)
  y: number; // screen top
  w: number; // screen width (height = w * 9/16)
  children?: React.ReactNode;
  screenshot?: string;
  focus?: TvFocus[];
  focusUntil?: number;
  presses?: number[];
  camera?: {s: number; cx: number; cy: number};
  stand?: boolean;
  glow?: number;
  glowColor?: string;
  shakeX?: number;
  opacity?: number;
  screenOpacity?: number;
  style?: React.CSSProperties;
  overlay?: React.ReactNode; // stage-scale overlay positioned over the screen (not scaled)
}> = ({
  x,
  y,
  w,
  children,
  screenshot,
  focus,
  focusUntil,
  presses,
  camera,
  stand = true,
  glow = 1,
  glowColor = brand.primary,
  shakeX = 0,
  opacity = 1,
  screenOpacity = 1,
  style,
  overlay,
}) => {
  const h = (w * 9) / 16;
  const k = w / 1440; // proportions tuned at 1440 px
  const bz = 13 * k;
  const chin = 5 * k;
  const R = 16 * k;
  return (
    <div
      style={{
        position: 'absolute',
        left: x - bz + shakeX,
        top: y - bz,
        width: w + bz * 2,
        height: h + bz * 2 + chin,
        opacity,
        ...style,
      }}
    >
      {/* bias light on the wall */}
      <div
        style={{
          position: 'absolute',
          left: -w * 0.22,
          right: -w * 0.22,
          top: -h * 0.3,
          bottom: -h * 0.36,
          background: `radial-gradient(50% 50% at 50% 50%, ${rgba(glowColor, 0.11 * glow)}, ${rgba(glowColor, 0.035 * glow)} 55%, transparent 72%)`,
          pointerEvents: 'none',
        }}
      />
      {stand ? (
        <>
          {/* floor shadow */}
          <div
            style={{
              position: 'absolute',
              left: '50%',
              top: h + bz * 2 + chin + 58 * k,
              width: w * 0.62,
              height: 44 * k,
              transform: 'translateX(-50%)',
              background: 'radial-gradient(50% 50% at 50% 50%, rgba(0,0,0,0.55), transparent 70%)',
            }}
          />
          {/* neck */}
          <div
            style={{
              position: 'absolute',
              left: '50%',
              top: h + bz * 2 + chin - 4 * k,
              width: 92 * k,
              height: 60 * k,
              transform: 'translateX(-50%)',
              background: 'linear-gradient(90deg, #0A0E12, #1B232C 45%, #0C1116)',
              clipPath: 'polygon(22% 0, 78% 0, 88% 100%, 12% 100%)',
            }}
          />
          {/* base */}
          <div
            style={{
              position: 'absolute',
              left: '50%',
              top: h + bz * 2 + chin + 54 * k,
              width: w * 0.3,
              height: 11 * k,
              transform: 'translateX(-50%)',
              borderRadius: 999,
              background: 'linear-gradient(180deg, #2A3440 0%, #121820 55%, #080B0F 100%)',
              boxShadow: `0 ${6 * k}px ${16 * k}px rgba(0,0,0,0.5)`,
            }}
          />
        </>
      ) : null}
      {/* body */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          borderRadius: R,
          background: 'linear-gradient(180deg, #151B22 0%, #06090C 6%, #05080B 90%, #0B1015 100%)',
          boxShadow: `0 ${40 * k}px ${90 * k}px rgba(0,0,0,0.6), 0 ${8 * k}px ${22 * k}px rgba(0,0,0,0.45), inset 0 0 0 ${Math.max(1, 1.2 * k)}px rgba(255,255,255,0.07)`,
        }}
      />
      {/* top edge highlight */}
      <div
        style={{
          position: 'absolute',
          left: R,
          right: R,
          top: 0,
          height: Math.max(1, 1.5 * k),
          background: 'linear-gradient(90deg, transparent, rgba(255,255,255,0.22) 30%, rgba(255,255,255,0.28) 60%, transparent)',
        }}
      />
      {/* screen */}
      <div
        style={{
          position: 'absolute',
          left: bz,
          top: bz,
          width: w,
          height: h,
          overflow: 'hidden',
          borderRadius: 3.5 * k,
          background: '#020304',
        }}
      >
        <div
          style={{
            position: 'absolute',
            left: 0,
            top: 0,
            width: SW,
            height: SH,
            transform: `scale(${w / SW})`,
            transformOrigin: '0 0',
            opacity: screenOpacity,
            overflow: 'hidden',
          }}
        >
          <ScreenCamera s={camera?.s ?? 1} cx={(camera?.cx ?? 0.5) * SW} cy={(camera?.cy ?? 0.5) * SH}>
            {screenshot ? <Screen name={screenshot} /> : null}
            {children}
            {focus && focus.length ? (
              <FocusRing
                track={focus.map((k) => ({f: k.f, rect: fr(k.screen ?? screenshot ?? '', k.key), r: k.r}))}
                until={focusUntil}
                presses={presses}
                width={6}
              />
            ) : null}
          </ScreenCamera>
        </div>
        {/* glass: diagonal sheen + subtle top falloff */}
        <div
          style={{
            position: 'absolute',
            inset: 0,
            background:
              'linear-gradient(118deg, rgba(255,255,255,0.075) 0%, rgba(255,255,255,0.028) 22%, rgba(255,255,255,0) 38%, rgba(255,255,255,0) 70%, rgba(255,255,255,0.025) 100%)',
            pointerEvents: 'none',
          }}
        />
        <div
          style={{
            position: 'absolute',
            inset: 0,
            boxShadow: 'inset 0 0 0 1px rgba(0,0,0,0.6), inset 0 0 40px rgba(0,0,0,0.25)',
            pointerEvents: 'none',
          }}
        />
        {overlay}
      </div>
      {/* tiny status LED on the chin */}
      <div
        style={{
          position: 'absolute',
          left: '50%',
          bottom: Math.max(1.5, 3 * k),
          width: 5 * k,
          height: 2.2 * k,
          borderRadius: 2,
          transform: 'translateX(-50%)',
          background: 'rgba(234,240,246,0.28)',
        }}
      />
    </div>
  );
};

/** A manifest screen: the real capture (+ its "base" patch) if present, else the labelled placeholder. */
export const Screen: React.FC<{name: string; opacity?: number; style?: React.CSSProperties; phase?: number}> = ({
  name,
  opacity = 1,
  style,
  phase,
}) => {
  const src = screenSrc(name);
  const info = screenInfo(name);
  const isPhone = info.device === 'phone';
  const W = isPhone ? 390 : SW;
  const H = isPhone ? 844 : SH;
  if (opacity <= 0) return null;
  return (
    <div style={{position: 'absolute', left: 0, top: 0, width: W, height: H, opacity, ...style}}>
      {src ? (
        <>
          <Img src={src} style={{width: W, height: H, display: 'block'}} />
          <ScreenPatch name={name} patch="base" width={W} />
        </>
      ) : info.kind === 'guard' ? (
        <GuardCover />
      ) : (
        <Placeholder name={name} phase={phase} />
      )}
    </div>
  );
};

/**
 * The guard's cover, drawn from its source (app/.../lock/GuardOverlay.kt coverView(): plain Android views, so no
 * screenshot test can capture it): brand background, the splash lockup (300x96 dp), a 56x4 dp teal bar and one
 * calm line (26 sp) — at the TV's 2x density.
 */
export const GuardCover: React.FC = () => (
  <div style={{position: 'absolute', inset: 0, background: '#0B0F14', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center'}}>
    <Img src={staticFile('branding/logo-lockup.svg')} style={{width: 600, height: 192, objectFit: 'contain'}} />
    <div style={{width: 112, height: 8, borderRadius: 4, background: '#2EC4B6', marginTop: 56}} />
    <div style={{marginTop: 44, fontFamily: brand.font, fontWeight: 500, fontSize: 52, color: '#EAF0F6', direction: 'rtl'}}>لحظة… نعود إلى «شاشة»</div>
  </div>
);

/**
 * Zoom/pan inside the screen: scale `s` with the point (cx, cy) (screen px) moved to the middle,
 * clamped so the capture always covers the screen.
 */
export const ScreenCamera: React.FC<{s: number; cx: number; cy: number; children: React.ReactNode; w?: number; h?: number}> = ({
  s,
  cx,
  cy,
  children,
  w = SW,
  h = SH,
}) => {
  const sc = Math.max(1, s);
  const hx = w / (2 * sc);
  const hy = h / (2 * sc);
  const px_ = clamp(cx, hx, w - hx);
  const py_ = clamp(cy, hy, h - hy);
  return (
    <div
      style={{
        position: 'absolute',
        left: 0,
        top: 0,
        width: w,
        height: h,
        transformOrigin: '0 0',
        transform: `translate(${w / 2}px, ${h / 2}px) scale(${sc}) translate(${-px_}px, ${-py_}px)`,
      }}
    >
      {children}
    </div>
  );
};

/** Stage camera: moves point (cx, cy) of the stage to the frame centre at scale s. */
export const Camera: React.FC<{s: number; cx: number; cy: number; children: React.ReactNode}> = ({s, cx, cy, children}) => (
  <AbsoluteFill style={{transformOrigin: '0 0', transform: `translate(960px, 540px) scale(${s}) translate(${-cx}px, ${-cy}px)`}}>
    {children}
  </AbsoluteFill>
);

/** Interpolate camera keyframes [{f, s, cx, cy}] with ease-in-out (scale interpolated in log space). */
export const camAt = (f: number, ks: Array<{f: number; s: number; cx: number; cy: number}>, ease = (t: number) => t) => {
  if (f <= ks[0].f) return ks[0];
  for (let i = 0; i < ks.length - 1; i++) {
    const a = ks[i];
    const b = ks[i + 1];
    if (f <= b.f) {
      const t = ease(clamp((f - a.f) / Math.max(1, b.f - a.f)));
      return {f, s: Math.exp(mix(Math.log(a.s), Math.log(b.s), t)), cx: mix(a.cx, b.cx, t), cy: mix(a.cy, b.cy, t)};
    }
  }
  return ks[ks.length - 1];
};

