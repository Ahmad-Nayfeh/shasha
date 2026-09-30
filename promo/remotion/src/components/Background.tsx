import React from 'react';
import {AbsoluteFill, continueRender, delayRender, staticFile, useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {rand} from '../lib/anim';

/**
 * Page background: #0B0F14 with a very soft teal radial glow drifting slowly, a faint warm counter-glow and
 * a vignette. `tint` lets a scene lean the glow toward amber (warnings) or kids yellow.
 */
export const Background: React.FC<{tint?: string; tintAmount?: number}> = ({tint, tintAmount = 0}) => {
  const f = useCurrentFrame();
  const gx = 58 + 14 * Math.sin(f / 260);
  const gy = 34 + 8 * Math.cos(f / 330);
  const hx = 30 - 10 * Math.sin(f / 410);
  return (
    <AbsoluteFill style={{backgroundColor: brand.bg}}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(62% 58% at ${gx}% ${gy}%, rgba(46,196,182,0.075), rgba(46,196,182,0.02) 45%, transparent 72%)`,
        }}
      />
      <AbsoluteFill
        style={{background: `radial-gradient(48% 44% at ${hx}% 86%, rgba(255,181,71,0.028), transparent 70%)`}}
      />
      {tint && tintAmount > 0 ? (
        <AbsoluteFill
          style={{
            background: `radial-gradient(70% 70% at 50% 45%, ${tint}, transparent 70%)`,
            opacity: tintAmount,
          }}
        />
      ) : null}
      <AbsoluteFill
        style={{background: 'radial-gradient(125% 95% at 50% 42%, transparent 58%, rgba(0,0,0,0.42) 100%)'}}
      />
    </AbsoluteFill>
  );
};

const GRAIN = [0, 1, 2, 3, 4, 5].map((k) => staticFile(`fx/grain${k}.png`));
let grainReady = false;
const preloadGrain = () => {
  if (grainReady || typeof window === 'undefined') return;
  grainReady = true;
  const h = delayRender('grain tiles');
  Promise.all(
    GRAIN.map(
      (src) =>
        new Promise<void>((res) => {
          const img = new Image();
          img.onload = () => (img.decode ? img.decode().then(() => res(), () => res()) : res());
          img.onerror = () => res();
          img.src = src;
        }),
    ),
  ).then(() => continueRender(h));
};

/** Subtle animated film grain over everything (6 pre-generated tiles, jittered each frame). */
export const Grain: React.FC<{opacity?: number}> = ({opacity = 0.03}) => {
  preloadGrain();
  // grain changes every 3rd frame (10 fps, film-like, and much kinder to the encoder)
  const g = Math.floor(useCurrentFrame() / 3);
  const k = g % GRAIN.length;
  const ox = Math.floor(rand(g + 1) * 256);
  const oy = Math.floor(rand(g + 77) * 256);
  return (
    <AbsoluteFill
      style={{
        backgroundImage: `url(${GRAIN[k]})`,
        backgroundRepeat: 'repeat',
        backgroundSize: '512px 512px',
        backgroundPosition: `${ox * 2}px ${oy * 2}px`,
        opacity,
        pointerEvents: 'none',
      }}
    />
  );
};
