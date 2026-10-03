import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, mixHex, rgba} from '../brand';
import {Icon} from '../components/Icons';
import {fr, Screen, TvFrame} from '../components/Tv';
import {atStart, Card, featureTv, FocusRing, Header, stepOf} from '../components/ui';
import {clamp, easeInOut, mix, sp, tween} from '../lib/anim';
import {dir, flowSign, isEn, t} from '../lib/lang';
import {isEnglishScreen} from '../lib/screens';
import {beatsFor, Scene} from '../lib/timeline';

// s08 — suggested videos and Shorts: two switches on every profile's page. The capture shows both off (the child's
// profile); the switches are redrawn over it so they can be turned on and off. The result per profile: the adult's
// home with the «مقترحة لك» row and a Shorts badge, the child's home as calm as before.

const TV = featureTv();

/**
 * A profile-page switch redrawn over the capture: p 0 = off (متوقف / Off), 1 = on (مفعّل / On). Capture px. (x, y) is the
 * switch's track as the manifest has it (focus `shortsToggle` / `suggestionsToggle`). In the Arabic capture the state
 * word sits to the right of the track (the row is right to left); in the English capture it sits to the left of it.
 */
const Switch: React.FC<{x: number; y: number; cover: [number, number, number, number]; bg: string; p: number}> = ({x, y, cover, bg, p}) => {
  const ltr = isEnglishScreen('profile_toggles');
  return (
    <>
      <div style={{position: 'absolute', left: cover[0], top: cover[1], width: cover[2], height: cover[3], background: bg}} />
      <div style={{position: 'absolute', left: x, top: y, width: 104, height: 60, borderRadius: 30, background: mixHex('#2E3947', '#2EC4B6', p)}}>
        <div
          style={{
            position: 'absolute',
            top: 8,
            left: ltr ? mix(8, 52, p) : mix(52, 8, p),
            width: 44,
            height: 44,
            borderRadius: 999,
            background: mixHex('#8A97A8', '#04201D', p),
          }}
        />
      </div>
      <div style={{position: 'absolute', left: ltr ? x - 134 : x + 128, top: y + 4, width: 110, height: 52, display: 'grid', placeItems: ltr ? 'center end' : 'center start', fontFamily: brand.font, fontSize: 31, fontWeight: 600, direction: dir}}>
        <span style={{position: 'absolute', color: '#8A97A8', opacity: 1 - p}}>{t('متوقف')}</span>
        <span style={{position: 'absolute', color: brand.primary, opacity: p}}>{t('مفعّل')}</span>
      </div>
    </>
  );
};

/** The switch's place on the capture: the manifest's track rect; the cover hides the capture's own switch and its word. */
const switchAt = (key: string) => {
  const [x, y] = fr('profile_toggles', key);
  return {x, y, cover: (isEnglishScreen('profile_toggles') ? [x - 130, y - 10, 250, 80] : [x - 16, y - 10, 250, 80]) as [number, number, number, number]};
};

/** A capture shown small (a mini TV picture). */
const Mini: React.FC<{name: string; w: number}> = ({name, w}) => (
  <div style={{width: w, height: (w * 9) / 16, borderRadius: 16, overflow: 'hidden', position: 'relative', boxShadow: '0 0 0 1.5px rgba(234,240,246,0.12)'}}>
    <div style={{position: 'absolute', left: 0, top: 0, width: 1920, height: 1080, transform: `scale(${w / 1920})`, transformOrigin: '0 0'}}>
      <Screen name={name} />
    </div>
  </div>
);

const Pill: React.FC<{label: string; on: boolean; t: number}> = ({label, on, t: k}) => (
  <div
    style={{
      display: 'inline-flex',
      alignItems: 'center',
      gap: 10,
      padding: '8px 20px 10px',
      borderRadius: 99,
      fontSize: 25,
      fontWeight: 600,
      background: on ? rgba(brand.primary, 0.14) : 'rgba(234,240,246,0.07)',
      border: `1.5px solid ${on ? rgba(brand.primary, 0.5) : 'rgba(234,240,246,0.16)'}`,
      color: on ? brand.primaryBright : brand.muted,
      opacity: clamp(k * 1.4),
      transform: `scale(${mix(0.9, 1, k)})`,
    }}
  >
    <Icon name={on ? 'check' : 'x'} size={24} color={on ? brand.primaryBright : brand.muted} stroke={3} />
    {label}
  </div>
);

export const S08Suggest: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tSugg = b.at('المقترحة');
  const tShorts = b.at('شورتس');
  const tShow = b.at('إظهارها');
  const tHide = b.at('إخفاءها');
  const tEach = b.at('لكل ملف');
  const tKid = b.at('طفلك');
  const tYours = b.at('ما تحب');

  const tvIn = sp(f, 0, {damping: 24, stiffness: 90});
  // switches: on at «إظهارها», off again at «إخفاءها». The English voice says «shown or hidden» about half as far apart
  // (12 frames against 23), so there each switch is held on a little longer (and turned on a little sooner).
  const [sOn, sOff, hOn, hOff] = isEn ? [4, 12, 10, 20] : [6, 8, 16, 18];
  const pSugg = tween(f, tShow + sOn, 10, easeInOut) * (1 - tween(f, tHide + sOff, 10, easeInOut));
  const pShorts = tween(f, tShow + hOn, 10, easeInOut) * (1 - tween(f, tHide + hOff, 10, easeInOut));
  const adultIn = sp(f, tEach + 2, {damping: 22});
  const kidIn = sp(f, tKid - 4, {damping: 22});
  const adultGlow = tween(f, tYours - 4, 14);

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker={t('حماية الأطفال')} index={stepOf(5, 5)} title={t('المقترحة وShorts')} />

      <TvFrame x={TV.x + (1 - tvIn) * -60 * flowSign} y={TV.y} w={TV.w} opacity={clamp(tvIn * 1.4)}>
        <Screen name="profile_toggles" />
        <Switch {...switchAt('shortsToggle')} bg="#151B24" p={pShorts} />
        <Switch {...switchAt('suggestionsToggle')} bg="#28323F" p={pSugg} />
        <FocusRing
          track={[
            {f: tSugg - 2, rect: fr('profile_toggles', 'suggestionsToggle'), r: 34},
            {f: tShorts - 2, rect: fr('profile_toggles', 'shortsToggle'), r: 34},
          ]}
          until={tEach - 6}
          width={5}
        />
      </TvFrame>

      {/* the two profiles: what each one sees */}
      <div style={{position: 'absolute', ...atStart(90), top: 190, opacity: clamp(adultIn * 1.4), transform: `translateX(${(1 - adultIn) * -40 * flowSign}px)`}}>
        <Card style={{width: 408, padding: '22px 24px 24px', boxSizing: 'border-box'}} tone="teal" glow={adultGlow}>
          <div style={{display: 'flex', alignItems: 'center', gap: 14, marginBottom: 14}}>
            <Icon name="user" size={36} color={brand.primary} stroke={2.2} />
            <div style={{fontSize: 33, fontWeight: 700}}>{t('ملف الكبار')}</div>
          </div>
          <Mini name="home_suggested" w={360} />
          <div style={{display: 'flex', gap: 14, marginTop: 16}}>
            <Pill label={t('المقترحة')} on t={sp(f, tEach + 14, {damping: 20})} />
            <Pill label="Shorts" on t={sp(f, tEach + 22, {damping: 20})} />
          </div>
        </Card>
      </div>
      <div style={{position: 'absolute', ...atStart(90), top: 570, opacity: clamp(kidIn * 1.4), transform: `translateX(${(1 - kidIn) * -40 * flowSign}px)`}}>
        <Card style={{width: 408, padding: '22px 24px 24px', boxSizing: 'border-box'}} tone="kids">
          <div style={{display: 'flex', alignItems: 'center', gap: 14, marginBottom: 14}}>
            <Icon name="star" size={36} color={brand.kids} stroke={2.2} />
            <div style={{fontSize: 33, fontWeight: 700}}>{t('ملف الطفل')}</div>
          </div>
          <Mini name="kids_home" w={360} />
          <div style={{display: 'flex', gap: 14, marginTop: 16}}>
            <Pill label={t('المقترحة')} on={false} t={sp(f, tKid + 10, {damping: 20})} />
            <Pill label="Shorts" on={false} t={sp(f, tKid + 18, {damping: 20})} />
          </div>
        </Card>
      </div>
    </div>
  );
};
