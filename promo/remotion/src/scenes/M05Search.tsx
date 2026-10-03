import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {HomePage, SearchPage} from '../components/MobileScreens';
import {Callout, MobileHeader, PhoneStage, SidePill, TapAt} from '../components/MobileStage';
import {tween} from '../lib/anim';
import {beatsFor, Scene} from '../lib/timeline';

// m05 — search: open it, type, the results (videos, channels, playlists) without ads between them.

const QUERY = 'الخط العربي';

export const M05Search: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tOpen = b.at('افتح');
  const tType = b.at('واكتب');
  const tShow = b.at('تظهر');
  const tNoAds = b.at('من غير');

  const tapSearch = tOpen + 8;
  const toSearch = tween(f, tapSearch + 6, 10);
  const typed = tween(f, tType, 34) * QUERY.length;
  const results = tween(f, tShow, 50);

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <MobileHeader kicker="المشاهدة" index="١ / ٢" title="البحث" />
      <PhoneStage f={f} from={-60} glow={brand.primary}>
        <HomePage scroll={120} />
        <div style={{position: 'absolute', inset: 0, opacity: toSearch}}>
          <SearchPage query={QUERY} typed={typed} results={results} cursor={typed < QUERY.length + 0.5 && Math.floor(f / 8) % 2 === 0} />
        </div>
        <TapAt f={f} at={tapSearch} x={240} y={804} />
      </PhoneStage>

      <Callout f={f} at={tShow - 6} icon="search" title="فيديوهات وقنوات" sub="وقوائم تشغيل" top={300} />
      <SidePill f={f} at={tNoAds - 6} icon="tag" label="إعلانات بينها" top={470} crossAt={tNoAds + 20} />
    </div>
  );
};
