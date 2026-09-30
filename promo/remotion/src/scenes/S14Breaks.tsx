import React from 'react';
import {useCurrentFrame} from 'remotion';
import {arDigits, brand, rgba} from '../brand';
import {Icon} from '../components/Icons';
import {UpdateProgressOverlay} from '../components/Overlays';
import {fr, Screen, ScreenCamera, TvFrame} from '../components/Tv';
import {Card, Chip, CrossedOut, FocusRing, Header} from '../components/ui';
import {clamp, easeInOut, keys, mix, sp, tween} from '../lib/anim';
import {FEATURE_TV} from '../lib/layout';
import {APK_NAME, APK_SIZE, RELEASE_TAG} from '../lib/demo';
import {beatsFor, Scene} from '../lib/timeline';

// s14 — when YouTube breaks: it changes every few months; Shasha says so clearly (unified error screen with
// the library version and error name), "check for updates" fetches the latest GitHub release, and the
// fix steps live in MAINTENANCE.md.

const MONTHS = ['ينا', 'فبر', 'مار', 'أبر', 'ماي', 'يون', 'يول', 'أغس', 'سبت', 'أكت', 'نوف', 'ديس'];
const BREAKS = [1, 4, 5, 8]; // months with a break (illustrative)

export const S14Breaks: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  // continuity with s13 (TV 968 px wide), then back to the feature layout
  const TV = {x: FEATURE_TV.x, y: FEATURE_TV.y, w: keys(f, [[4, 968], [36, FEATURE_TV.w]], easeInOut)};
  const tMonths = b.at('كل بضعة');
  const tSilent = b.at('لن تتعطل');
  const tClear = b.at('بل تقول');
  const tMsg = b.at('يوتيوب غير');
  const tBtn = b.at('وزر');
  const tFetch = b.at('يجلب');
  const tGh = tFetch + 12;
  const tFix = scene.duration_frames + 1000; // (v1 narrated the maintenance doc; v2 does not)
  const tDoc = scene.duration_frames + 1000;

  const tvIn = 1;
  // playback stalls: calm desaturation + a tiny 2-frame jitter
  const stall = tween(f, 50, 30);
  const jitter = (f >= 64 && f < 66) || (f >= 92 && f < 94) ? 2 : 0;
  const errIn = tween(f, tSilent + 6, 12);
  const updIn = tween(f, tBtn + 34, 10); // «فحص التحديثات» pressed → the app finds the new release
  const tInstall = tFetch + 6; // «تنزيل وتثبيت» pressed
  const downIn = tween(f, tInstall + 6, 8);

  // camera inside the TV: details (version + error name), then the message, then back out
  const det = fr('error_youtube_changed', 'details');
  const ttl = fr('error_youtube_changed', 'title');
  // zoom 1: the details (library version + error name) with the message above; zoom 2: the message itself
  const cam = {
    s: keys(f, [[tClear - 6, 1], [tClear + 18, 1.6], [tMsg - 8, 1.6], [tMsg + 12, 1.65], [tBtn - 20, 1.65], [tBtn - 2, 1]], easeInOut),
    cx: keys(f, [[tClear - 6, 960], [tClear + 18, det[0] + det[2] / 2], [tMsg - 8, det[0] + det[2] / 2], [tMsg + 12, ttl[0] + ttl[2] / 2], [tBtn - 20, ttl[0] + ttl[2] / 2], [tBtn - 2, 960]], easeInOut),
    cy: keys(f, [[tClear - 6, 540], [tClear + 18, 712], [tMsg - 8, 712], [tMsg + 12, 470], [tBtn - 20, 470], [tBtn - 2, 540]], easeInOut),
  };

  const monthsIn = sp(f, tMonths - 30, {damping: 22});
  const monthsOut = tween(f, tSilent - 6, 12);
  const chipsOut = tween(f, tBtn - 6, 12);
  const relIn = sp(f, tFetch - 6, {damping: 22});
  const relOut = tween(f, tFix - 8, 12);
  const docIn = sp(f, tFix - 2, {damping: 22});
  const dl = tween(f, tInstall + 10, 56, easeInOut); // the download (TV bar + release card, in sync)

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker="الصيانة" title="عندما يتعطل يوتيوب" />

      <TvFrame x={TV.x + (1 - tvIn) * -60 + jitter} y={TV.y} w={TV.w} opacity={clamp(tvIn * 1.4)} glowColor={errIn > 0.5 && updIn < 0.5 ? brand.error : brand.primary} glow={mix(1, 0.7, errIn)}>
        <div style={{position: 'absolute', inset: 0, filter: `saturate(${1 - stall * 0.85}) brightness(${1 - stall * 0.25})`}}>
          <Screen name="player_overlay" />
        </div>
        {stall > 0 && errIn < 1 ? (
          <div style={{position: 'absolute', left: 960 - 60, top: 540 - 60, width: 120, height: 120, opacity: stall * (1 - errIn)}}>
            <svg width={120} height={120} viewBox="0 0 120 120">
              <circle cx={60} cy={60} r={46} stroke="rgba(234,240,246,0.2)" strokeWidth={10} fill="none" />
              <circle cx={60} cy={60} r={46} stroke="#EAF0F6" strokeWidth={10} fill="none" strokeLinecap="round" strokeDasharray="70 300" transform={`rotate(${f * 9} 60 60)`} />
            </svg>
          </div>
        ) : null}
        <div style={{position: 'absolute', inset: 0, opacity: errIn}}>
          <ScreenCamera s={cam.s} cx={cam.cx} cy={cam.cy}>
            <Screen name="error_youtube_changed" />
            <FocusRing track={[{f: tClear + 14, rect: det, r: 32}, {f: tMsg + 8, rect: ttl, r: 20}]} until={tBtn - 16} color={brand.amber} width={5} />
            <FocusRing track={[{f: tBtn + 4, rect: fr('error_youtube_changed', 'updateButton'), r: 55}]} presses={[tBtn + 26]} until={tBtn + 36} width={6} />
          </ScreenCamera>
        </div>
        <Screen name="update_check" opacity={updIn * (1 - downIn)} />
        <div style={{position: 'absolute', inset: 0, opacity: downIn}}>
          <Screen name="update_downloading" />
          <UpdateProgressOverlay p={dl} />
        </div>
        <FocusRing track={[{f: tBtn + 40, rect: fr('update_check', 'install'), r: 55}]} presses={[tInstall]} until={tInstall + 8} width={6} />
      </TvFrame>

      {/* every few months */}
      <div style={{position: 'absolute', right: 90, top: 300, opacity: clamp(monthsIn * 1.4) * (1 - monthsOut), transform: `translateX(${(1 - monthsIn) * -40}px)`}}>
        <Card style={{width: 500, padding: '30px 34px', boxSizing: 'border-box'}}>
          <div style={{display: 'flex', alignItems: 'center', gap: 14}}>
            <Icon name="calendar" size={40} color={brand.amber} />
            <div style={{fontSize: 32, fontWeight: 700}}>كل بضعة أشهر</div>
          </div>
          <div style={{fontSize: 24, color: brand.muted, marginTop: 8}}>يوتيوب يغيّر طريقة التشغيل</div>
          <div style={{display: 'flex', gap: 6, marginTop: 26, direction: 'rtl'}}>
            {MONTHS.map((m, i) => {
              const isBreak = BREAKS.includes(i);
              const on = isBreak ? tween(f, tMonths - 8 + BREAKS.indexOf(i) * 6, 8) : 0;
              return (
                <div key={m} style={{display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, width: 30}}>
                  <div
                    style={{
                      width: 30,
                      height: 64,
                      borderRadius: 8,
                      background: on > 0 ? rgba(brand.amber, 0.2 + 0.6 * on) : 'rgba(234,240,246,0.08)',
                      boxShadow: on > 0.5 ? `0 0 16px ${rgba(brand.amber, 0.5)}` : undefined,
                      display: 'grid',
                      placeItems: 'center',
                    }}
                  >
                    {on > 0.5 ? <Icon name="warning" size={20} color={brand.onAmber} stroke={2.6} /> : null}
                  </div>
                  <div style={{fontSize: 15, color: brand.muted}}>{m}</div>
                </div>
              );
            })}
          </div>
        </Card>
      </div>

      {/* not silently — clearly */}
      <div style={{position: 'absolute', right: 90, top: 320, display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 22, opacity: 1 - chipsOut}}>
        <CrossedOut from={tSilent - 2} strikeAt={b.at('بصمت') + 6}>
          <div style={{display: 'flex', alignItems: 'center', gap: 14, padding: '14px 30px', borderRadius: 999, border: '1.5px solid rgba(234,240,246,0.24)', background: 'rgba(234,240,246,0.08)', fontSize: 34, fontWeight: 600}}>
            <Icon name="x" size={34} color={brand.text} />
            تعطّل صامت
          </div>
        </CrossedOut>
        <Chip icon="warning" label="رسالة واضحة" tone="amber" size={34} t={sp(f, tClear, {damping: 20})} />
        <div style={{opacity: tween(f, tClear + 18, 12), display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 10, marginTop: 8}}>
          <div style={{fontSize: 24, color: brand.muted}}>تعرض:</div>
          <div style={{fontSize: 28, fontWeight: 600}}>إصدار المكتبة · اسم الخطأ</div>
          <div style={{fontSize: 28, fontWeight: 600}}>وزر «فحص التحديثات»</div>
        </div>
      </div>

      {/* latest release */}
      <div style={{position: 'absolute', right: 90, top: 300, opacity: clamp(relIn * 1.4) * (1 - relOut), transform: `translateX(${(1 - relIn) * -50}px)`}}>
        <Card style={{width: 500, padding: '30px 34px', boxSizing: 'border-box'}} tone="teal">
          <div style={{display: 'flex', alignItems: 'center', gap: 14}}>
            <div style={{width: 56, height: 56, borderRadius: 16, background: rgba(brand.primary, 0.14), display: 'grid', placeItems: 'center'}}>
              <Icon name="tag" size={32} color={brand.primary} />
            </div>
            <div>
              <div style={{fontSize: 22, color: brand.muted, direction: 'ltr', textAlign: 'right'}}>GitHub Releases</div>
              <div style={{fontSize: 36, fontWeight: 700, fontFamily: brand.mono, direction: 'ltr', textAlign: 'right'}}>{RELEASE_TAG}</div>
            </div>
          </div>
          <div style={{display: 'flex', alignItems: 'center', gap: 12, marginTop: 22, padding: '14px 18px', borderRadius: 16, background: '#0F141A', direction: 'ltr'}}>
            <Icon name="apk" size={30} color={brand.text} />
            <div style={{fontFamily: brand.mono, fontSize: 19, flex: 1, whiteSpace: 'nowrap'}}>{APK_NAME}</div>
            <div style={{fontFamily: brand.mono, fontSize: 17, color: brand.muted, whiteSpace: 'nowrap'}}>{APK_SIZE}</div>
          </div>
          <div style={{height: 10, borderRadius: 6, background: 'rgba(234,240,246,0.1)', marginTop: 20, overflow: 'hidden', direction: 'rtl'}}>
            <div style={{width: `${dl * 100}%`, height: '100%', borderRadius: 6, background: brand.primary}} />
          </div>
          <div style={{fontSize: 22, color: dl >= 1 ? brand.primaryBright : brand.muted, marginTop: 10}}>{dl >= 1 ? 'جاهز للتثبيت ✓' : `جارٍ التنزيل… ${Math.round(dl * 100)}%`}</div>
        </Card>
      </div>

      {/* MAINTENANCE.md */}
      <div style={{position: 'absolute', right: 90, top: 290, opacity: clamp(docIn * 1.4), transform: `translateX(${(1 - docIn) * -50}px) rotate(${mix(-3, 0, docIn)}deg)`}}>
        <div
          style={{
            width: 480,
            borderRadius: 20,
            background: '#EDF1F5',
            color: '#0B0F14',
            boxShadow: '0 30px 70px rgba(0,0,0,0.5)',
            overflow: 'hidden',
          }}
        >
          <div style={{display: 'flex', alignItems: 'center', gap: 12, padding: '16px 22px', background: '#DDE4EB', direction: 'ltr'}}>
            <Icon name="doc" size={28} color="#0B0F14" />
            <div style={{fontFamily: brand.mono, fontSize: 24, fontWeight: 600}}>MAINTENANCE.md</div>
          </div>
          <div style={{padding: '22px 28px 28px', direction: 'rtl'}}>
            <div style={{fontSize: 30, fontWeight: 700, marginBottom: 14}}>عندما يتعطل يوتيوب</div>
            {['رفع إصدار المكتبة', 'البناء', 'النشر'].map((l, i) => {
              const t = tween(f, tDoc - 10 + i * 10, 10);
              return (
                <div key={l} style={{display: 'flex', alignItems: 'center', gap: 14, height: 54, opacity: t, transform: `translateX(${(1 - t) * -16}px)`}}>
                  <div style={{width: 36, height: 36, borderRadius: 99, background: '#0B0F14', color: '#EDF1F5', display: 'grid', placeItems: 'center', fontSize: 20, fontWeight: 700}}>{arDigits(i + 1)}</div>
                  <div style={{fontSize: 28, fontWeight: 600}}>{l}</div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
};
