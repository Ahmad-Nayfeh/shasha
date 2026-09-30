import React from 'react';
import {Img, staticFile, useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {Icon} from '../components/Icons';
import {NoteChip, StepHeader} from '../components/Install';
import {PhoneCameraPage, PhoneFrame, PhoneRemotePage, PhoneScreen, PH, PW, Tap} from '../components/Phone';
import {Crumbs, sx, sy, TV} from '../components/TvStep';
import {fr, Screen, TvFrame} from '../components/Tv';
import {FocusRing} from '../components/ui';
import {clamp, easeInOut, mix, sp, tween} from '../lib/anim';
import {beatsFor, Scene} from '../lib/timeline';

// Step 9 (phone, optional): Shasha's settings → «التحكم من الجوال» → scan the QR with the phone camera (same Wi-Fi)
// → add the page to the phone's home screen.
const PX = 1452;
const PY = 262;
const PS = 0.74;

export const S24Phone: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tSettings = b.at('من إعدادات');
  const tRemote = b.at('افتح التحكم');
  const tScan = b.at('وامسح');
  const tNet = b.at('وهو على');
  const tAdd = b.at('ثم أضف');
  const D = scene.duration_frames;

  const qrIn = tween(f, tRemote - 2, 12);
  const phoneIn = sp(f, tScan - 16, {damping: 20, stiffness: 80});
  const scan = tween(f, tScan - 4, 22, easeInOut);
  const lockAt = tScan + 20;
  const lock = tween(f, lockAt, 8);
  const toPage = tween(f, lockAt + 30, 10);
  const sheet = tween(f, tAdd + 2, 10);
  const tapAdd = tAdd + 24;
  const home = tween(f, tapAdd + 8, 12);
  return (
    <div style={{position: 'absolute', inset: 0}}>
      <StepHeader step={9} />
      <TvFrame x={TV.x} y={TV.y} w={TV.w}>
        <Screen name="home_adult" />
        <Screen name="remote_qr" opacity={qrIn} />
        <FocusRing track={[{f: tScan - 4, rect: fr('remote_qr', 'qr'), r: 40}]} until={lockAt + 12} width={6} />
      </TvFrame>
      <Crumbs
        items={[
          {ar: 'إعدادات «شاشة»', en: 'الإعدادات', at: tSettings - 2},
          {ar: 'التحكم من الجوال', en: 'رمز QR للاقتران', at: tRemote},
        ]}
        x={sx(60)}
        y={sy(40)}
        out={tScan - 8}
      />
      <PhoneFrame x={PX} y={PY + (1 - phoneIn) * 700} scale={PS} opacity={clamp(phoneIn * 2)} rotate={mix(8, 0, phoneIn)}>
        <PhoneCameraPage scan={scan} lock={lock} f={f} />
        <PhoneScreen name="phone_remote_main" opacity={toPage * (1 - home)}>
          <PhoneRemotePage />
        </PhoneScreen>
        {/* «Add to Home Screen» sheet (phone browser menu), then the icon on the home screen */}
        {sheet > 0 && home < 1 ? (
          <div style={{position: 'absolute', left: 0, right: 0, bottom: 0, height: 330, transform: `translateY(${(1 - sheet) * 330}px)`, background: '#1C232D', borderRadius: '26px 26px 0 0', boxShadow: '0 -20px 50px rgba(0,0,0,0.5)', direction: 'rtl', fontFamily: brand.font, padding: '26px 22px', boxSizing: 'border-box'}}>
            {['نسخ الرابط', 'إضافة إلى الشاشة الرئيسية', 'إضافة إشارة مرجعية'].map((t, i) => (
              <div key={t} style={{height: 70, display: 'flex', alignItems: 'center', gap: 14, padding: '0 16px', borderRadius: 16, fontSize: 21, fontWeight: 600, color: brand.text, background: i === 1 ? rgba(brand.primary, 0.18) : 'transparent', border: i === 1 ? `2px solid ${brand.primary}` : '2px solid transparent', marginBottom: 12}}>
                <Icon name={i === 1 ? 'plus' : i === 0 ? 'link' : 'star'} size={26} color={i === 1 ? brand.primary : brand.muted} />
                {t}
              </div>
            ))}
          </div>
        ) : null}
        {home > 0 ? (
          <div style={{position: 'absolute', inset: 0, opacity: home, background: 'radial-gradient(120% 80% at 50% 20%, #25324A, #0D1119)'}}>
            {Array.from({length: 12}).map((_, i) => (
              <div key={i} style={{position: 'absolute', left: 34 + (i % 4) * 84, top: 120 + Math.floor(i / 4) * 110, width: 62, height: 62, borderRadius: 16, background: ['#3E7BFA', '#35B37E', '#F2A93B', '#8E6CF0', '#E85D9A', '#2BB3C0'][i % 6], opacity: 0.55}} />
            ))}
            <div style={{position: 'absolute', left: 34 + 84, top: 120 + 3 * 110, width: 62, textAlign: 'center', transform: `scale(${0.8 + 0.2 * sp(f, tapAdd + 10, {damping: 14})})`}}>
              <Img src={staticFile('branding/app-icon.svg')} style={{width: 62, height: 62, borderRadius: 16, boxShadow: `0 0 0 3px ${brand.primary}, 0 0 24px ${rgba(brand.primary, 0.6)}`}} />
              <div style={{fontSize: 14, color: '#fff', marginTop: 6, fontFamily: brand.font, fontWeight: 600}}>شاشة</div>
            </div>
          </div>
        ) : null}
        <Tap f={f} at={tapAdd} x={PW / 2} y={PH - 330 + 26 + 82 + 35} />
      </PhoneFrame>
      <NoteChip x={1330} y={905} t={sp(f, tNet - 4, {damping: 20}) * (1 - tween(f, D - 14, 10))} icon="wifi" size={24}>
        الجوال على شبكة التلفاز نفسها
      </NoteChip>
    </div>
  );
};
