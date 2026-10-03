import React from 'react';
import qr from '../data/qr.json';
import {isEn} from '../lib/lang';

// Real QR code (segno, version 4-M) of the pairing URL (lib/demo.ts PAIR_URL, port 8765) — generated into data/qr.json.
const rows: string[] = (qr as {rows: string[]}).rows;
const N = rows.length;

const pathFor = (reveal: number) => {
  let d = '';
  for (let y = 0; y < N; y++) {
    for (let x = 0; x < N; x++) {
      if (rows[y][x] !== '1') continue;
      // diagonal sweep from the top-right corner (RTL start); from the top-left corner in English
      const order = ((isEn ? x : N - 1 - x) + y) / (2 * (N - 1));
      if (order > reveal) continue;
      d += `M${x} ${y}h1v1h-1z`;
    }
  }
  return d;
};

export const QrCode: React.FC<{size: number; fg?: string; bg?: string; quiet?: number; reveal?: number; radius?: number}> = ({
  size,
  fg = '#0B0F14',
  bg = '#EAF0F6',
  quiet = 2,
  reveal = 1,
  radius = 0.06,
}) => {
  const total = N + quiet * 2;
  return (
    <svg width={size} height={size} viewBox={`${-quiet} ${-quiet} ${total} ${total}`} shapeRendering="crispEdges" style={{display: 'block'}}>
      <rect x={-quiet} y={-quiet} width={total} height={total} rx={total * radius} fill={bg} shapeRendering="geometricPrecision" />
      <path d={pathFor(reveal)} fill={fg} />
    </svg>
  );
};
