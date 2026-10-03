import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {Icon} from '../components/Icons';
import {PhonePinField, PhoneQuotaValue} from '../components/Overlays';
import {
  PhoneCameraPage,
  PhoneFrame,
  PhoneParentPage,
  PhoneParentPinPage,
  PhoneRemotePage,
  PhoneScreen,
  pr,
  Tap,
} from '../components/Phone';
import {fr, Screen, TvFrame} from '../components/Tv';
import {Chip, FocusRing, Header} from '../components/ui';
import {clamp, easeInOut, keys, mix, sp, tween} from '../lib/anim';
import {FEATURE_TV, rectToStage, tvH} from '../lib/layout';
import {focusRect, hasScreen} from '../lib/screens';
import {beatsFor, Scene} from '../lib/timeline';

// s09 — the phone as a remote: scan the QR; search, control, send links; the parent section (separate
// PIN): adjust the quotas, unlock the time-up screen, manage the child's list. The phone shows the real
// :remote page captures (search → now playing → link sent → parent PIN → time → quotas → allow-list); taps
// land on their real buttons.

const PS = 0.74;
const PX = 1452;
const PY = 262;
const toStageP = (x: number, y: number): [number, number] => [PX + (13 + x) * PS, PY + (13 + y) * PS];

/** Centre of a manifest rect of a phone capture, in phone CSS px (390x844). */
const tapOf = (name: string, key: string): [number, number] => {
  const r = pr(name, key);
  return [r[0] + r[2] / 2, r[1] + r[3] / 2];
};

export const S09Phone: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  // the TV makes room for the phone + the verbs under it, and gives it back before the next scene
  const D = scene.duration_frames;
  const w = keys(f, [[12, FEATURE_TV.w], [42, 968], [D - 30, 968], [D - 4, FEATURE_TV.w]], easeInOut);
  const TV = {x: FEATURE_TV.x, y: FEATURE_TV.y, w};
  const tScan = b.at('امسح');
  const tSearch = b.at('لتبحث');
  const tControl = b.at('وتتحكم');
  const tSend = b.at('وترسل');
  const tParent = b.at('وفي قسم الأب');
  const tSep = b.at('برمز الوالدين');
  const tQuota = b.at('تضيف وقتا');
  const tUnlock = b.at('تضيف وقتا') + 16; // adding time also opens the time-up screen on the TV
  const tList = b.at('وتدير');

  const tvIn = 1; // continuity: same TV as the previous scene (pure cross-fade, no entrance)
  const phoneIn = sp(f, 8, {damping: 20, stiffness: 70});

  // scanning the TV's QR, then the page opens on the phone (search results)
  const scan = tween(f, tScan - 6, 26, easeInOut);
  const lockAt = tScan + 24;
  const lock = tween(f, lockAt, 10);
  const toApp = tween(f, lockAt + 12, 10);

  // search: tap the first result → it flies to the TV, which plays it
  const tapResult = tSearch + 6;
  const fly = tween(f, tapResult + 2, 22, easeInOut);
  const tvPlayer = tween(f, tapResult + 20, 8);
  // control: the "now on screen" page, tap pause
  const toMain = tween(f, tControl - 2, 8);
  const tapPause = tControl + 10;
  // send a link: pasted into the field, tap «أرسل» → the link flies to the TV → "now on screen" toast
  const pasted = f >= tSend + 2;
  const tapSend = tSend + 12;
  const fly2 = tween(f, tapSend + 2, 20, easeInOut);
  const toSent = tween(f, tapSend + 6, 8);
  const tvFlash = Math.sin(clamp((f - (tapSend + 22)) / 18) * Math.PI);

  // parent section: tab → PIN (separate) → time page → quotas (scrolled) → back up: unlock → allow-list tab
  const tapParentTab = tParent - 5;
  const toPin = tween(f, tParent + 1, 8);
  const pinTyped = clamp((f - (tParent + 14)) / 6, 0, 4);
  const tapEnter = tParent + 14 + 24 + 3; // after the 4th digit
  const toParent = tween(f, tapEnter + 6, 8);
  const toQuotas = tween(f, tQuota - 10, 12, easeInOut);
  const tapPlus = tQuota + 8;
  const backUp = tween(f, tUnlock - 8, 12, easeInOut);
  const tapUnlock = tUnlock + 12;
  const tapAllowTab = tList + 2;
  const toAllow = tween(f, tList + 6, 8);
  const tapAdd = tList + 24;
  const tvTimeUp = tween(f, tParent - 4, 12);
  const tvKids = tween(f, tapUnlock + 6, 12);

  // flying card (phone result → TV)
  const hasSearch = hasScreen('phone_search');
  const resultTap = hasSearch ? tapOf('phone_search', 'firstResult') : tapOf('phone_remote_main', 'firstResult');
  const [sx, sy] = toStageP(resultTap[0], resultTap[1]);
  const tx = TV.x + TV.w / 2;
  const ty = TV.y + tvH(TV.w) / 2;
  const cardX = mix(sx, tx, fly);
  const cardY = mix(sy, ty, fly) - Math.sin(fly * Math.PI) * 120;
  const [lx, ly] = toStageP(...tapOf('phone_remote_main', 'send'));
  const linkX = mix(lx, tx, fly2);
  const linkY = mix(ly, ty, fly2) - Math.sin(fly2 * Math.PI) * 100;

  // QR beam during the scan
  const qrS = rectToStage(TV, focusRect('remote_qr', 'qr'));
  const beam = tween(f, tScan - 8, 10) * (1 - tween(f, lockAt + 6, 10));
  const [camX, camY] = toStageP(195, 30);

  const link = pr('phone_remote_main', 'link');

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker="الجوال" index="١ / ٣" title="الجوال ريموتاً وقسماً للأب" />

      <TvFrame x={TV.x + (1 - tvIn) * -60} y={TV.y} w={TV.w} opacity={clamp(tvIn * 1.4)} glowColor={tvTimeUp > 0.5 && tvKids < 0.5 ? brand.amber : brand.primary}>
        <Screen name="remote_qr" />
        <Screen name="home_adult" opacity={toApp * (1 - tvPlayer)} />
        <Screen name="player_overlay" opacity={tvPlayer * (1 - tvTimeUp)} />
        {/* the sent link starts on the TV */}
        <Screen name="player_loading" opacity={tween(f, tapSend + 20, 6) * (1 - tvTimeUp)} />
        <Screen name="time_up" opacity={tvTimeUp * (1 - tvKids)} />
        <Screen name="kids_home" opacity={tvKids} />
        <FocusRing track={[{f: tScan - 6, rect: fr('remote_qr', 'qr'), r: 40}]} until={lockAt + 14} width={6} />
        {/* pause pressed from the phone */}
        {f >= tapPause && f < tapPause + 26 ? (
          <div
            style={{
              position: 'absolute',
              left: 960 - 90,
              top: 540 - 90,
              width: 180,
              height: 180,
              borderRadius: 999,
              background: 'rgba(5,8,11,0.55)',
              display: 'grid',
              placeItems: 'center',
              opacity: Math.sin(clamp((f - tapPause) / 26) * Math.PI),
              transform: `scale(${mix(0.85, 1.05, clamp((f - tapPause) / 26))})`,
            }}
          >
            <Icon name="pause" size={90} color="#fff" />
          </div>
        ) : null}
        <div style={{position: 'absolute', inset: 0, boxShadow: `inset 0 0 0 12px ${brand.primary}`, opacity: tvFlash * 0.8}} />
        {/* new item in the child's list */}
        <div
          style={{
            position: 'absolute',
            left: fr('kids_home', 'grid')[0],
            top: fr('kids_home', 'grid')[1],
            width: fr('kids_home', 'grid')[2],
            height: fr('kids_home', 'grid')[3],
            borderRadius: 30,
            boxShadow: `0 0 0 5px ${rgba(brand.kids, 0.7)}, 0 0 60px ${rgba(brand.kids, 0.4)}`,
            opacity: Math.sin(clamp((f - (tapAdd + 6)) / 26) * Math.PI) * 0.9,
          }}
        />
      </TvFrame>

      {/* scan beam */}
      {beam > 0 ? (
        <svg width={1920} height={1080} style={{position: 'absolute', inset: 0, pointerEvents: 'none'}}>
          <defs>
            <linearGradient id="beamG" x1="1" y1="0" x2="0" y2="0">
              <stop offset="0" stopColor={brand.primary} stopOpacity={0.35} />
              <stop offset="1" stopColor={brand.primary} stopOpacity={0.02} />
            </linearGradient>
          </defs>
          <polygon points={`${camX},${camY} ${qrS[0]},${qrS[1]} ${qrS[0]},${qrS[1] + qrS[3]}`} fill="url(#beamG)" opacity={beam} />
        </svg>
      ) : null}

      <PhoneFrame x={PX} y={PY + (1 - phoneIn) * 700} scale={PS} opacity={clamp(phoneIn * 2)} rotate={mix(8, 0, phoneIn)}>
        <PhoneCameraPage scan={scan} lock={lock} f={f} />
        {hasSearch ? <PhoneScreen name="phone_search" opacity={toApp * (1 - toMain)}>{null}</PhoneScreen> : null}
        <PhoneScreen name="phone_remote_main" opacity={(hasSearch ? toMain : toApp) * (1 - toSent)}>
          <PhoneRemotePage typed={99} results={1} link={pasted ? 1 : 0} />
        </PhoneScreen>
        {/* the link pasted into «الصق رابط يوتيوب هنا» */}
        {pasted && hasScreen('phone_remote_main') && toSent < 1 ? (
          <div
            style={{
              position: 'absolute',
              left: link[0] + 6,
              top: link[1] + 6,
              width: link[2] - 12,
              height: link[3] - 12,
              borderRadius: 10,
              background: '#1E2632',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'flex-end',
              padding: '0 12px',
              boxSizing: 'border-box',
              fontFamily: brand.mono,
              fontSize: 13,
              color: '#BFEFEA',
              direction: 'ltr',
              opacity: (1 - toSent) * tween(f, tSend + 2, 4),
            }}
          >
            https://youtu.be/…
          </div>
        ) : null}
        {hasScreen('phone_send_link') ? <PhoneScreen name="phone_send_link" opacity={toSent * (1 - toPin)}>{null}</PhoneScreen> : null}
        <div style={{position: 'absolute', inset: 0, opacity: toPin * (1 - toParent)}}>
          <PhoneScreen name="phone_parent_pin">
            <PhoneParentPinPage filled={pinTyped} />
          </PhoneScreen>
          <PhonePinField n={pinTyped} />
        </div>
        <div style={{position: 'absolute', inset: 0, opacity: toParent * (1 - toAllow)}}>
          <PhoneScreen name="phone_parent" opacity={1 - (hasScreen('phone_parent_quotas') ? toQuotas * (1 - backUp) : 0)}>
            <PhoneParentPage minutes={45} unlocked={tween(f, tapUnlock + 4, 6)} toggled={0} />
          </PhoneScreen>
          {hasScreen('phone_parent_quotas') ? (
            <div style={{position: 'absolute', inset: 0, opacity: toQuotas * (1 - backUp)}}>
              <PhoneScreen name="phone_parent_quotas">{null}</PhoneScreen>
              <PhoneQuotaValue minutes={105} t={tween(f, tapPlus + 3, 6)} />
            </div>
          ) : null}
        </div>
        {hasScreen('phone_parent_allowlist') ? <PhoneScreen name="phone_parent_allowlist" opacity={toAllow}>{null}</PhoneScreen> : null}
        <Tap f={f} at={tapResult} x={resultTap[0]} y={resultTap[1]} />
        <Tap f={f} at={tapPause} x={tapOf('phone_remote_main', 'play')[0]} y={tapOf('phone_remote_main', 'play')[1]} />
        <Tap f={f} at={tapSend} x={tapOf('phone_remote_main', 'send')[0]} y={tapOf('phone_remote_main', 'send')[1]} />
        <Tap f={f} at={tapParentTab} x={tapOf('phone_remote_main', 'parentTab')[0]} y={tapOf('phone_remote_main', 'parentTab')[1]} />
        <Tap f={f} at={tapEnter} x={tapOf('phone_parent_pin', 'enter')[0]} y={tapOf('phone_parent_pin', 'enter')[1]} />
        <Tap f={f} at={tapPlus} x={tapOf('phone_parent_quotas', 'childPlus')[0]} y={tapOf('phone_parent_quotas', 'childPlus')[1]} />
        <Tap f={f} at={tapUnlock} x={tapOf('phone_parent', 'plus15')[0]} y={tapOf('phone_parent', 'plus15')[1]} />
        <Tap f={f} at={tapAllowTab} x={tapOf('phone_parent', 'allowTab')[0]} y={tapOf('phone_parent', 'allowTab')[1]} />
        <Tap f={f} at={tapAdd} x={tapOf('phone_parent_allowlist', 'add')[0]} y={tapOf('phone_parent_allowlist', 'add')[1]} />
      </PhoneFrame>

      {/* result card flying to the TV */}
      {fly > 0 && fly < 1 ? (
        <div
          style={{
            position: 'absolute',
            left: cardX - 90 * mix(1, 1.6, fly),
            top: cardY - 50 * mix(1, 1.6, fly),
            width: 180 * mix(1, 1.6, fly),
            height: 101 * mix(1, 1.6, fly),
            borderRadius: 14,
            background: 'radial-gradient(70% 90% at 40% 40%, #1F5A70, #081620)',
            boxShadow: `0 20px 50px rgba(0,0,0,0.5), 0 0 0 3px ${brand.primary}`,
            opacity: Math.sin(fly * Math.PI) * 1.4,
            display: 'grid',
            placeItems: 'center',
          }}
        >
          <Icon name="play" size={40} color="#fff" />
        </div>
      ) : null}
      {/* link pill flying to the TV */}
      {fly2 > 0 && fly2 < 1 ? (
        <div
          style={{
            position: 'absolute',
            left: linkX - 130,
            top: linkY - 26,
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '12px 20px',
            borderRadius: 999,
            background: brand.primary,
            color: brand.onPrimary,
            fontFamily: brand.mono,
            fontSize: 22,
            fontWeight: 600,
            direction: 'ltr',
            opacity: Math.min(1, Math.sin(fly2 * Math.PI) * 1.6),
            boxShadow: `0 16px 40px ${rgba(brand.primary, 0.35)}`,
          }}
        >
          <Icon name="link" size={26} color={brand.onPrimary} stroke={2.4} />
          youtu.be/…
        </div>
      ) : null}

      {/* verbs */}
      <div style={{position: 'absolute', left: TV.x, top: TV.y + tvH(TV.w) + 76, display: 'flex', gap: 16, direction: 'rtl', opacity: 1 - tween(f, tParent - 6, 10)}}>
        <Chip icon="search" label="ابحث" size={24} t={sp(f, tSearch - 2, {damping: 20})} />
        <Chip icon="play" label="تحكّم" size={24} t={sp(f, tControl - 2, {damping: 20})} />
        <Chip icon="link" label="أرسل رابطاً" size={24} t={sp(f, tSend - 2, {damping: 20})} />
      </div>
      <div style={{position: 'absolute', left: TV.x, top: TV.y + tvH(TV.w) + 76, display: 'flex', gap: 16, direction: 'rtl', opacity: 1 - tween(f, D - 24, 14)}}>
        <Chip icon="shield" label="قسم الأب · رمز منفصل" tone="amber" size={24} t={sp(f, tSep - 2, {damping: 20})} />
        <Chip icon="plus" label="الحصص" tone="amber" size={24} t={sp(f, tapPlus, {damping: 20})} />
        <Chip icon="unlock" label="فتح انتهاء الوقت" tone="amber" size={24} t={sp(f, tapUnlock, {damping: 20})} />
        <Chip icon="star" label="قائمة الطفل" tone="kids" size={24} t={sp(f, tapAdd - 8, {damping: 20})} />
      </div>
    </div>
  );
};
