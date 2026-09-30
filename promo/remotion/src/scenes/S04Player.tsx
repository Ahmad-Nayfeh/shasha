import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {Icon, IconName} from '../components/Icons';
import {fr, Screen, TvFrame} from '../components/Tv';
import {Card, FocusRing, Header} from '../components/ui';
import {clamp, easeInOut, keys, mix, sp, tween} from '../lib/anim';
import {FEATURE_TV} from '../lib/layout';
import {beatsFor, Scene} from '../lib/timeline';

// s04 — player: prefers 1080p, speed & subtitles, resume; SponsorBlock skips sponsor segments.

const TV = FEATURE_TV;

const SettingRow: React.FC<{icon: IconName; label: string; value: string; on: number; seen: number; t: number}> = ({icon, label, value, on, seen, t}) => (
  <div
    style={{
      display: 'flex',
      alignItems: 'center',
      gap: 22,
      width: 470,
      height: 104,
      padding: '0 26px',
      boxSizing: 'border-box',
      borderRadius: 26,
      background: `linear-gradient(180deg, ${mix(0, 1, on) > 0.5 ? '#16252A' : '#161D26'}, #121820)`,
      border: `2px solid ${rgba(brand.primary, 0.12 + on * 0.75)}`,
      boxShadow: on > 0 ? `0 0 ${30 * on}px ${rgba(brand.primary, 0.3 * on)}` : '0 18px 40px rgba(0,0,0,0.3)',
      opacity: clamp(t * 1.4) * mix(0.55, 1, Math.max(on, seen * 0.7)),
      transform: `translateX(${(1 - t) * -40}px) scale(${1 + on * 0.03})`,
      direction: 'rtl',
    }}
  >
    <div style={{width: 60, height: 60, borderRadius: 18, background: rgba(brand.primary, 0.14), display: 'grid', placeItems: 'center'}}>
      <Icon name={icon} size={36} color={brand.primary} stroke={2.2} />
    </div>
    <div style={{fontSize: 32, fontWeight: 600, color: brand.muted, flex: 1}}>{label}</div>
    <div style={{fontSize: 36, fontWeight: 700, color: on > 0.5 ? brand.primaryBright : brand.text, direction: 'ltr'}}>{value}</div>
  </div>
);

export const S04Player: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tQ = b.at('الجودة') - 4;
  const tSpeed = b.at('والسرعة') - 6;
  const tSubs = b.at('والترجمة') - 2;
  const tResume = b.at('ويكمل') - 4;
  const tSB = b.at('وفقرات') - 6;
  const tSkip = b.at('يتخطاها');

  const tvIn = 1; // continuity: same TV as the previous scene (pure cross-fade, no entrance)

  // screens: overlay → quality menu → overlay → resume → sponsor-skipped
  const qMenuIn = tween(f, tQ + 14, 10);
  const qMenuOut = tween(f, tSpeed - 14, 10);
  const resumeIn = tween(f, tResume, 12);
  const skippedIn = tween(f, tSkip + 22, 10);

  // SponsorBlock timeline (LTR, like the player's seek bar)
  const sbIn = sp(f, tSB, {damping: 22});
  const rowsOut = tween(f, tSB - 8, 12);
  const segA = 0.44;
  const segB = 0.63;
  const jumpT = tween(f, tSkip + 4, 18, easeInOut);
  const head = f < tSkip + 4 ? mix(0.18, segA, tween(f, tSB + 6, tSkip + 4 - (tSB + 6), (t) => t)) : mix(segA, segB, jumpT) + tween(f, tSkip + 22, 60, (t) => t) * 0.12;
  const arcY = -Math.sin(jumpT * Math.PI) * 70 * (jumpT > 0 && jumpT < 1 ? 1 : 0);

  const rows: Array<{icon: IconName; label: string; value: string; at: number; next: number}> = [
    {icon: 'sliders', label: 'الجودة', value: '1080p ✓', at: tQ, next: tSpeed},
    {icon: 'gauge', label: 'السرعة', value: '0.5–2x', at: tSpeed, next: tSubs},
    {icon: 'subtitles', label: 'الترجمة', value: 'العربية', at: tSubs, next: tResume},
    {icon: 'refresh', label: 'يكمل من', value: '12:34', at: tResume, next: tSB},
  ];
  const BAR_W = 420;

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Header kicker="جولة في الميزات" index="٢ / ٨" title="المشغّل وSponsorBlock" />

      <TvFrame
        x={TV.x + (1 - tvIn) * -60}
        y={TV.y}
        w={TV.w}
        opacity={clamp(tvIn * 1.4)}
        camera={{
          s: keys(f, [[tQ + 12, 1], [tQ + 32, 1.35], [tSpeed - 16, 1.35], [tSpeed - 2, 1]], easeInOut),
          cx: (fr('player_settings', 'panel')[0] + fr('player_settings', 'panel')[2] / 2) / 1920,
          cy: (fr('player_settings', 'panel')[1] + fr('player_settings', 'panel')[3] / 2) / 1080,
        }}
      >
        <Screen name="player_overlay" />
        <Screen name="player_settings" opacity={qMenuIn * (1 - qMenuOut)} />
        <Screen name="player_resume" opacity={resumeIn} />
        <Screen name="player_sponsor_skipped" opacity={skippedIn} />
        <FocusRing
          track={[
            {f: tQ, rect: fr('player_overlay', 'quality'), r: 30},
            {f: tQ + 16, rect: fr('player_settings', 'q1080'), r: 25},
            {f: tSpeed - 10, rect: fr('player_overlay', 'speed'), r: 30},
            {f: tSubs, rect: fr('player_overlay', 'subtitles'), r: 30},
          ]}
          presses={[tQ + 12]}
          until={tResume + 2}
          width={6}
        />
        <FocusRing track={[{f: tResume + 14, rect: fr('player_resume', 'resume'), r: 36}]} until={tSB + 4} width={6} />
        <FocusRing track={[{f: tSkip + 26, rect: fr('player_sponsor_skipped', 'toast'), r: 58}]} width={5} />
      </TvFrame>

      {/* settings rows, lit in sync with the focus ring */}
      <div
        style={{
          position: 'absolute',
          right: 90,
          top: 262,
          display: 'flex',
          flexDirection: 'column',
          gap: 22,
          opacity: 1 - rowsOut,
          transform: `translateX(${rowsOut * 40}px)`,
        }}
      >
        {rows.map((r, i) => {
          const on = tween(f, r.at, 10) * (1 - tween(f, r.next - 4, 10));
          const seen = tween(f, r.at, 10);
          return <SettingRow key={r.label} icon={r.icon} label={r.label} value={r.value} on={on} seen={seen} t={sp(f, 8 + i * 5, {damping: 22})} />;
        })}
      </div>

      {/* SponsorBlock card */}
      <div
        style={{
          position: 'absolute',
          right: 90,
          top: 300,
          opacity: clamp(sbIn * 1.4),
          transform: `translateX(${(1 - sbIn) * -50}px)`,
        }}
      >
        <Card style={{width: 500, padding: '34px 40px 40px', boxSizing: 'border-box'}}>
          <div style={{display: 'flex', alignItems: 'center', gap: 16}}>
            <div style={{width: 56, height: 56, borderRadius: 16, background: rgba(brand.primary, 0.14), display: 'grid', placeItems: 'center'}}>
              <Icon name="skip" size={32} color={brand.primary} stroke={2.4} />
            </div>
            <div style={{fontSize: 36, fontWeight: 700}}>SponsorBlock</div>
          </div>
          <div style={{fontSize: 27, color: brand.muted, marginTop: 14, lineHeight: 1.5}}>يتخطى فقرات الرعاية تلقائياً</div>
          {/* timeline */}
          <div style={{position: 'relative', height: 132, marginTop: 6, direction: 'ltr'}}>
            <div style={{position: 'absolute', left: 0, top: 62, width: BAR_W, height: 12, borderRadius: 6, background: 'rgba(234,240,246,0.14)'}} />
            <div style={{position: 'absolute', left: 0, top: 62, width: BAR_W * head, height: 12, borderRadius: 6, background: brand.primary}} />
            <div
              style={{
                position: 'absolute',
                left: BAR_W * segA,
                top: 58,
                width: BAR_W * (segB - segA),
                height: 20,
                borderRadius: 6,
                background: rgba(brand.amber, 0.85),
                boxShadow: `0 0 18px ${rgba(brand.amber, 0.45)}`,
              }}
            />
            <div
              style={{
                position: 'absolute',
                left: BAR_W * segA,
                width: BAR_W * (segB - segA),
                top: 90,
                textAlign: 'center',
                fontSize: 24,
                fontWeight: 600,
                color: brand.amber,
                direction: 'rtl',
              }}
            >
              رعاية
            </div>
            {/* jump arc */}
            {jumpT > 0 ? (
              <svg width={BAR_W} height={100} style={{position: 'absolute', left: 0, top: 0, overflow: 'visible'}}>
                <path
                  d={`M${BAR_W * segA} 64 Q${BAR_W * (segA + segB) * 0.5} -26 ${BAR_W * segB} 64`}
                  stroke={brand.primaryBright}
                  strokeWidth={3}
                  strokeDasharray="6 8"
                  fill="none"
                  opacity={0.8 * (1 - tween(f, tSkip + 40, 20))}
                  pathLength={1}
                  style={{strokeDasharray: `${jumpT} 1`}}
                />
              </svg>
            ) : null}
            <div
              style={{
                position: 'absolute',
                left: BAR_W * head - 15,
                top: 53 + arcY,
                width: 30,
                height: 30,
                borderRadius: 999,
                background: '#fff',
                boxShadow: `0 0 0 6px ${rgba(brand.primary, 0.35)}`,
              }}
            />
          </div>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 12,
              fontSize: 28,
              fontWeight: 600,
              color: brand.primaryBright,
              opacity: tween(f, tSkip + 18, 12),
              transform: `translateY(${(1 - tween(f, tSkip + 18, 12)) * 8}px)`,
            }}
          >
            <Icon name="check" size={30} color={brand.primaryBright} stroke={3} />
            تم تخطي: إعلان مموَّل
          </div>
        </Card>
        <div style={{fontSize: 20, color: brand.faint, marginTop: 16, textAlign: 'right'}}>بيانات التخطي: SponsorBlock — CC BY-NC-SA</div>
      </div>
    </div>
  );
};

