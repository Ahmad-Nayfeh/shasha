import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {LivingRoom, ROOM_TV} from '../components/LivingRoom';
import {Camera, camAt} from '../components/Tv';
import {CheckBadge, WordReveal} from '../components/ui';
import {clamp, easeIn, easeInOut, easeOut, mix, sp, tween} from '../lib/anim';
import {beatsFor, Scene} from '../lib/timeline';

// s02 — why: calm, intentional watching. Night living room; headline word by word; the chosen video;
// the TV switched off, satisfied; a brief dim contrast of endless scrolling that stops.

const THUMB = ['#1E5A63', '#3C3163', '#6B4721', '#1F3F66', '#2F5433', '#5E2748'];

const MiniGrid: React.FC<{f: number; lift: number; pick: number; expand: number}> = ({f, lift, pick, expand}) => {
  const W = ROOM_TV.w;
  const H = ROOM_TV.h;
  const cw = 140;
  const ch = 80;
  const gx = 22;
  const gy = 40;
  const x0 = W - 34 - cw;
  const y0 = 58;
  const chosen = {r: 0, c: 1};
  return (
    <div style={{position: 'absolute', inset: 0, background: 'radial-gradient(90% 90% at 70% 0%, #121A24, #0A0E13 70%)'}}>
      <div style={{position: 'absolute', right: 34, top: 18, width: 90, height: 12, borderRadius: 6, background: 'rgba(234,240,246,0.2)'}} />
      {[0, 1].map((r) =>
        [0, 1, 2].map((c) => {
          const isC = r === chosen.r && c === chosen.c;
          const x = x0 - c * (cw + gx);
          const y = y0 + r * (ch + gy);
          const dim = isC ? 1 : mix(0.7, 0.3, pick);
          const s = isC ? 1 + 0.1 * lift : 1;
          // expand the chosen card to fill the screen (it plays)
          const ex = isC ? expand : 0;
          const L = mix(x, 0, ex);
          const Tt = mix(y, 0, ex);
          const Wd = mix(cw, W, ex);
          const Hd = mix(ch, H, ex);
          return (
            <div
              key={`${r}${c}`}
              style={{
                position: 'absolute',
                left: L,
                top: Tt,
                width: Wd,
                height: Hd,
                borderRadius: mix(10, 0, ex),
                background: `radial-gradient(70% 90% at 35% 40%, ${THUMB[(r * 3 + c) % 6]}, #0A0F14 80%)`,
                opacity: dim,
                transform: `scale(${s})`,
                boxShadow: isC && lift > 0 ? `0 0 0 ${3 * lift * (1 - ex)}px ${brand.primary}, 0 0 ${24 * lift * (1 - ex)}px ${rgba(brand.primary, 0.6)}` : undefined,
                zIndex: isC ? 2 : 1,
                overflow: 'hidden',
              }}
            >
              {isC && ex > 0.6 ? (
                <div style={{position: 'absolute', left: 24, right: 24, bottom: 18, height: 5, borderRadius: 3, background: 'rgba(234,240,246,0.2)', opacity: (ex - 0.6) / 0.4, direction: 'ltr'}}>
                  <div style={{width: `${30 + (f % 400) * 0.08}%`, height: '100%', borderRadius: 3, background: brand.primary}} />
                </div>
              ) : null}
            </div>
          );
        }),
      )}
    </div>
  );
};

export const S02Why: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tWatch = b.at('تشاهد');
  const tChose = b.at('ما اخترته');
  const tOff = b.at('ثم تغلق');
  const tSat = b.at('راضيا');
  const tWaste = b.at('لا أن تضيع');
  const tStop = b.at('لم تبحث');

  const roomIn = tween(f, 0, 24);
  const cam = camAt(
    f,
    [
      {f: 0, s: 1, cx: 960, cy: 540},
      {f: tWatch - 10, s: 1, cx: 960, cy: 540},
      {f: tWatch + 30, s: 1.55, cx: 640, cy: 600},
      {f: tWaste - 6, s: 1.55, cx: 640, cy: 600},
      {f: scene.duration_frames, s: 1.4, cx: 700, cy: 590},
    ],
    easeInOut,
  );

  // TV power-off: collapse to a line, then to a dot
  const offY = tween(f, tOff + 10, 9, easeIn);
  const offX = tween(f, tOff + 19, 8, easeIn);
  const tvBright = f < tOff + 10 ? 1 : 1 - tween(f, tOff + 19, 10);
  const lift = sp(f, tWatch + 2, {damping: 20});
  const pick = tween(f, tChose - 4, 14);
  const expand = tween(f, tChose + 10, 20, easeInOut);
  const check = sp(f, tSat - 2, {damping: 16, stiffness: 140});

  // contrast column (endless feed + spinning clock), dim; stops at "لم تبحث عنه"
  const wIn = tween(f, tWaste - 4, 10);
  const stop = tween(f, tStop, 16, easeOut);
  const wOut = tween(f, tStop + 16, 18);
  const scrollBase = (tStop - (tWaste - 4)) * 22;
  const scroll = f < tStop ? (f - (tWaste - 4)) * 22 : scrollBase + (1 - Math.pow(1 - clamp(stop), 2)) * 60;

  const headline = ['مشاهدة', 'هادئة', 'ومقصودة'];
  return (
    <div style={{position: 'absolute', inset: 0}}>
      <Camera s={cam.s} cx={cam.cx} cy={cam.cy}>
        <LivingRoom
          opacity={roomIn}
          tv={tvBright}
          lamp={1}
          screenScaleY={mix(1, 0.012, offY)}
          screenScaleX={mix(1, 0.004, offX)}
          screen={
            <>
              <MiniGrid f={f} lift={lift} pick={pick} expand={expand} />
              {offY > 0 ? <div style={{position: 'absolute', inset: 0, background: '#EAF0F6', opacity: offY * 0.9}} /> : null}
            </>
          }
        />
        {/* satisfied check next to the switched-off TV */}
        <div style={{position: 'absolute', left: ROOM_TV.x + ROOM_TV.w / 2 - 34, top: ROOM_TV.y + ROOM_TV.h / 2 - 34}}>
          <CheckBadge t={check} size={68} />
        </div>
      </Camera>

      {/* headline */}
      <div style={{position: 'absolute', left: 0, right: 0, top: 118, textAlign: 'center'}}>
        <div
          style={{
            fontSize: 30,
            fontWeight: 600,
            color: brand.primary,
            opacity: tween(f, b.at('الفكرة') - 2, 14),
            transform: `translateY(${(1 - tween(f, b.at('الفكرة') - 2, 14)) * 10}px)`,
            marginBottom: 8,
          }}
        >
          الفكرة
        </div>
        <WordReveal
          words={headline}
          starts={[b.at('مشاهدة') - 2, b.at('هادئة') - 2, b.at('ومقصودة') - 2]}
          style={{fontSize: 92, fontWeight: 600, color: brand.text, lineHeight: 1.25}}
          wordStyle={(i) => (i === 2 ? {color: brand.primaryBright} : {})}
        />
      </div>

      {/* the endless feed we do not want */}
      {wIn > 0 && wOut < 1 ? (
        <div
          style={{
            position: 'absolute',
            left: 1440,
            top: 250,
            width: 330,
            height: 640,
            overflow: 'hidden',
            opacity: wIn * (1 - wOut) * 0.55,
            maskImage: 'linear-gradient(180deg, transparent, black 18%, black 82%, transparent)',
            WebkitMaskImage: 'linear-gradient(180deg, transparent, black 18%, black 82%, transparent)',
            filter: f < tStop ? 'blur(1.2px) grayscale(1)' : 'grayscale(1)',
          }}
        >
          {Array.from({length: 14}).map((_, i) => {
            const y = ((i * 128 - scroll) % (14 * 128) + 14 * 128) % (14 * 128) - 128;
            return (
              <div key={i} style={{position: 'absolute', left: 20, top: y, width: 290, height: 112, display: 'flex', gap: 14, direction: 'rtl'}}>
                <div style={{width: 176, height: 99, borderRadius: 10, background: `linear-gradient(135deg, #3A4452, #202833)`}} />
                <div style={{flex: 1, display: 'flex', flexDirection: 'column', gap: 10, paddingTop: 8}}>
                  <div style={{height: 12, borderRadius: 6, background: '#3A4452'}} />
                  <div style={{height: 12, width: '70%', borderRadius: 6, background: '#2A323D'}} />
                </div>
              </div>
            );
          })}
        </div>
      ) : null}
      {wIn > 0 && wOut < 1 ? (
        <div style={{position: 'absolute', left: 1560, top: 168, opacity: wIn * (1 - wOut) * 0.75}}>
          <svg width={90} height={90} viewBox="0 0 90 90">
            <circle cx={45} cy={45} r={38} fill="none" stroke={brand.muted} strokeWidth={4} />
            <line
              x1={45}
              y1={45}
              x2={45}
              y2={16}
              stroke={brand.amber}
              strokeWidth={4}
              strokeLinecap="round"
              transform={`rotate(${f < tStop ? (f - tWaste) * 26 : (tStop - tWaste) * 26 + stop * 30} 45 45)`}
            />
            <line x1={45} y1={45} x2={62} y2={45} stroke={brand.muted} strokeWidth={4} strokeLinecap="round" transform={`rotate(${(f - tWaste) * 2.2} 45 45)`} />
            <circle cx={45} cy={45} r={4} fill={brand.muted} />
          </svg>
        </div>
      ) : null}
    </div>
  );
};
