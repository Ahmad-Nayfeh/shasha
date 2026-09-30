import React from 'react';
import {brand} from '../brand';
import {Header} from '../components/ui';
import {Scene} from '../lib/timeline';

// Temporary stand-in for scenes that are not built yet.
export const Fallback: React.FC<{scene: Scene}> = ({scene}) => (
  <div style={{position: 'absolute', inset: 0}}>
    <Header kicker={scene.section} title={scene.title} />
    <div
      style={{
        position: 'absolute',
        left: 240,
        right: 240,
        top: 220,
        height: 560,
        borderRadius: 28,
        background: brand.surface,
        display: 'grid',
        placeItems: 'center',
        color: brand.muted,
        fontSize: 32,
        fontFamily: brand.mono,
      }}
    >
      {scene.id}
    </div>
  </div>
);
