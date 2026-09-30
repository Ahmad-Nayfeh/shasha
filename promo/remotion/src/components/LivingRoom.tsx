import React from 'react';
import {brand, rgba} from '../brand';
import {useSvgId} from './Brand';

// Night living-room silhouette (s02, s16): TV on a low console at the left (RTL: where the eye ends),
// sofa facing it at the right with a seated silhouette, floor lamp, window with the moon, a plant.
// `tv` = screen brightness 0..1 (teal glow and rim light follow it); the screen content is passed in.

export const ROOM_TV = {x: 330, y: 468, w: 520, h: 292}; // screen rect in stage px

export const LivingRoom: React.FC<{
  tv?: number;
  lamp?: number;
  screen?: React.ReactNode;
  screenScaleY?: number;
  screenScaleX?: number;
  opacity?: number;
}> = ({tv = 1, lamp = 1, screen, screenScaleY = 1, screenScaleX = 1, opacity = 1}) => {
  const gFloor = useSvgId('fl');
  const gGlow = useSvgId('gl');
  const gLamp = useSvgId('lp');
  const gSofa = useSvgId('sf');
  const T = ROOM_TV;
  const rim = rgba(brand.primary, 0.32 * tv);
  return (
    <div style={{position: 'absolute', inset: 0, opacity}}>
      <svg width={1920} height={1080} viewBox="0 0 1920 1080" style={{position: 'absolute', inset: 0}}>
        <defs>
          <linearGradient id={gFloor} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="#0F151C" />
            <stop offset="1" stopColor="#080B0F" />
          </linearGradient>
          <radialGradient id={gGlow} cx="0.5" cy="0.5" r="0.5">
            <stop offset="0" stopColor={brand.primary} stopOpacity={0.2 * tv} />
            <stop offset="0.55" stopColor={brand.primary} stopOpacity={0.06 * tv} />
            <stop offset="1" stopColor={brand.primary} stopOpacity={0} />
          </radialGradient>
          <radialGradient id={gLamp} cx="0.5" cy="0.5" r="0.5">
            <stop offset="0" stopColor={brand.amber} stopOpacity={0.16 * lamp} />
            <stop offset="0.6" stopColor={brand.amber} stopOpacity={0.04 * lamp} />
            <stop offset="1" stopColor={brand.amber} stopOpacity={0} />
          </radialGradient>
          <linearGradient id={gSofa} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="#18202A" />
            <stop offset="1" stopColor="#0E141A" />
          </linearGradient>
        </defs>
        {/* window with moon (right wall) */}
        <g opacity={0.9}>
          <rect x={1540} y={300} width={250} height={250} rx={14} fill="#0D1522" stroke="#1B2533" strokeWidth={6} />
          <line x1={1665} y1={300} x2={1665} y2={550} stroke="#1B2533" strokeWidth={5} />
          <line x1={1540} y1={425} x2={1790} y2={425} stroke="#1B2533" strokeWidth={5} />
          <circle cx={1728} cy={352} r={24} fill="#DCE6F0" opacity={0.85} />
          <circle cx={1740} cy={344} r={22} fill="#0D1522" />
          {[
            [1580, 340],
            [1615, 470],
            [1700, 500],
            [1760, 470],
            [1600, 395],
          ].map(([x, y], i) => (
            <circle key={i} cx={x} cy={y} r={2} fill="#DCE6F0" opacity={0.5} />
          ))}
        </g>
        {/* lamp glow + tv glow on the wall */}
        <ellipse cx={1760} cy={560} rx={420} ry={360} fill={`url(#${gLamp})`} />
        <ellipse cx={T.x + T.w / 2} cy={T.y + T.h / 2} rx={620} ry={420} fill={`url(#${gGlow})`} />
        {/* floor */}
        <rect x={0} y={832} width={1920} height={248} fill={`url(#${gFloor})`} />
        <ellipse cx={1180} cy={930} rx={560} ry={70} fill="#0C1117" />
        <ellipse cx={T.x + T.w / 2} cy={860} rx={520} ry={40} fill={rgba(brand.primary, 0.05 * tv)} />
        {/* console */}
        <rect x={T.x - 80} y={772} width={T.w + 160} height={64} rx={12} fill="#121820" stroke={rim} strokeWidth={1.5} />
        <rect x={T.x - 60} y={836} width={14} height={20} rx={3} fill="#0D1217" />
        <rect x={T.x + T.w + 46} y={836} width={14} height={20} rx={3} fill="#0D1217" />
        {/* plant */}
        <g>
          <rect x={150} y={740} width={70} height={92} rx={10} fill="#141B23" />
          {[-30, -12, 8, 24, 40].map((a, i) => (
            <ellipse key={i} cx={185 + a * 1.2} cy={690 - Math.abs(a) * 0.6} rx={16} ry={52} transform={`rotate(${a} ${185 + a * 1.2} ${720})`} fill="#16241F" opacity={0.95} />
          ))}
        </g>
        {/* TV body */}
        <rect x={T.x - 12} y={T.y - 12} width={T.w + 24} height={T.h + 24} rx={12} fill="#05080B" stroke="rgba(255,255,255,0.06)" strokeWidth={2} />
        <rect x={T.x + T.w / 2 - 30} y={T.y + T.h + 12} width={60} height={18} fill="#0A0E12" />
        {/* sofa facing left (towards the TV) */}
        <g>
          <path d="M1150 700 Q1150 640 1210 640 L1560 640 Q1640 640 1640 720 L1640 860 L1150 860 Z" fill={`url(#${gSofa})`} />
          <path d="M1560 520 Q1640 520 1650 600 L1672 860 L1590 860 L1566 640 Z" fill="#141B24" />
          <rect x={1130} y={690} width={84} height={170} rx={30} fill="#18212B" />
          <rect x={1150} y={860} width={20} height={26} rx={4} fill="#0B1015" />
          <rect x={1610} y={860} width={20} height={26} rx={4} fill="#0B1015" />
          {/* rim light from the TV on the sofa's left edges */}
          <path d="M1130 860 L1130 720 Q1130 690 1160 690" stroke={rim} strokeWidth={3} fill="none" />
          <path d="M1214 640 L1540 640" stroke={rgba(brand.primary, 0.18 * tv)} strokeWidth={2} />
        </g>
        {/* seated silhouette */}
        <g>
          <path d="M1380 640 Q1372 560 1420 540 L1470 540 Q1510 552 1506 640 Z" fill="#101720" />
          <circle cx={1442} cy={505} r={36} fill="#101720" />
          <path d="M1406 500 Q1408 470 1440 468" stroke={rim} strokeWidth={3} fill="none" strokeLinecap="round" />
          <path d="M1382 636 Q1376 575 1410 548" stroke={rim} strokeWidth={3} fill="none" strokeLinecap="round" />
        </g>
        {/* floor lamp */}
        <g>
          <rect x={1754} y={480} width={8} height={352} fill="#151C24" />
          <ellipse cx={1758} cy={834} rx={46} ry={8} fill="#121820" />
          <path d="M1712 480 L1804 480 L1784 410 L1732 410 Z" fill={lamp > 0.05 ? '#3A2C14' : '#161C23'} />
          <path d="M1716 480 L1800 480" stroke={rgba(brand.amber, 0.6 * lamp)} strokeWidth={3} />
        </g>
      </svg>
      {/* TV screen content (HTML so we can put real UI in it) */}
      <div
        style={{
          position: 'absolute',
          left: T.x,
          top: T.y,
          width: T.w,
          height: T.h,
          overflow: 'hidden',
          background: '#020304',
          borderRadius: 4,
        }}
      >
        <div
          style={{
            position: 'absolute',
            inset: 0,
            transform: `scale(${screenScaleX}, ${screenScaleY})`,
            opacity: tv,
          }}
        >
          {screen}
        </div>
      </div>
    </div>
  );
};
