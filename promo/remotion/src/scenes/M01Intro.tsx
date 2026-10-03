import React from 'react';
import {useCurrentFrame} from 'remotion';
import {LogoMark, Wordmark, WORDMARK_RATIO} from '../components/Brand';
import {HomePage} from '../components/MobileScreens';
import {PhoneStage, SidePill} from '../components/MobileStage';
import {clamp, easeInOut, easeOut, mix, sp, tween} from '../lib/anim';
import {beatsFor, Scene} from '../lib/timeline';
import {brand} from '../brand';

// m01 — logo reveal, then the phone with the app's home feed; «يوتيوب هادئ» as a pill, the ads and the sign-in
// crossed out as they are named.

export const M01Intro: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tPhone = b.at('جوالك');
  const tYt = b.at('يوتيوب');
  const tAds = b.at('إعلانات');
  const tLogin = b.at('تسجيل');

  // logo reveal (as the TV video's intro), then it parks top-right
  const screenIn = sp(f, 4, {damping: 18, stiffness: 90});
  const tri = tween(f, 22, 26, easeOut);
  const dot = (start: number) => {
    const s = sp(f, start, {damping: 14, stiffness: 120});
    return {dy: (1 - s) * -170, opacity: clamp(s * 2.2), scale: mix(0.7, 1, clamp(s))};
  };
  const dots: [ReturnType<typeof dot>, ReturnType<typeof dot>, ReturnType<typeof dot>] = [dot(26), dot(34), dot(42)];
  const wm = tween(f, 46, 22);
  const park = tween(f, tPhone - 10, 30, easeInOut);
  const float = Math.sin(f / 19) * 3 * (1 - park);
  const markW = mix(300, 62, park);
  const S = (markW / 751) * 1024;
  const cx = mix(960, 1792, park);
  const cy = mix(446, 104, park);
  const wmW = mix(340, 122, park);
  const wmH = wmW * WORDMARK_RATIO;
  const wmCx = mix(960, cx - markW / 2 - 20 - wmW / 2, park);
  const wmCy = mix(700, cy - 6, park);

  const scroll = tween(f, tPhone + 30, 260, easeInOut) * 300;
  return (
    <div style={{position: 'absolute', inset: 0}}>
      <PhoneStage f={f} from={tPhone - 8} glow={brand.primary}>
        <HomePage scroll={scroll} />
      </PhoneStage>

      <SidePill f={f} at={tYt} icon="moon" label="يوتيوب هادئ" top={330} />
      <SidePill f={f} at={tAds - 8} icon="tag" label="إعلانات" top={460} crossAt={tAds + 6} />
      <SidePill f={f} at={tLogin - 8} icon="user" label="تسجيل الدخول" top={590} crossAt={tLogin + 12} />

      <div style={{position: 'absolute', left: cx - 512 * (S / 1024), top: cy - 651 * (S / 1024), width: S, height: S, transform: `translateY(${float}px)`}}>
        <LogoMark
          size={S}
          screenScale={mix(0.85, 1, screenIn)}
          screenOpacity={clamp(screenIn * 1.5)}
          triangleScale={mix(0.55, 1, tri)}
          dots={[
            {...dots[0], dy: dots[0].dy + Math.sin(f / 21) * 6 * (1 - park)},
            {...dots[1], dy: dots[1].dy + Math.sin(f / 21 + 1) * 6 * (1 - park)},
            {...dots[2], dy: dots[2].dy + Math.sin(f / 21 + 2) * 6 * (1 - park)},
          ]}
        />
      </div>
      <div style={{position: 'absolute', left: wmCx - wmW / 2, top: wmCy - wmH / 2 + (1 - wm) * 24, opacity: wm}}>
        <Wordmark width={wmW} />
      </div>
    </div>
  );
};
