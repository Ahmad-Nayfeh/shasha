import React from 'react';
import {useCurrentFrame} from 'remotion';
import {arDigits, brand, rgba} from '../brand';
import {Icon} from '../components/Icons';
import {Remote} from '../components/Remote';
import {HoldRingOverlay, PhraseOverlay} from '../components/Overlays';
import {fr, Screen, TvFrame} from '../components/Tv';
import {Card, Chip, FocusRing, FocusKey, Header} from '../components/ui';
import {clamp, sp, tween} from '../lib/anim';
import {FEATURE_TV} from '../lib/layout';
import {focusRects} from '../lib/screens';
import {beatsFor, Scene} from '../lib/timeline';

// s07 — the lock inside the app: Back does nothing in kids mode (the video stays; leaving needs the parents'
// PIN); in adult mode leaving early needs a 10-second hold and then typing a phrase shown on screen (logged in
// the stats). All on the real captures: the hold ring counts down and the phrase gets typed key by key.

const TV = FEATURE_TV;
const PHRASE = 'الصبر مفتاح الفرج'; // the capture's phrase
const PRE = 8; // «الصبر مف» is already typed in the capture
const HOP = 8; // frames per key

export const S07Lock: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tBack = b.at('زر الرجوع');
  const tAdult = b.at('وفي ملف الكبار');
  const tHold = b.at('ضغطا مطولا');
  const tTen = b.at('لعشر ثوان');
  const tType = b.at('ثم كتابة');

  const tvIn = 1; // continuity: same TV as the previous scene (pure cross-fade, no entrance)
  const deny = tBack + 6;
  const shake = f >= deny && f < deny + 8 ? Math.sin((f - deny) * 2.4) * 2 : 0;
  const adultIn = tween(f, tAdult + 2, 12);
  const holdFrom = tHold - 4;
  const holdTo = tTen + 24; // 10 s, time-compressed
  const holdScreen = tween(f, holdFrom - 2, 10);
  const phraseScreen = tween(f, tType - 6, 12);
  const holdP = clamp((f - holdFrom) / (holdTo - holdFrom));
  const count = Math.ceil(10 * (1 - holdP));

  // Back in kids mode: the video stays, the app answers with its "leaving needs the parents' PIN" prompt
  const promptIn = tween(f, deny + 2, 8);

  // typing the rest of the phrase on the on-screen keyboard: the ring hops key to key, each press adds a letter
  const typeFrom = tType + 4;
  const keysR = focusRects('lock_phrase', 'keys').map((r) => [r[0] * 1920, r[1] * 1080, r[2] * 1920, r[3] * 1080] as [number, number, number, number]);
  const keyTrack: FocusKey[] = keysR.map((rect, i) => ({f: typeFrom + i * HOP, rect, r: 26}));
  const keyPresses = keyTrack.map((k) => k.f + 5); // the ring has landed (snappy glide) before OK
  const n = PRE + keyPresses.filter((p) => f >= p).length;

  const kidTag = tween(f, 4, 14) * (1 - tween(f, tAdult - 4, 10));
  const adultTag = tween(f, tAdult + 4, 14);
  const holdCardIn = sp(f, holdFrom - 6, {damping: 22});
  const holdCardOut = tween(f, tType - 4, 12);
  const typeCardIn = sp(f, tType - 2, {damping: 22});

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker="جولة في الميزات" index="٥ / ٨" title="القفل داخل التطبيق" />

      <TvFrame x={TV.x + (1 - tvIn) * -60} y={TV.y} w={TV.w} opacity={clamp(tvIn * 1.4)} shakeX={shake} glowColor={adultIn > 0.5 ? brand.primary : brand.kids}>
        <Screen name="kids_video" />
        <Screen name="kids_player_locked" opacity={promptIn} />
        <Screen name="player_overlay" opacity={adultIn * (1 - holdScreen)} />
        <div style={{position: 'absolute', inset: 0, opacity: holdScreen * (1 - phraseScreen)}}>
          <Screen name="lock_hold" />
          <HoldRingOverlay p={holdP} seconds={10 * (1 - holdP)} />
        </div>
        <div style={{position: 'absolute', inset: 0, opacity: phraseScreen}}>
          <Screen name="lock_phrase" />
          <PhraseOverlay phrase={PHRASE} typed={n} blink={Math.floor(f / 8) % 2 === 0} />
        </div>
        <FocusRing track={[{f: deny + 8, rect: fr('kids_player_locked', 'lockHint'), r: 26}]} until={tAdult} color={brand.amber} width={5} />
        <FocusRing track={keyTrack} until={scene.duration_frames + 20} width={5} presses={keyPresses} glide={{frames: 4}} />
      </TvFrame>

      {/* mode tags, top-left of the TV */}
      <div style={{position: 'absolute', left: TV.x, top: TV.y - 74}}>
        <div style={{position: 'absolute', left: 0, top: 0, opacity: kidTag}}>
          <Chip icon="star" label="وضع الطفل" tone="kids" size={24} />
        </div>
        <div style={{position: 'absolute', left: 0, top: 0, opacity: adultTag}}>
          <Chip icon="user" label="وضع الكبار" tone="teal" size={24} />
        </div>
      </div>

      <Remote
        x={1600}
        y={300}
        scale={0.95}
        rotate={-8}
        opacity={clamp(tvIn) * (1 - tween(f, tType - 8, 12))}
        denies={[{btn: 'back', f: deny}]}
        holds={[{btn: 'back', from: holdFrom, to: holdTo}]}
      />
      {/* "Back does nothing" note next to the remote */}
      <div
        style={{
          position: 'absolute',
          right: 330,
          top: 600,
          opacity: tween(f, deny, 10) * (1 - tween(f, tAdult - 6, 10)),
          transform: `translateX(${(1 - tween(f, deny, 10)) * -20}px)`,
        }}
      >
        <Chip icon="back" label="لا يعمل" tone="amber" size={26} />
      </div>

      {/* 10-second hold counter */}
      <div
        style={{
          position: 'absolute',
          right: 400,
          top: 330,
          opacity: clamp(holdCardIn * 1.4) * (1 - holdCardOut),
          transform: `translateX(${(1 - holdCardIn) * -30}px)`,
        }}
      >
        <Card style={{width: 250, padding: '26px 20px 24px', boxSizing: 'border-box', textAlign: 'center'}} tone="teal" glow={holdP}>
          <div style={{width: 76, height: 76, margin: '0 auto', borderRadius: 999, background: rgba(brand.primary, 0.14), display: 'grid', placeItems: 'center'}}>
            <Icon name={holdP >= 1 ? 'check' : 'back'} size={42} color={brand.primaryBright} stroke={2.4} />
          </div>
          <div style={{fontSize: 30, fontWeight: 700, marginTop: 14}}>ضغط مطوّل</div>
          <div style={{fontSize: 26, color: brand.muted, marginTop: 2}}>١٠ ثوانٍ</div>
          <div style={{height: 8, borderRadius: 4, background: 'rgba(234,240,246,0.1)', marginTop: 16, overflow: 'hidden', direction: 'rtl'}}>
            <div style={{width: `${holdP * 100}%`, height: '100%', background: brand.primary, borderRadius: 4}} />
          </div>
        </Card>
      </div>

      {/* type the phrase */}
      <div
        style={{
          position: 'absolute',
          right: 90,
          top: 290,
          opacity: clamp(typeCardIn * 1.4),
          transform: `translateX(${(1 - typeCardIn) * -40}px)`,
        }}
      >
        <Card style={{width: 480, padding: '32px 36px', boxSizing: 'border-box'}}>
          <div style={{display: 'flex', alignItems: 'center', gap: 16}}>
            <div style={{width: 60, height: 60, borderRadius: 18, background: rgba(brand.primary, 0.14), display: 'grid', placeItems: 'center'}}>
              <Icon name="keyboard" size={36} color={brand.primary} stroke={2.2} />
            </div>
            <div>
              <div style={{fontSize: 24, color: brand.muted}}>الخطوة الأخيرة</div>
              <div style={{fontSize: 34, fontWeight: 700}}>اكتب الجملة الظاهرة</div>
            </div>
          </div>
          <div style={{height: 8, borderRadius: 4, background: 'rgba(234,240,246,0.1)', marginTop: 24, overflow: 'hidden', direction: 'rtl'}}>
            <div style={{width: `${((n - PRE) / (PHRASE.length - PRE)) * 100}%`, height: '100%', background: brand.primary, borderRadius: 4}} />
          </div>
          <div style={{display: 'flex', alignItems: 'center', gap: 12, marginTop: 22, fontSize: 24, color: brand.muted}}>
            <Icon name="barChart" size={28} color={brand.muted} />
            يُسجَّل في الإحصائيات
          </div>
        </Card>
      </div>
    </div>
  );
};
