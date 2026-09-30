import React from 'react';
import {brand} from '../brand';

// Generic TV launcher (no real brands): hero banner + rows of coloured app tiles with neutral glyphs.
const TILE = ['#E0564F', '#3E7BFA', '#F2A93B', '#35B37E', '#8E6CF0', '#E85D9A', '#2BB3C0', '#F06A3B'];
const Glyph: React.FC<{i: number}> = ({i}) => {
  const k = i % 4;
  const s = {fill: 'rgba(255,255,255,0.9)'};
  return (
    <svg width={90} height={90} viewBox="0 0 90 90">
      {k === 0 ? <circle cx={45} cy={45} r={24} {...s} /> : null}
      {k === 1 ? <path d="M32 24 L68 45 L32 66 Z" {...s} /> : null}
      {k === 2 ? <rect x={23} y={23} width={44} height={44} rx={10} {...s} /> : null}
      {k === 3 ? <path d="M45 20 L52 38 L71 38 L56 50 L62 69 L45 57 L28 69 L34 50 L19 38 L38 38 Z" {...s} /> : null}
    </svg>
  );
};

export const Launcher: React.FC = () => (
  <div style={{position: 'absolute', left: 0, top: 0, width: 1920, height: 1080, background: 'linear-gradient(180deg, #1B2230, #0E1219)', direction: 'ltr', fontFamily: brand.font}}>
    <div style={{position: 'absolute', left: 90, top: 50, width: 64, height: 64, borderRadius: 999, background: '#3A4658'}} />
    {[0, 1, 2, 3].map((i) => (
      <div key={i} style={{position: 'absolute', left: 200 + i * 170, top: 70, width: 130, height: 24, borderRadius: 12, background: i === 0 ? 'rgba(255,255,255,0.8)' : 'rgba(255,255,255,0.22)'}} />
    ))}
    <div style={{position: 'absolute', right: 90, top: 58, width: 160, height: 48, borderRadius: 24, background: 'rgba(255,255,255,0.14)'}} />
    <div
      style={{
        position: 'absolute',
        left: 90,
        top: 150,
        width: 1740,
        height: 420,
        borderRadius: 30,
        background: 'radial-gradient(60% 90% at 70% 40%, #5B3A8A, #2A1B45 60%, #171126)',
        overflow: 'hidden',
      }}
    >
      <div style={{position: 'absolute', left: 80, top: 120, width: 520, height: 44, borderRadius: 22, background: 'rgba(255,255,255,0.85)'}} />
      <div style={{position: 'absolute', left: 80, top: 190, width: 380, height: 26, borderRadius: 13, background: 'rgba(255,255,255,0.4)'}} />
      <div style={{position: 'absolute', left: 80, top: 260, width: 200, height: 64, borderRadius: 32, background: 'rgba(255,255,255,0.9)'}} />
    </div>
    <div style={{position: 'absolute', left: 90, top: 620, width: 220, height: 26, borderRadius: 13, background: 'rgba(255,255,255,0.35)'}} />
    {Array.from({length: 7}).map((_, i) => (
      <div
        key={i}
        style={{
          position: 'absolute',
          left: 90 + i * 250,
          top: 680,
          width: 226,
          height: 128,
          borderRadius: 22,
          background: TILE[i % TILE.length],
          display: 'grid',
          placeItems: 'center',
          boxShadow: i === 0 ? '0 0 0 6px #fff' : undefined,
        }}
      >
        <Glyph i={i} />
      </div>
    ))}
    <div style={{position: 'absolute', left: 90, top: 860, width: 180, height: 26, borderRadius: 13, background: 'rgba(255,255,255,0.3)'}} />
    {Array.from({length: 5}).map((_, i) => (
      <div key={i} style={{position: 'absolute', left: 90 + i * 350, top: 910, width: 320, height: 180, borderRadius: 22, background: `linear-gradient(135deg, ${TILE[(i + 3) % 8]}, #1A1F2A)`, opacity: 0.8}} />
    ))}
  </div>
);
