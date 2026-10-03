import React from 'react';
import {useCurrentFrame} from 'remotion';
import {arDigits, brand, rgba} from '../brand';
import {Icon} from '../components/Icons';
import {PhoneFrame, PhoneScreen, pr, Tap} from '../components/Phone';
import {Screen, TvFrame} from '../components/Tv';
import {Chip, FocusRing, Header} from '../components/ui';
import {clamp, easeInOut, mix, sp, tween} from '../lib/anim';
import {FEATURE_TV} from '../lib/layout';
import {beatsFor, Scene} from '../lib/timeline';

// s13 — what the phone sends, the TV asks about: «قبول» or «رفض». What is playing now goes on until you accept;
// an unanswered request is refused by itself. The bar is drawn like the app's overlay (cast_request_overlay).

const TV = FEATURE_TV;
const PS = 0.74;
const PX = 1452;
const PY = 262;

/** The request bar (screen px 1920x1080, like the capture). */
const RequestBar: React.FC<{t: number; seconds: number; acceptGlow: number}> = ({t, seconds, acceptGlow}) => (
  <div
    style={{
      position: 'absolute',
      left: 200,
      top: 795,
      width: 1520,
      height: 220,
      borderRadius: 64,
      background: 'rgba(20,27,37,0.97)',
      border: '2px solid rgba(46,196,182,0.5)',
      boxShadow: '0 30px 80px rgba(0,0,0,0.6)',
      opacity: clamp(t * 1.6),
      transform: `translateY(${(1 - t) * 160}px)`,
      direction: 'rtl',
      fontFamily: brand.font,
    }}
  >
    <div style={{position: 'absolute', right: 34, top: 62, width: 96, height: 96, borderRadius: 999, background: 'rgba(46,196,182,0.16)', border: '2px solid rgba(46,196,182,0.55)', display: 'grid', placeItems: 'center'}}>
      <Icon name="phone" size={50} color={brand.primary} stroke={2.2} />
    </div>
    <div style={{position: 'absolute', right: 160, top: 34, textAlign: 'right'}}>
      <div style={{fontSize: 36, fontWeight: 600, color: brand.primary}}>طلب تشغيل من الجوال:</div>
      <div style={{fontSize: 52, fontWeight: 700, color: brand.text, marginTop: 4}}>«حقائق مذهلة عن الثقوب السوداء»</div>
      <div style={{fontSize: 33, fontWeight: 500, color: brand.muted, marginTop: 8}}>
        يستمر ما يُعرض الآن حتى تقبل. · يُرفض وحده بعد {arDigits(seconds)} ثانية
      </div>
    </div>
    <div
      style={{
        position: 'absolute',
        left: 286,
        top: 56,
        width: 244,
        height: 110,
        borderRadius: 999,
        background: brand.primary,
        color: brand.onPrimary,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 18,
        fontSize: 44,
        fontWeight: 700,
        boxShadow: `0 0 ${40 * acceptGlow}px ${rgba(brand.primary, 0.55 * acceptGlow)}`,
      }}
    >
      <Icon name="play" size={40} color={brand.onPrimary} stroke={2.4} />
      قبول
    </div>
    <div
      style={{
        position: 'absolute',
        left: 39,
        top: 58,
        width: 233,
        height: 105,
        borderRadius: 999,
        background: '#222A35',
        border: '1.5px solid rgba(234,240,246,0.14)',
        color: brand.text,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 18,
        fontSize: 44,
        fontWeight: 600,
      }}
    >
      <Icon name="x" size={40} color={brand.text} stroke={2.4} />
      رفض
    </div>
  </div>
);

export const S13Accept: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tSend = b.at('أرسل');
  const tAsk = b.at('سألك');
  const tAccept = b.at('قبول');
  const tReject = b.at('رفض');
  const tKeep = b.at('ويستمر');

  const tvIn = sp(f, 0, {damping: 24, stiffness: 90});
  const phoneIn = sp(f, tSend - 14, {damping: 22});
  const tapSend = tSend + 10;
  const barIn = sp(f, tAsk - 2, {damping: 22});
  const seconds = Math.max(3, 24 - Math.floor(Math.max(0, f - tAsk) / 6));
  const glow = Math.max(0, Math.sin(clamp((f - tAccept) / 26) * Math.PI));

  // the link flying from the phone to the TV
  const fly = tween(f, tapSend + 4, 34, easeInOut);
  const bez = (a: number, c: number, e: number, t: number) => (1 - t) * (1 - t) * a + 2 * (1 - t) * t * c + t * t * e;
  const fx = bez(1606, 1250, 700, fly);
  const fy = bez(470, 330, 760, fly);

  // focus ring on the bar's buttons (screen px, as in the capture)
  const accept: [number, number, number, number] = [486, 851, 244, 110];
  const reject: [number, number, number, number] = [239, 853, 233, 105];
  const send = pr('phone_send_link', 'send');

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker="الجوال" index="٢ / ٣" title="قبول أو رفض" />

      <TvFrame x={TV.x + (1 - tvIn) * -60} y={TV.y} w={TV.w} opacity={clamp(tvIn * 1.4)}>
        <Screen name="player_overlay" />
        <RequestBar t={barIn} seconds={seconds} acceptGlow={glow} />
        <FocusRing
          track={[
            {f: tAccept - 2, rect: accept, r: 55},
            {f: tReject - 2, rect: reject, r: 52},
          ]}
          until={tKeep - 4}
          width={6}
          pad={10}
        />
      </TvFrame>

      {/* the phone that sends */}
      <PhoneFrame x={PX} y={PY + (1 - phoneIn) * 600} scale={PS} opacity={clamp(phoneIn * 2)} rotate={mix(8, 0, phoneIn)}>
        <PhoneScreen name="phone_send_link">{null}</PhoneScreen>
        <Tap f={f} at={tapSend} x={send[0] + send[2] / 2} y={send[1] + send[3] / 2} />
      </PhoneFrame>

      {fly > 0 && fly < 1 ? (
        <div style={{position: 'absolute', left: fx - 60, top: fy - 34, width: 120, height: 68, borderRadius: 14, background: 'linear-gradient(135deg, #3B2E5A, #1D2A44)', border: `2px solid ${brand.primary}`, display: 'grid', placeItems: 'center', opacity: Math.sin(fly * Math.PI) * 1.2, transform: `scale(${mix(1, 0.7, fly)})`}}>
          <Icon name="play" size={34} color={brand.text} stroke={2.2} />
        </div>
      ) : null}

      {/* what keeps going */}
      <div style={{position: 'absolute', left: TV.x, top: TV.y - 74, opacity: tween(f, tKeep - 4, 14), transform: `translateY(${(1 - tween(f, tKeep - 4, 14)) * 10}px)`}}>
        <Chip icon="play" label="يستمر ما تشاهده حتى تقرر" tone="teal" size={24} />
      </div>
    </div>
  );
};
