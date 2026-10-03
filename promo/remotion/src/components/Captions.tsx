import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {tween, win} from '../lib/anim';
import {dir} from '../lib/lang';
import {captionChunks, Scene} from '../lib/timeline';

/**
 * Burned-in captions (Arabic, or English in the English version): phrase-level chunks of the cue's display text,
 * timed to the spoken words. Sits on a soft surface pill at the bottom centre.
 */
export const Captions: React.FC<{scene: Scene; bottom?: number}> = ({scene, bottom = 44}) => {
  const f = useCurrentFrame();
  const chunks = captionChunks(scene);
  return (
    <>
      {chunks.map((c, i) => {
        // back-to-back chunks: the old one fades out by `to`, the next fades in from `to` (no overlap)
        if (f < c.from - 1 || f > c.to + 1) return null;
        const o = win(f, c.from, c.to, 6, 5);
        const y = (1 - tween(f, c.from, 12)) * 10;
        return (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: 0,
              right: 0,
              bottom,
              display: 'flex',
              justifyContent: 'center',
              opacity: o,
              transform: `translateY(${y}px)`,
              pointerEvents: 'none',
            }}
          >
            <div
              style={{
                direction: dir,
                fontFamily: brand.font,
                fontWeight: 500,
                fontSize: 38,
                lineHeight: 1.5,
                color: brand.text,
                background: 'rgba(15,20,27,0.84)',
                border: '1px solid rgba(234,240,246,0.08)',
                boxShadow: '0 12px 34px rgba(0,0,0,0.38)',
                borderRadius: 18,
                padding: '8px 30px 11px',
                maxWidth: 1480,
                textAlign: 'center',
              }}
            >
              {c.text}
            </div>
          </div>
        );
      })}
    </>
  );
};
