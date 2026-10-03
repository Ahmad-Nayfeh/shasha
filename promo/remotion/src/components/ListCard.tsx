import React from 'react';
import {brand, rgba} from '../brand';
import {Icon, IconName} from './Icons';
import {Card} from './ui';
import {clamp, tween} from '../lib/anim';
import {flowSign, isEn} from '../lib/lang';

// A card with a list whose rows light up in turn, in step with the narration (rows[i].at = the frame of the word).
// `side` is the side of the Arabic layout; the English version is its mirror image (flowSign / the opposite edge).

export type ListRow = {icon: IconName; label: string; at: number};

export const ListCard: React.FC<{title: string; icon: IconName; rows: ListRow[]; f: number; t: number; top: number; side?: 'right' | 'left'; width?: number}> = ({
  title,
  icon,
  rows,
  f,
  t,
  top,
  side = 'right',
  width = 500,
}) => {
  const active = rows.reduce((a, r, i) => (f >= r.at - 4 ? i : a), -1);
  const edge = isEn ? (side === 'right' ? 'left' : 'right') : side;
  return (
    <div style={{position: 'absolute', [edge]: side === 'right' ? 90 : 100, top, opacity: clamp(t * 1.4), transform: `translateX(${(1 - t) * (side === 'right' ? -40 : 40) * flowSign}px)`}}>
      <Card style={{width, padding: '26px 30px 22px', boxSizing: 'border-box'}} tone="teal">
        <div style={{display: 'flex', alignItems: 'center', gap: 14, marginBottom: 14}}>
          <Icon name={icon} size={36} color={brand.primary} stroke={2.2} />
          <div style={{fontSize: 32, fontWeight: 700}}>{title}</div>
        </div>
        {rows.map((r, i) => {
          const on = i === active;
          const k = tween(f, r.at - 4, 10);
          return (
            <div
              key={r.label}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 16,
                height: 62,
                marginTop: 4,
                padding: '0 14px',
                borderRadius: 18,
                background: on ? rgba(brand.primary, 0.16) : 'transparent',
                border: `1.5px solid ${on ? rgba(brand.primary, 0.55) : 'transparent'}`,
                opacity: clamp(k * 3) * (on ? 1 : 0.62),
                transform: `translateX(${(1 - k) * -20 * flowSign}px)`,
              }}
            >
              <Icon name={r.icon} size={30} color={on ? brand.primaryBright : brand.muted} stroke={2.2} />
              <div style={{fontSize: 30, fontWeight: on ? 700 : 500, color: on ? brand.text : brand.muted}}>{r.label}</div>
            </div>
          );
        })}
      </Card>
    </div>
  );
};
