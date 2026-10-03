import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand} from '../brand';
import {LogoMark} from '../components/Brand';
import {SideSteps, StepHeader} from '../components/Install';
import {PinDotsOverlay} from '../components/Overlays';
import {TV} from '../components/TvStep';
import {Screen, TvFrame} from '../components/Tv';
import {clamp, easeOut, mix, tween} from '../lib/anim';
import {t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';

// Step 8 (TV): first start of Shasha — parent PIN, your profile, the child's profile (daily minutes, allowed
// hours), then the channels the child may watch.
export const S23FirstStart: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tFirst = b.at('أول تشغيل');
  const tPin = b.at('اختر رمز');
  const tYours = b.at('ثم أنشئ');
  const tChild = b.at('وملف طفلك');
  const tMinutes = b.at('بدقائقه');
  const tChannels = b.at('ثم أضف');

  const splash = tween(f, tFirst - 6, 10) * (1 - tween(f, tPin - 8, 10));
  const pinIn = tween(f, tPin - 8, 10);
  const dots = clamp((f - (tPin + 12)) / 6, 0, 4);
  const profIn = tween(f, tChild - 6, 10);
  const quotaIn = tween(f, tMinutes - 4, 10);
  const kidsIn = tween(f, tChannels - 4, 12);
  return (
    <div style={{position: 'absolute', inset: 0}}>
      <StepHeader step={8} />
      <TvFrame x={TV.x} y={TV.y} w={TV.w}>
        <div style={{position: 'absolute', inset: 0, background: brand.bg}} />
        <div style={{position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', opacity: splash}}>
          <div style={{transform: `scale(${mix(0.8, 1, tween(f, tFirst - 6, 16, easeOut))})`}}>
            <LogoMark size={420} />
          </div>
        </div>
        <div style={{position: 'absolute', inset: 0, opacity: pinIn * (1 - profIn)}}>
          <Screen name="first_run_pin" />
          <PinDotsOverlay name="first_run_pin" filled={dots} />
        </div>
        <Screen name="profiles_setup" opacity={profIn * (1 - quotaIn)} />
        <Screen name="settings_quota" opacity={quotaIn * (1 - kidsIn)} />
        <Screen name="kids_home" opacity={kidsIn} />
      </TvFrame>
      <SideSteps
        items={[
          {at: tPin, label: t('رمز الوالدين'), sub: '4–8 digits', icon: 'lock'},
          {at: tYours, label: t('ملفك (ملف الكبار)'), icon: 'user'},
          {at: tChild, label: t('ملف الطفل: دقائق وساعات'), icon: 'hourglass'},
          {at: tChannels, label: t('القنوات المسموحة'), icon: 'list'},
        ]}
      />
    </div>
  );
};
