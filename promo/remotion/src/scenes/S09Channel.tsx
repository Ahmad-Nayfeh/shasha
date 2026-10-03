import React from 'react';
import {useCurrentFrame} from 'remotion';
import {ListCard, ListRow} from '../components/ListCard';
import {fr, Screen, TvFrame} from '../components/Tv';
import {featureTv, FocusRing, FocusKey, Header, stepOf} from '../components/ui';
import {clamp, sp, tween} from '../lib/anim';
import {flowSign, t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// s09 — the channel page: sections (videos, Shorts, live, playlists) and the sort order (newest, most viewed,
// oldest). The D-pad ring walks along the real chips; the two cards on the right (left in English) light up in step
// with the voice. The English labels are the app's own (channel_tab_*, channel_sort_*).

const TV = featureTv();

export const S09Channel: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tPage = b.at('وصفحة');
  const tVid = b.at('الفيديوهات');
  const tShorts = b.at('والقصيرة');
  const tLive = b.at('والبث');
  const tLists = b.at('وقوائم');
  const tSort = b.at('وتختار');
  const tNew = b.at('الأحدث');
  const tMost = b.at('الأكثر');
  const tOld = b.at('الأقدم');

  const tvIn = sp(f, 0, {damping: 24, stiffness: 90});
  const toShorts = tween(f, tShorts - 6, 10);

  // the capture's own coordinates: the Videos capture first, then the Shorts capture (the page scrolls up a little)
  const V = (k: string) => fr('channel_videos', k);
  const S = (k: string) => fr('channel_shorts', k);
  const track: FocusKey[] = [
    {f: tVid - 2, rect: V('tabVideos'), r: 44},
    {f: tShorts - 2, rect: S('tabShorts'), r: 44},
    {f: tLive - 2, rect: S('tabLive'), r: 44},
    {f: tLists - 2, rect: S('tabLists'), r: 44},
    {f: tNew - 4, rect: S('sortNewest'), r: 40},
    {f: tMost - 4, rect: S('sortViewed'), r: 40},
    {f: tOld - 4, rect: S('sortOldest'), r: 40},
  ];
  const presses = [tShorts + 8, tLive + 8, tLists + 8, tMost + 8, tOld + 8];

  const sections: ListRow[] = [
    {icon: 'play', label: t('الفيديوهات'), at: tVid},
    {icon: 'phone', label: t('القصيرة (Shorts)'), at: tShorts},
    {icon: 'wifi', label: t('البث المباشر'), at: tLive},
    {icon: 'list', label: t('قوائم التشغيل'), at: tLists},
  ];
  const sorts: ListRow[] = [
    {icon: 'clock', label: t('الأحدث'), at: tNew},
    {icon: 'barChart', label: t('الأكثر مشاهدة'), at: tMost},
    {icon: 'calendar', label: t('الأقدم'), at: tOld},
  ];

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker={t('المشاهدة')} index={stepOf(1, 3)} title={t('صفحة القناة')} />

      <TvFrame x={TV.x + (1 - tvIn) * -60 * flowSign} y={TV.y} w={TV.w} opacity={clamp(tvIn * 1.4)}>
        <Screen name="channel_videos" />
        <Screen name="channel_shorts" opacity={toShorts} />
        <FocusRing track={track} width={5} presses={presses} />
      </TvFrame>

      <ListCard title={t('الأقسام')} icon="layers" rows={sections} f={f} t={sp(f, tPage, {damping: 22})} top={214} />
      <ListCard title={t('الترتيب')} icon="sliders" rows={sorts} f={f} t={sp(f, tSort - 4, {damping: 22})} top={604} />

    </div>
  );
};
