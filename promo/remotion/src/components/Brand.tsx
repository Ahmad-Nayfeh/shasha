import React, {useId} from 'react';
import {
  MARK_DOT_R,
  MARK_DOTS,
  MARK_SCREEN,
  MARK_TRIANGLE,
  WORDMARK_ACCENT,
  WORDMARK_TEXT,
  WORDMARK_VIEWBOX,
} from './brandPaths';

export const useSvgId = (p: string) => p + useId().replace(/[^a-zA-Z0-9]/g, '');

export type DotState = {dy?: number; opacity?: number; scale?: number};

/**
 * The brand mark (logo-mark.svg) with animatable parts: the teal screen, the hollow play triangle
 * (a mask, so it can grow like the splash animation) and the three amber dots of «ش».
 * `size` is the size of the 1024-unit art box; the visible mark spans x 136..888, y 112..912 of it.
 */
export const LogoMark: React.FC<{
  size: number;
  screenScale?: number;
  screenOpacity?: number;
  triangleScale?: number;
  dots?: [DotState, DotState, DotState];
  mono?: string;
  style?: React.CSSProperties;
}> = ({size, screenScale = 1, screenOpacity = 1, triangleScale = 1, dots, mono, style}) => {
  const gS = useSvgId('ms');
  const gD = useSvgId('md');
  const mk = useSvgId('mk');
  const d = dots ?? [{}, {}, {}];
  return (
    <svg width={size} height={size} viewBox="0 0 1024 1024" style={{overflow: 'visible', ...style}}>
      <defs>
        <linearGradient id={gS} gradientUnits="userSpaceOnUse" x1="0" y1="390.39" x2="0" y2="912">
          <stop offset="0" stopColor="#48DCCD" />
          <stop offset="1" stopColor="#1FAE9F" />
        </linearGradient>
        <linearGradient id={gD} gradientUnits="userSpaceOnUse" x1="0" y1="112" x2="0" y2="340.31">
          <stop offset="0" stopColor="#FFC56B" />
          <stop offset="1" stopColor="#FFA92E" />
        </linearGradient>
        <mask id={mk} maskUnits="userSpaceOnUse" x="0" y="0" width="1024" height="1024">
          <rect x="0" y="0" width="1024" height="1024" fill="white" />
          <path
            d={MARK_TRIANGLE}
            fill="black"
            transform={`translate(512 651) scale(${triangleScale}) translate(-512 -651)`}
          />
        </mask>
      </defs>
      <g
        opacity={screenOpacity}
        transform={`translate(512 651) scale(${screenScale}) translate(-512 -651)`}
        mask={`url(#${mk})`}
      >
        <path d={MARK_SCREEN} fill={mono ?? `url(#${gS})`} />
      </g>
      {MARK_DOTS.map(([cx, cy], i) => {
        const s = d[i];
        const sc = s.scale ?? 1;
        return (
          <circle
            key={i}
            cx={cx}
            cy={cy + (s.dy ?? 0)}
            r={MARK_DOT_R * sc}
            fill={mono ?? `url(#${gD})`}
            opacity={s.opacity ?? 1}
            // gradient is userSpace: shift it with the dot so the colour stays the same while it moves
            style={{}}
          />
        );
      })}
    </svg>
  );
};

/** Wordmark «شاشة» (outlined paths from logo-wordmark.svg). `width` in px. */
export const Wordmark: React.FC<{width: number; opacity?: number; style?: React.CSSProperties; color?: string}> = ({
  width,
  opacity = 1,
  style,
  color,
}) => {
  const g = useSvgId('wa');
  const [, , vw, vh] = WORDMARK_VIEWBOX.split(' ').map(Number);
  return (
    <svg width={width} height={(width * vh) / vw} viewBox={WORDMARK_VIEWBOX} style={{opacity, overflow: 'visible', ...style}}>
      <defs>
        <linearGradient id={g} gradientUnits="userSpaceOnUse" x1="0" y1="98.05" x2="0" y2="214.38">
          <stop offset="0" stopColor="#FFC56B" />
          <stop offset="1" stopColor="#FFA92E" />
        </linearGradient>
      </defs>
      <path d={WORDMARK_TEXT} fill={color ?? '#EAF0F6'} />
      <path d={WORDMARK_ACCENT} fill={color ?? `url(#${g})`} />
    </svg>
  );
};

export const WORDMARK_RATIO = (() => {
  const [, , vw, vh] = WORDMARK_VIEWBOX.split(' ').map(Number);
  return vh / vw;
})();

/**
 * Horizontal lockup (mark on the right, wordmark on the left — as logo-lockup.svg).
 * `height` = height of the mark's screen in px.
 */
export const Lockup: React.FC<{height: number; style?: React.CSSProperties; dotsFloat?: number}> = ({
  height,
  style,
  dotsFloat = 0,
}) => {
  // In the 1024 box the screen is 521.6 units tall; the whole mark (with dots) spans 112..912.
  const unit = height / 521.6;
  const markBox = 1024 * unit;
  const wmWidth = height * 2.0;
  return (
    <div style={{display: 'flex', alignItems: 'flex-end', direction: 'rtl', gap: height * 0.26, ...style}}>
      <div style={{width: 751 * unit, height: 800 * unit, position: 'relative', overflow: 'visible'}}>
        <LogoMark
          size={markBox}
          style={{position: 'absolute', left: -136.44 * unit, top: -112 * unit}}
          dots={[{dy: dotsFloat}, {dy: dotsFloat * 0.8}, {dy: dotsFloat * 1.2}]}
        />
      </div>
      <Wordmark width={wmWidth} style={{marginBottom: -height * 0.12}} />
    </div>
  );
};
