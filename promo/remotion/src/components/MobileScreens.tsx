import React from 'react';
import {rgba} from '../brand';
import {Icon} from './Icons';
import {
  Avatar,
  BottomNav,
  Btn390,
  C,
  Chip390,
  MH,
  MW,
  Row390,
  SAMPLE,
  Screen390,
  StatusBar,
  Thumb,
  thumbBg,
  Toggle390,
  TopBar,
  VideoCard,
  VideoRow,
} from './MobileUi';
import {clamp, mix} from '../lib/anim';

// The phone app's screens, drawn (phone space 390 x 844). Every screen takes the animation state it needs as props;
// the scenes drive them from the narration's beats.

// ------------------------------------------------------------------------------------------------ home
export const HomePage: React.FC<{scroll?: number; shown?: number}> = ({scroll = 0, shown = 4}) => (
  <Screen390>
    <StatusBar />
    <TopBar title="شاشة" logo actions={['search']} />
    <div style={{position: 'absolute', left: 0, top: 96, width: MW, bottom: 76, overflow: 'hidden'}}>
      <div style={{transform: `translateY(${-scroll}px)`}}>
        {[0, 1, 2, 3, 4, 5].slice(0, shown).map((i) => (
          <VideoCard key={i} i={i} />
        ))}
      </div>
    </div>
    <BottomNav active="home" />
  </Screen390>
);

// ------------------------------------------------------------------------------------------------ search
export const SearchPage: React.FC<{query?: string; typed?: number; results?: number; cursor?: boolean}> = ({query = 'الخط العربي', typed = 0, results = 0, cursor = false}) => {
  const shown = query.slice(0, Math.round(typed));
  const item = (k: number) => clamp(results * 6 - k);
  return (
    <Screen390>
      <StatusBar />
      <div style={{position: 'absolute', left: 14, right: 14, top: 44, height: 52, borderRadius: 26, background: C.higher, display: 'flex', alignItems: 'center', gap: 10, padding: '0 16px'}}>
        <Icon name="search" size={24} color={C.muted} stroke={2.2} />
        <div style={{flex: 1, fontSize: 17, color: shown ? C.text : C.muted, fontWeight: 500}}>
          {shown || 'ابحث عن فيديوهات وقنوات'}
          {cursor ? <span style={{display: 'inline-block', width: 2, height: 22, background: C.primary, marginRight: 2, verticalAlign: 'middle'}} /> : null}
        </div>
        {shown ? <Icon name="x" size={20} color={C.muted} stroke={2.4} /> : null}
      </div>
      <div style={{position: 'absolute', left: 0, right: 0, top: 108, display: 'flex', gap: 8, padding: '0 14px', direction: 'rtl'}}>
        <Chip390 label="الكل" on />
        <Chip390 label="فيديوهات" />
        <Chip390 label="قنوات" />
        <Chip390 label="قوائم تشغيل" />
      </div>
      {!shown ? (
        <div style={{position: 'absolute', left: 0, right: 0, top: 168, padding: '0 18px'}}>
          <div style={{fontSize: 14, color: C.muted, marginBottom: 10}}>عمليات البحث الأخيرة</div>
          {['وثائقيات المحيط', 'تعلم البرمجة', 'كبسة'].map((t) => (
            <div key={t} style={{display: 'flex', alignItems: 'center', gap: 14, height: 46, fontSize: 16, color: C.text}}>
              <Icon name="clock" size={20} color={C.muted} stroke={2} />
              {t}
            </div>
          ))}
        </div>
      ) : (
        <div style={{position: 'absolute', left: 0, right: 0, top: 160, bottom: 76, overflow: 'hidden'}}>
          <div style={{opacity: item(0), transform: `translateY(${(1 - item(0)) * 18}px)`}}>
            <VideoRow i={1} w={150} />
          </div>
          <div style={{opacity: item(1), transform: `translateY(${(1 - item(1)) * 18}px)`, display: 'flex', alignItems: 'center', gap: 14, padding: '10px 14px'}}>
            <Avatar letter="ف" hue={330} size={62} />
            <div style={{flex: 1}}>
              <div style={{fontSize: 16.5, fontWeight: 700}}>فنون الخط</div>
              <div style={{fontSize: 12.5, color: C.muted, marginTop: 3}}>١٫٢ مليون مشترك · ٣٤٠ فيديو</div>
            </div>
            <Chip390 label="اشتراك" on />
          </div>
          <div style={{opacity: item(2), transform: `translateY(${(1 - item(2)) * 18}px)`}}>
            <VideoRow i={4} w={150} />
          </div>
          <div style={{opacity: item(3), transform: `translateY(${(1 - item(3)) * 18}px)`, display: 'flex', gap: 12, padding: '8px 14px'}}>
            <div style={{position: 'relative', width: 150, height: 84}}>
              <div style={{position: 'absolute', right: 0, top: 0, width: 150, height: 84, borderRadius: 10, background: thumbBg(5)}} />
              <div style={{position: 'absolute', right: 0, top: 54, width: 150, height: 30, borderRadius: '0 0 10px 10px', background: 'rgba(0,0,0,0.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6, fontSize: 12.5, fontWeight: 600}}>
                <Icon name="list" size={16} color={C.text} stroke={2.2} />
                ١٢ فيديو
              </div>
            </div>
            <div style={{flex: 1}}>
              <div style={{fontSize: 14.5, fontWeight: 600, lineHeight: 1.4}}>قائمة: دروس الخط العربي للمبتدئين</div>
              <div style={{fontSize: 12.5, color: C.muted, marginTop: 4}}>قائمة تشغيل · فنون الخط</div>
            </div>
          </div>
          <div style={{opacity: item(4), transform: `translateY(${(1 - item(4)) * 18}px)`}}>
            <VideoRow i={0} w={150} />
          </div>
        </div>
      )}
      <BottomNav active="search" />
    </Screen390>
  );
};

// ------------------------------------------------------------------------------------------------ channel
/** tab: 0 videos, 1 Shorts, 2 playlists (fractional while the indicator slides). subscribed: 0..1. */
export const ChannelPage: React.FC<{tab?: number; subscribed?: number; pressSub?: number}> = ({tab = 0, subscribed = 0, pressSub = 0}) => {
  const k = (i: number) => clamp(1 - Math.abs(tab - i) * 1.6);
  const tabs = ['الفيديوهات', 'القصيرة', 'القوائم'];
  return (
    <Screen390>
      <StatusBar />
      <TopBar title="" back actions={['search']} />
      <div style={{position: 'absolute', left: 0, top: 90, width: MW, height: 110, background: 'linear-gradient(135deg, #17381F, #3C6B2E 45%, #0B0F14)'}} />
      <div style={{position: 'absolute', right: 18, top: 150, display: 'flex', alignItems: 'flex-end', gap: 14}}>
        <Avatar letter="ف" hue={330} size={78} />
        <div style={{paddingBottom: 2}}>
          <div style={{fontSize: 23, fontWeight: 700}}>فنون الخط</div>
          <div style={{fontSize: 13, color: C.muted, marginTop: 2}}>١٫٢ مليون مشترك · @fonoon</div>
        </div>
      </div>
      <div style={{position: 'absolute', left: 18, top: 214, transform: `scale(${1 - pressSub * 0.05})`}}>
        <div
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 8,
            height: 40,
            padding: '0 20px',
            borderRadius: 20,
            fontSize: 15,
            fontWeight: 700,
            background: subscribed > 0.5 ? C.higher : C.primary,
            color: subscribed > 0.5 ? C.text : C.onPrimary,
            boxShadow: pressSub ? `0 0 ${24 * pressSub}px ${rgba(C.primary, 0.6 * pressSub)}` : undefined,
          }}
        >
          {subscribed > 0.5 ? <Icon name="check" size={18} color={C.primaryBright} stroke={3} /> : null}
          {subscribed > 0.5 ? 'مشترك' : 'اشتراك'}
        </div>
      </div>
      <div style={{position: 'absolute', left: 0, top: 264, width: MW, height: 48, display: 'flex', borderBottom: `1px solid ${C.outline}`}}>
        {tabs.map((t, i) => (
          <div key={t} style={{flex: 1, display: 'grid', placeItems: 'center', fontSize: 15.5, fontWeight: 700, color: mix(0.62, 1, k(i)) > 0.8 ? C.text : C.muted}}>
            {t}
          </div>
        ))}
        <div style={{position: 'absolute', bottom: -1, height: 3, width: MW / 3 - 36, borderRadius: 2, background: C.primary, right: 18 + tab * (MW / 3)}} />
      </div>
      <div style={{position: 'absolute', left: 0, top: 316, width: MW, bottom: 0, overflow: 'hidden'}}>
        <div style={{position: 'absolute', inset: 0, opacity: k(0), transform: `translateX(${(0 - tab) * -60}px)`}}>
          {[1, 3, 5, 0].map((i) => (
            <VideoRow key={i} i={i} w={150} />
          ))}
        </div>
        <div style={{position: 'absolute', inset: 0, opacity: k(1), transform: `translateX(${(1 - tab) * -60}px)`, padding: '12px 14px', display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 8, alignContent: 'start'}}>
          {[0, 1, 2, 3, 4, 5].map((i) => (
            <Thumb key={i} i={i + 3} w={118} h={200} dur={`0:${30 + i * 4}`} radius={12} />
          ))}
        </div>
        <div style={{position: 'absolute', inset: 0, opacity: k(2), transform: `translateX(${(2 - tab) * -60}px)`, padding: '8px 0'}}>
          {[0, 1, 2].map((i) => (
            <div key={i} style={{display: 'flex', gap: 12, padding: '8px 14px'}}>
              <div style={{position: 'relative', width: 150, height: 84}}>
                <div style={{position: 'absolute', right: 8, top: -4, width: 134, height: 84, borderRadius: 10, background: thumbBg(i + 1), opacity: 0.5}} />
                <div style={{position: 'absolute', right: 0, top: 0, width: 150, height: 84, borderRadius: 10, background: thumbBg(i + 1)}} />
                <div style={{position: 'absolute', right: 0, bottom: 0, left: 0, height: 28, borderRadius: '0 0 10px 10px', background: 'rgba(0,0,0,0.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6, fontSize: 12.5, fontWeight: 600}}>
                  <Icon name="list" size={16} color={C.text} stroke={2.2} />
                  {[12, 8, 20][i]} فيديو
                </div>
              </div>
              <div style={{flex: 1}}>
                <div style={{fontSize: 14.5, fontWeight: 600, lineHeight: 1.4}}>{['دروس الخط العربي للمبتدئين', 'أدوات الخطاط', 'لوحات رمضان'][i]}</div>
                <div style={{fontSize: 12.5, color: C.muted, marginTop: 4}}>قائمة تشغيل</div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </Screen390>
  );
};

// ------------------------------------------------------------------------------------------------ player
/** The video area drawn at (x, y) with size w x w*9/16: used by the player page and by the PiP window. */
export const VideoSurface: React.FC<{w: number; seed?: number; time?: number; playing?: boolean; radius?: number}> = ({w, seed = 0, time = 0, playing = true, radius = 0}) => {
  const h = (w * 9) / 16;
  const drift = playing ? Math.sin(time * 0.9) * 6 : 0;
  return (
    <div style={{position: 'relative', width: w, height: h, borderRadius: radius, overflow: 'hidden', background: '#071018'}}>
      <div style={{position: 'absolute', inset: -20, background: thumbBg(seed), transform: `translateX(${drift}px) scale(1.15)`}} />
      <div style={{position: 'absolute', inset: 0, background: 'repeating-linear-gradient(100deg, rgba(255,255,255,0) 0px, rgba(255,255,255,0.05) 26px, rgba(255,255,255,0) 58px)', transform: `translateX(${drift * 3}px)`, opacity: 0.8}} />
      <div style={{position: 'absolute', left: '18%', top: '52%', width: '64%', height: '40%', borderRadius: '50%', background: 'radial-gradient(50% 50% at 50% 50%, rgba(255,255,255,0.12), transparent 70%)', transform: `translateX(${-drift * 2}px)`}} />
      <div style={{position: 'absolute', left: 0, right: 0, bottom: 0, height: '34%', background: 'linear-gradient(180deg, rgba(0,0,0,0), rgba(3,10,16,0.55))'}} />
    </div>
  );
};

const fmt = (s: number) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;

/** The player page. time/duration in seconds; sponsor = [start, end] shown on the seek bar. */
export const PlayerPage: React.FC<{
  time?: number;
  duration?: number;
  playing?: boolean;
  controls?: number;
  speed?: string;
  sponsor?: [number, number];
  sponsorHi?: number;
  toast?: number;
  pipGlow?: number;
  dlGlow?: number;
  speedGlow?: number;
  hideSurface?: boolean;
  resume?: number;
}> = ({time = 120, duration = 754, playing = true, controls = 1, speed, sponsor, sponsorHi = 0, toast = 0, pipGlow = 0, dlGlow = 0, speedGlow = 0, hideSurface = false, resume = 0}) => {
  const W = MW;
  const H = (W * 9) / 16;
  const p = time / duration;
  return (
    <Screen390>
      <StatusBar />
      <div style={{position: 'absolute', left: 0, top: 34, width: W, height: H}}>
        {hideSurface ? <div style={{width: W, height: H, background: '#05080B'}} /> : <VideoSurface w={W} seed={0} time={time} playing={playing} />}
        {/* controls */}
        <div style={{position: 'absolute', inset: 0, opacity: controls, background: 'linear-gradient(180deg, rgba(0,0,0,0.5), rgba(0,0,0,0) 35%, rgba(0,0,0,0) 60%, rgba(0,0,0,0.65))'}}>
          <div style={{position: 'absolute', right: 12, top: 10, display: 'flex', gap: 8, alignItems: 'center'}}>
            <Icon name="arrowRight" size={26} color="#fff" stroke={2.2} />
          </div>
          <div style={{position: 'absolute', left: 12, top: 10, display: 'flex', gap: 14, alignItems: 'center'}}>
            <div style={{width: 40, height: 40, borderRadius: 999, display: 'grid', placeItems: 'center', background: pipGlow ? rgba(C.primary, 0.35) : 'transparent', boxShadow: pipGlow ? `0 0 ${20 * pipGlow}px ${rgba(C.primary, 0.7 * pipGlow)}` : undefined}}>
              <Icon name="layers" size={24} color="#fff" stroke={2.1} />
            </div>
            <Icon name="sliders" size={24} color="#fff" stroke={2.1} />
          </div>
          <div style={{position: 'absolute', left: 0, right: 0, top: H / 2 - 28, display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 40}}>
            <Icon name="back" size={30} color="#fff" stroke={2.1} />
            <div style={{width: 56, height: 56, borderRadius: 999, background: 'rgba(0,0,0,0.5)', display: 'grid', placeItems: 'center'}}>
              <Icon name={playing ? 'pause' : 'play'} size={30} color="#fff" stroke={2.2} />
            </div>
            <Icon name="skip" size={30} color="#fff" stroke={2.1} />
          </div>
          {/* seek bar */}
          <div style={{position: 'absolute', left: 14, right: 14, bottom: 22, height: 4, borderRadius: 2, background: 'rgba(255,255,255,0.3)'}}>
            {sponsor ? (
              <>
                <div style={{position: 'absolute', right: `${(sponsor[0] / duration) * 100}%`, width: `${((sponsor[1] - sponsor[0]) / duration) * 100}%`, top: -3, height: 10, borderRadius: 4, background: `rgba(0,212,106,${0.75 + 0.25 * sponsorHi})`, boxShadow: sponsorHi ? `0 0 ${16 * sponsorHi}px rgba(0,212,106,0.95)` : undefined}} />
                <div style={{position: 'absolute', right: `${((sponsor[0] + sponsor[1]) / 2 / duration) * 100}%`, top: -30, transform: 'translateX(50%)', padding: '1px 8px', borderRadius: 8, background: 'rgba(0,212,106,0.95)', color: '#032A15', fontSize: 11.5, fontWeight: 800, opacity: sponsorHi}}>راعٍ</div>
              </>
            ) : null}
            <div style={{position: 'absolute', right: 0, top: 0, bottom: 0, width: `${p * 100}%`, borderRadius: 2, background: '#FF5C5C'}} />
            <div style={{position: 'absolute', right: `calc(${p * 100}% - 7px)`, top: -5, width: 14, height: 14, borderRadius: 999, background: '#FF5C5C'}} />
          </div>
          <div style={{position: 'absolute', right: 14, bottom: 32, fontSize: 12, fontWeight: 600, direction: 'ltr', color: '#fff'}}>
            {fmt(time)} / {fmt(duration)}
          </div>
          {speed ? (
            <div style={{position: 'absolute', left: 14, bottom: 34, padding: '2px 10px', borderRadius: 10, fontSize: 13, fontWeight: 700, background: speedGlow ? C.primary : 'rgba(0,0,0,0.6)', color: speedGlow ? C.onPrimary : '#fff', direction: 'ltr', boxShadow: speedGlow ? `0 0 ${18 * speedGlow}px ${rgba(C.primary, 0.7 * speedGlow)}` : undefined}}>
              {speed}
            </div>
          ) : null}
        </div>
        {/* the skip notice */}
        {toast > 0 ? (
          <div style={{position: 'absolute', left: 14, right: 14, bottom: 46, height: 42, borderRadius: 12, background: 'rgba(30,38,50,0.96)', display: 'flex', alignItems: 'center', gap: 10, padding: '0 14px', opacity: toast, transform: `translateY(${(1 - toast) * 12}px)`, fontSize: 14, fontWeight: 600}}>
            <Icon name="skip" size={20} color={C.primaryBright} stroke={2.3} />
            <span style={{flex: 1}}>تم تخطي: راعٍ</span>
            <span style={{color: C.primaryBright, fontWeight: 700}}>تراجع</span>
          </div>
        ) : null}
        {resume > 0 ? (
          <div style={{position: 'absolute', left: 14, top: 52, padding: '4px 12px', borderRadius: 12, background: rgba(C.primary, 0.9), color: C.onPrimary, fontSize: 13, fontWeight: 700, opacity: resume}}>تكمل من {fmt(time)}</div>
        ) : null}
      </div>
      {/* title and actions */}
      <div style={{position: 'absolute', left: 0, top: 34 + H, width: W, padding: '12px 14px 0'}}>
        <div style={{fontSize: 18, fontWeight: 700, lineHeight: 1.4}}>{SAMPLE[0].title}</div>
        <div style={{fontSize: 12.5, color: C.muted, marginTop: 4}}>{SAMPLE[0].meta}</div>
        <div style={{display: 'flex', alignItems: 'center', gap: 12, marginTop: 14}}>
          <Avatar letter="ع" hue={212} size={40} />
          <div style={{flex: 1, fontSize: 15, fontWeight: 600}}>عالم الطبيعة</div>
          <Chip390 label="اشتراك" on />
        </div>
        <div style={{display: 'flex', gap: 8, marginTop: 14}}>
          <div style={{boxShadow: dlGlow ? `0 0 ${22 * dlGlow}px ${rgba(C.primary, 0.7 * dlGlow)}` : undefined, borderRadius: 10}}>
            <Chip390 label="تنزيل" icon="download" on={dlGlow > 0.4} />
          </div>
          <Chip390 label="المشاهدة لاحقاً" icon="clock" />
          <Chip390 label="مشاركة" icon="send" />
        </div>
        <div style={{fontSize: 14, color: C.muted, margin: '16px 0 4px'}}>فيديوهات مقترحة</div>
        <VideoRow i={1} w={140} />
        <VideoRow i={3} w={140} />
      </div>
    </Screen390>
  );
};

/** A bottom sheet over the player: speed options. sel = highlighted index. */
export const SpeedSheet: React.FC<{sel: number; t: number}> = ({sel, t}) => {
  const opts = ['٠٫٥×', '٠٫٧٥×', '١×', '١٫٢٥×', '١٫٥×', '٢×'];
  return (
    <>
      <div style={{position: 'absolute', left: 0, top: 0, width: MW, height: MH, background: `rgba(0,0,0,${0.55 * t})`}} />
      <div style={{position: 'absolute', left: 0, bottom: 0, width: MW, transform: `translateY(${(1 - t) * 420}px)`, background: C.higher, borderRadius: '24px 24px 0 0', padding: '14px 0 28px', direction: 'rtl'}}>
        <div style={{width: 40, height: 4, borderRadius: 2, background: C.faint, margin: '0 auto 14px'}} />
        <div style={{display: 'flex', alignItems: 'center', gap: 12, padding: '4px 20px 12px', fontSize: 19, fontWeight: 700}}>
          <Icon name="gauge" size={26} color={C.primaryBright} stroke={2.2} />
          السرعة
        </div>
        {opts.map((o, i) => (
          <div key={o} style={{display: 'flex', alignItems: 'center', gap: 14, height: 50, padding: '0 22px', fontSize: 18, fontWeight: i === sel ? 700 : 500, background: i === sel ? rgba(C.primary, 0.18) : 'transparent', color: i === sel ? C.primaryBright : C.text}}>
            <span style={{flex: 1, direction: 'ltr', textAlign: 'right'}}>{o}</span>
            {i === sel ? <Icon name="check" size={22} color={C.primaryBright} stroke={3} /> : null}
          </div>
        ))}
      </div>
    </>
  );
};

/** The download sheet: video (MP4 with sound) qualities, audio only. */
export const DownloadSheet: React.FC<{t: number; hiVideo?: number; hiAudio?: number; picked?: 'video' | 'audio' | null}> = ({t, hiVideo = 0, hiAudio = 0, picked = null}) => (
  <>
    <div style={{position: 'absolute', left: 0, top: 0, width: MW, height: MH, background: `rgba(0,0,0,${0.55 * t})`}} />
    <div style={{position: 'absolute', left: 0, bottom: 0, width: MW, transform: `translateY(${(1 - t) * 520}px)`, background: C.higher, borderRadius: '24px 24px 0 0', padding: '14px 0 26px', direction: 'rtl'}}>
      <div style={{width: 40, height: 4, borderRadius: 2, background: C.faint, margin: '0 auto 12px'}} />
      <div style={{display: 'flex', alignItems: 'center', gap: 12, padding: '4px 20px 10px', fontSize: 20, fontWeight: 700}}>
        <Icon name="download" size={26} color={C.primaryBright} stroke={2.2} />
        تنزيل
      </div>
      <div style={{padding: '8px 20px 6px', fontSize: 13.5, color: C.muted, fontWeight: 600, background: hiVideo ? rgba(C.primary, 0.1 * hiVideo) : 'transparent'}}>فيديو (MP4 بالصوت)</div>
      {[['١٠٨٠p', '١٤٢ ميغابايت'], ['٧٢٠p', '٧٨ ميغابايت'], ['٤٨٠p', '٤١ ميغابايت']].map(([q, size], i) => (
        <div key={q} style={{display: 'flex', alignItems: 'center', height: 48, padding: '0 22px', fontSize: 17, fontWeight: 600, background: hiVideo && i === 1 ? rgba(C.primary, 0.2 * hiVideo) : 'transparent', borderTop: `1px solid ${rgba(C.outline, 0.6)}`}}>
          <Icon name="tv" size={22} color={C.muted} stroke={2} />
          <span style={{flex: 1, marginRight: 14, direction: 'ltr', textAlign: 'right'}}>{q}</span>
          <span style={{fontSize: 13.5, color: C.muted}}>{size}</span>
        </div>
      ))}
      <div style={{padding: '14px 20px 6px', fontSize: 13.5, color: C.muted, fontWeight: 600, background: hiAudio ? rgba(C.primary, 0.1 * hiAudio) : 'transparent'}}>الصوت فقط</div>
      <div style={{display: 'flex', alignItems: 'center', height: 52, padding: '0 22px', fontSize: 17, fontWeight: 600, background: hiAudio ? rgba(C.primary, 0.22 * hiAudio) : 'transparent', borderTop: `1px solid ${rgba(C.outline, 0.6)}`}}>
        <Icon name="mic" size={22} color={hiAudio ? C.primaryBright : C.muted} stroke={2} />
        <span style={{flex: 1, marginRight: 14}}>صوت (M4A)</span>
        <span style={{fontSize: 13.5, color: C.muted}}>١٢ ميغابايت</span>
      </div>
      {picked ? (
        <div style={{margin: '12px 20px 0', height: 8, borderRadius: 4, background: C.outline, overflow: 'hidden'}}>
          <div style={{height: '100%', width: '62%', background: C.primary, marginRight: 0, float: 'right'}} />
        </div>
      ) : null}
    </div>
  </>
);

// ------------------------------------------------------------------------------------------------ library
export const LibraryPage: React.FC<{hi?: number; hiIndex?: number}> = ({hi = 0, hiIndex = -1}) => {
  const tiles: Array<{icon: 'download' | 'clock' | 'heart' | 'star'; label: string; sub: string}> = [
    {icon: 'download', label: 'التنزيلات', sub: '٤ ملفات'},
    {icon: 'clock', label: 'السجل', sub: 'ما شاهدته'},
    {icon: 'heart', label: 'المشاهدة لاحقاً', sub: '٣ فيديوهات'},
    {icon: 'star', label: 'الاشتراكات', sub: '١٢ قناة'},
  ];
  return (
    <Screen390>
      <StatusBar />
      <TopBar title="مكتبتي" />
      <div style={{position: 'absolute', left: 0, top: 104, width: MW, padding: '0 16px', display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12}}>
        {tiles.map((t, i) => (
          <div key={t.label} style={{height: 118, borderRadius: 22, background: i === hiIndex ? rgba(C.primary, 0.16 + 0.1 * hi) : C.surface, border: `1.5px solid ${i === hiIndex ? rgba(C.primary, 0.7) : C.outline}`, padding: 16, display: 'flex', flexDirection: 'column', justifyContent: 'space-between'}}>
            <Icon name={t.icon} size={30} color={C.primaryBright} stroke={2.1} />
            <div>
              <div style={{fontSize: 18, fontWeight: 700}}>{t.label}</div>
              <div style={{fontSize: 13, color: C.muted, marginTop: 2}}>{t.sub}</div>
            </div>
          </div>
        ))}
      </div>
      <div style={{position: 'absolute', left: 0, top: 372, width: MW}}>
        <div style={{padding: '0 18px 6px', fontSize: 15, fontWeight: 700}}>آخر ما شاهدته</div>
        <VideoRow i={0} w={140} progress={0.34} />
        <VideoRow i={2} w={140} progress={0.8} />
      </div>
      <BottomNav active="library" />
    </Screen390>
  );
};

export const SubscriptionsPage: React.FC<{shown?: number}> = ({shown = 6}) => {
  const rows = [['ع', 212, 'عالم الطبيعة', '٢١ فيديو جديد'], ['ف', 330, 'فنون الخط', '٣ فيديوهات جديدة'], ['م', 22, 'مطبخ سلمى', '٥ فيديوهات جديدة'], ['ف', 262, 'مختبر الفضاء', 'لا جديد'], ['ب', 190, 'عالم البحار', '٢ فيديو جديد'], ['ق', 38, 'قصص قبل النوم', 'لا جديد']] as const;
  return (
    <Screen390>
      <StatusBar />
      <TopBar title="الاشتراكات" back />
      <div style={{position: 'absolute', left: 0, top: 100, width: MW}}>
        {rows.slice(0, shown).map(([l, h, n, s]) => (
          <div key={n} style={{display: 'flex', alignItems: 'center', gap: 14, padding: '10px 18px', height: 72}}>
            <Avatar letter={l} hue={h} size={50} />
            <div style={{flex: 1}}>
              <div style={{fontSize: 17, fontWeight: 700}}>{n}</div>
              <div style={{fontSize: 13, color: s === 'لا جديد' ? C.faint : C.primaryBright, marginTop: 2}}>{s}</div>
            </div>
            <Chip390 label="مشترك" on />
          </div>
        ))}
      </div>
    </Screen390>
  );
};

export const HistoryPage: React.FC<{shown?: number; hiRow?: number; hi?: number}> = ({shown = 5, hiRow = -1, hi = 0}) => {
  const prog = [0.34, 0.8, 0.12, 0.55, 0.9, 0.2];
  return (
    <Screen390>
      <StatusBar />
      <TopBar title="السجل" back actions={['x']} />
      <div style={{position: 'absolute', left: 0, top: 100, width: MW}}>
        <div style={{padding: '0 18px 6px', fontSize: 13.5, color: C.muted, fontWeight: 600}}>اليوم</div>
        {[0, 1, 2, 3, 4, 5].slice(0, shown).map((i) => (
          <VideoRow key={i} i={i} w={140} progress={prog[i]} hi={i === hiRow ? hi : 0} />
        ))}
      </div>
    </Screen390>
  );
};

// ------------------------------------------------------------------------------------------------ settings and update
export const SettingsPage: React.FC<{hiUpdate?: number}> = ({hiUpdate = 0}) => (
  <Screen390>
    <StatusBar />
    <TopBar title="الإعدادات" />
    <div style={{position: 'absolute', left: 0, top: 100, width: MW}}>
      <Row390 icon="sliders" title="اللغة" sub="حسب الهاتف" />
      <Row390 icon="moon" title="المظهر" sub="حسب الهاتف" />
      <Row390 icon="gauge" title="الجودة الافتراضية" sub="تلقائي" />
      <div style={{padding: '14px 18px 4px', fontSize: 13.5, color: C.primaryBright, fontWeight: 700}}>التشغيل</div>
      <Row390 icon="play" title="التشغيل في الخلفية" sub="يستمر الصوت عند مغادرة التطبيق أو إطفاء الشاشة." trailing={<Toggle390 p={1} />} />
      <Row390 icon="layers" title="صورة داخل صورة" trailing={<Toggle390 p={1} />} />
      <Row390 icon="skip" title="SponsorBlock" sub="تخطي المقاطع تلقائياً" trailing={<Toggle390 p={1} />} />
      <div style={{padding: '14px 18px 4px', fontSize: 13.5, color: C.primaryBright, fontWeight: 700}}>التطبيق</div>
      <Row390 icon="download" title="فحص التحديثات" sub="هل يوجد إصدار أحدث؟" hi={hiUpdate} />
      <Row390 icon="user" title="حول" />
    </div>
    <BottomNav active="settings" />
  </Screen390>
);

/** state: 'available' | 'downloading' | 'installing'. */
export const UpdatePage: React.FC<{state: 'available' | 'downloading' | 'installing'; progress?: number; glow?: number}> = ({state, progress = 0, glow = 0}) => (
  <Screen390>
    <StatusBar />
    <TopBar title="فحص التحديثات" back />
    <div style={{position: 'absolute', left: 0, top: 130, width: MW, textAlign: 'center'}}>
      <div style={{width: 96, height: 96, margin: '0 auto', borderRadius: 28, display: 'grid', placeItems: 'center', background: rgba(C.primary, 0.14), border: `2px solid ${rgba(C.primary, 0.5)}`}}>
        <Icon name={state === 'installing' ? 'refresh' : 'download'} size={48} color={C.primaryBright} stroke={2.1} />
      </div>
      <div style={{fontSize: 24, fontWeight: 700, marginTop: 24}}>
        {state === 'available' ? 'الإصدار ١٫٢٫٠ متاح' : state === 'downloading' ? 'جارٍ تنزيل التحديث…' : 'جارٍ التثبيت…'}
      </div>
      <div style={{fontSize: 14.5, color: C.muted, marginTop: 8, padding: '0 36px', lineHeight: 1.6}}>
        {state === 'available' ? 'الإصدار المثبّت: ١٫١٫١' : state === 'downloading' ? `${(18 * progress).toFixed(1)} من ١٨٫٠ ميغابايت`.replace(/[0-9]/g, (d) => '٠١٢٣٤٥٦٧٨٩'[+d]) : 'قد يطلب النظام تأكيدك.'}
      </div>
      {state === 'downloading' ? (
        <div style={{margin: '26px 40px 0', height: 10, borderRadius: 5, background: C.outline, overflow: 'hidden'}}>
          <div style={{height: '100%', width: `${progress * 100}%`, background: C.primary, float: 'right'}} />
        </div>
      ) : null}
      {state === 'available' ? (
        <div style={{marginTop: 34}}>
          <Btn390 label="نزّل وثبّت" icon="download" w={230} glow={glow} />
        </div>
      ) : null}
    </div>
  </Screen390>
);
