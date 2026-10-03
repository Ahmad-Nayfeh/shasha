import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {ChannelPage, HomePage, SubscriptionsPage} from '../components/MobileScreens';
import {Callout, MobileHeader, PhoneStage, TapAt} from '../components/MobileStage';
import {tween} from '../lib/anim';
import {beatsFor, Scene} from '../lib/timeline';

// m12 — subscriptions: one tap on «اشتراك», and the channel's new videos arrive in the home feed, all in one place.

export const M12Subscriptions: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tSub = b.at('وتشترك');
  const tOne = b.at('بضغطة');
  const tFind = b.at('فتجد');
  const tPlace = b.at('مكان');

  const tapSub = tOne + 4;
  const subscribed = tween(f, tapSub + 4, 8);
  const press = Math.max(0, 1 - Math.abs(f - tapSub) / 10);
  const toHome = tween(f, tFind + 2, 14);
  const toList = tween(f, tPlace + 6, 14);

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <MobileHeader kicker="مكتبتك" index="٢ / ٣" title="الاشتراكات" />
      <PhoneStage f={f} from={-60} glow={brand.primary}>
        <ChannelPage tab={0} subscribed={subscribed} pressSub={press} />
        <div style={{position: 'absolute', inset: 0, opacity: toHome * (1 - toList)}}>
          <HomePage scroll={0} />
        </div>
        <div style={{position: 'absolute', inset: 0, opacity: toList}}>
          <SubscriptionsPage />
        </div>
        <TapAt f={f} at={tapSub} x={73} y={234} />
      </PhoneStage>

      <Callout f={f} at={tSub - 4} icon="star" title="اشتراك بضغطة" top={300} />
      <Callout f={f} at={tFind - 4} icon="home" title="جديدها في الرئيسية" sub="في مكان واحد" top={470} />
    </div>
  );
};
