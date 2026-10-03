import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {ListCard, ListRow} from '../components/ListCard';
import {ChannelPage} from '../components/MobileScreens';
import {MobileHeader, PhoneStage, TapAt} from '../components/MobileStage';
import {easeInOut, sp, tween} from '../lib/anim';
import {beatsFor, Scene} from '../lib/timeline';

// m06 — the channel page of the phone app: three sections (videos, Shorts, playlists), the indicator slides along.

export const M06Channel: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tChan = b.at('القناة');
  const tVid = b.at('الفيديوهات');
  const tShorts = b.at('والقصيرة');
  const tLists = b.at('وقوائم');

  const tab = tween(f, tShorts - 4, 14, easeInOut) + tween(f, tLists - 4, 14, easeInOut);
  const rows: ListRow[] = [
    {icon: 'play', label: 'الفيديوهات', at: tVid},
    {icon: 'phone', label: 'القصيرة', at: tShorts},
    {icon: 'list', label: 'قوائم التشغيل', at: tLists},
  ];
  return (
    <div style={{position: 'absolute', inset: 0}}>
      <MobileHeader kicker="المشاهدة" index="٢ / ٢" title="صفحة القناة" />
      <PhoneStage f={f} from={-60} glow={brand.primary}>
        <ChannelPage tab={tab} />
        <TapAt f={f} at={tShorts - 2} x={195} y={288} />
        <TapAt f={f} at={tLists - 2} x={65} y={288} />
      </PhoneStage>
      <ListCard title="الأقسام" icon="layers" rows={rows} f={f} t={sp(f, tChan - 4, {damping: 22})} top={300} width={520} />
    </div>
  );
};
