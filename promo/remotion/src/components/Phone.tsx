import React from 'react';
import {Img} from 'remotion';
import {brand, rgba} from '../brand';
import {clamp, mix} from '../lib/anim';
import {dir, isEn, startSide, t} from '../lib/lang';
import {focusRect, screenSrc} from '../lib/screens';
import {LogoMark, Wordmark} from './Brand';
import {Icon, IconName} from './Icons';
import {QrCode} from './QrCode';
import {ScreenPatch} from './Patches';
import {PAIR_URL_SHORT} from '../lib/demo';

// Generic phone mockup (screen = 390x844 CSS px, the :remote page's viewport) + vector versions of the
// remote web page (mirrors remote/src/main/resources/web/index.html). When a real capture exists in
// public/screens (phone_*.png, 1170x2532) it is shown instead of the vector page.
// The real remote page is Arabic only, so its captures stay Arabic in the English version too; the vector pages (drawn
// only when a capture is missing) go through t() and follow the language's direction.

export const PW = 390;
export const PH = 844;

export const PhoneFrame: React.FC<{x: number; y: number; scale: number; children?: React.ReactNode; opacity?: number; rotate?: number}> = ({
  x,
  y,
  scale,
  children,
  opacity = 1,
  rotate = 0,
}) => {
  const bz = 13;
  return (
    <div
      style={{
        position: 'absolute',
        left: x,
        top: y,
        width: (PW + bz * 2) * scale,
        height: (PH + bz * 2) * scale,
        opacity,
        transform: `rotate(${rotate}deg)`,
        filter: 'drop-shadow(0 40px 60px rgba(0,0,0,0.6))',
      }}
    >
      <div
        style={{
          position: 'absolute',
          inset: 0,
          borderRadius: 64 * scale,
          background: 'linear-gradient(135deg, #2A333E, #0D1116 40%, #151B22 100%)',
          boxShadow: `inset 0 0 0 ${1.5 * scale}px rgba(255,255,255,0.14)`,
        }}
      />
      {/* side buttons */}
      <div style={{position: 'absolute', right: -3 * scale, top: 190 * scale, width: 4 * scale, height: 90 * scale, borderRadius: 3, background: '#1E252E'}} />
      <div style={{position: 'absolute', left: -3 * scale, top: 160 * scale, width: 4 * scale, height: 60 * scale, borderRadius: 3, background: '#1E252E'}} />
      <div
        style={{
          position: 'absolute',
          left: bz * scale,
          top: bz * scale,
          width: PW * scale,
          height: PH * scale,
          borderRadius: 52 * scale,
          overflow: 'hidden',
          background: brand.bg,
        }}
      >
        <div style={{position: 'absolute', left: 0, top: 0, width: PW, height: PH, transform: `scale(${scale})`, transformOrigin: '0 0', overflow: 'hidden'}}>
          {children}
        </div>
        {/* glass */}
        <div style={{position: 'absolute', inset: 0, background: 'linear-gradient(125deg, rgba(255,255,255,0.08), rgba(255,255,255,0) 35%)', pointerEvents: 'none'}} />
      </div>
      {/* punch-hole camera */}
      <div style={{position: 'absolute', left: '50%', top: (bz + 14) * scale, width: 12 * scale, height: 12 * scale, borderRadius: 999, transform: 'translateX(-50%)', background: '#05070A', boxShadow: `inset 0 0 0 ${2 * scale}px #151B22`}} />
    </div>
  );
};

/** Phone-space px rect of a manifest focus entry (phone screens). */
export const pr = (name: string, key: string): [number, number, number, number] => {
  const r = focusRect(name, key);
  return [r[0] * PW, r[1] * PH, r[2] * PW, r[3] * PH];
};

const abs = (s: React.CSSProperties): React.CSSProperties => ({position: 'absolute', ...s});
const font: React.CSSProperties = {fontFamily: brand.font, direction: dir, color: brand.text};

const StatusBar: React.FC<{dark?: boolean}> = () => (
  <div style={abs({left: 0, right: 0, top: 0, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 28px', fontFamily: brand.font, fontSize: 15, fontWeight: 600, color: brand.text, direction: 'ltr'})}>
    <span>9:41</span>
    <span style={{display: 'flex', gap: 6, alignItems: 'center'}}>
      <span style={{width: 18, height: 10, borderRadius: 2, border: '1.5px solid #EAF0F6', display: 'inline-block'}} />
    </span>
  </div>
);

const TopBar: React.FC<{who?: string; kid?: boolean}> = ({who = t('الكبار'), kid}) => (
  <div style={abs({left: 0, right: 0, top: 44, height: 58, display: 'flex', alignItems: 'center', gap: 10, padding: '0 16px', borderBottom: '1px solid rgba(234,240,246,0.07)', ...font})}>
    <div style={{display: 'flex', alignItems: 'center', gap: 6}}>
      <div style={{width: 30, height: 30, position: 'relative'}}>
        <LogoMark size={40} style={{position: 'absolute', left: -5, top: -6}} />
      </div>
      <Wordmark width={62} />
    </div>
    <div style={{flex: 1}} />
    <div style={{width: 9, height: 9, borderRadius: 9, background: brand.primary, boxShadow: `0 0 0 4px ${rgba(brand.primary, 0.16)}`}} />
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        height: 34,
        padding: '0 12px 0 6px',
        borderRadius: 999,
        background: kid ? rgba(brand.kids, 0.13) : '#1E2632',
        color: kid ? brand.kids : brand.text,
        fontSize: 14,
        fontWeight: 700,
      }}
    >
      <div style={{width: 24, height: 24, borderRadius: 999, background: kid ? rgba(brand.kids, 0.3) : rgba(brand.primary, 0.25)}} />
      {who}
    </div>
  </div>
);

const TabBar: React.FC<{active: number}> = ({active}) => {
  const tabs = [
    ['tv', t('الريموت')],
    ['search', t('البحث')],
    ['shield', t('الأب')],
    ['sliders', t('المزيد')],
  ] as Array<[IconName, string]>;
  return (
    <div style={abs({left: 0, right: 0, bottom: 0, height: 74, display: 'flex', background: 'rgba(11,15,20,0.92)', borderTop: '1px solid rgba(234,240,246,0.07)', ...font})}>
      {tabs.map(([ic, l], i) => (
        <div key={l} style={{flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 3, color: i === active ? brand.primary : brand.muted, fontSize: 12, fontWeight: 700}}>
          <Icon name={ic} size={24} color={i === active ? brand.primary : brand.muted} stroke={2} />
          {l}
        </div>
      ))}
    </div>
  );
};

const card = (s: React.CSSProperties): React.CSSProperties => abs({
  background: brand.surface,
  border: '1px solid rgba(234,240,246,0.07)',
  borderRadius: 22,
  boxShadow: '0 12px 32px rgba(0,0,0,0.22)',
  ...s,
});

/** The remote page: now playing + search + send a link. */
export const PhoneRemotePage: React.FC<{query?: string; typed?: number; results?: number; link?: number; playing?: boolean}> = ({
  query = t('وثائقي المحيط'),
  typed = 0,
  results = 0,
  link = 0,
  playing = true,
}) => {
  const sr = pr('phone_remote_main', 'search');
  const lk = pr('phone_remote_main', 'link');
  const sd = pr('phone_remote_main', 'send');
  const fr0 = pr('phone_remote_main', 'firstResult');
  return (
    <div style={abs({inset: 0, background: `radial-gradient(140% 55% at 50% -12%, ${rgba(brand.primary, 0.13)}, transparent 62%), ${brand.bg}`, ...font})}>
      <StatusBar />
      <TopBar />
      {/* now playing */}
      <div style={card({left: 16, right: 16, top: 114, height: 250, padding: 14, boxSizing: 'border-box'})}>
        <div style={{display: 'flex', gap: 12}}>
          <div style={{width: 150, height: 84, borderRadius: 14, background: 'radial-gradient(70% 90% at 40% 40%, #1A6A6E, #0D3446 60%, #050B12)', flex: 'none'}} />
          <div style={{flex: 1}}>
            <div style={{fontSize: 15, fontWeight: 800, lineHeight: 1.45}}>{t('رحلة إلى أعماق المحيط: كائنات لم ترها من قبل')}</div>
            <div style={{fontSize: 12.5, color: brand.muted, marginTop: 4}}>{t('عالم الطبيعة')}</div>
          </div>
        </div>
        <div style={{height: 6, borderRadius: 6, background: 'rgba(234,240,246,0.1)', marginTop: 18, direction: 'ltr'}}>
          <div style={{width: '35%', height: '100%', borderRadius: 6, background: `linear-gradient(90deg, ${brand.primary}, ${brand.primaryBright})`}} />
        </div>
        <div style={{display: 'flex', justifyContent: 'space-between', fontSize: 12, color: brand.muted, marginTop: 6, direction: 'ltr', fontWeight: 600}}>
          <span>4:22</span>
          <span>12:34</span>
        </div>
        <div style={{display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 16, marginTop: 14}}>
          {(['back', 'minus', 'play', 'plus', 'x'] as IconName[]).map((ic, i) =>
            i === 2 ? (
              <div key={i} style={{width: 72, height: 72, borderRadius: 999, background: brand.primary, display: 'grid', placeItems: 'center', boxShadow: `0 10px 28px ${rgba(brand.primary, 0.35)}`}}>
                <Icon name={playing ? 'pause' : 'play'} size={30} color={brand.onPrimary} />
              </div>
            ) : (
              <div key={i} style={{width: 50, height: 50, borderRadius: 999, background: '#1E2632', display: 'grid', placeItems: 'center'}}>
                <Icon name={ic === 'x' ? 'refresh' : ic} size={20} color={brand.text} />
              </div>
            ),
          )}
        </div>
      </div>
      {/* search */}
      <div style={abs({[startSide]: 20, top: sr[1] - 26, fontSize: 13, fontWeight: 700, color: brand.muted})}>{t('بحث')}</div>
      <div
        style={abs({
          left: sr[0],
          top: sr[1],
          width: sr[2],
          height: sr[3],
          borderRadius: 15,
          background: '#1E2632',
          border: `1px solid ${typed > 0 ? rgba(brand.primary, 0.8) : 'rgba(234,240,246,0.13)'}`,
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          padding: '0 14px',
          boxSizing: 'border-box',
          fontSize: 16,
        })}
      >
        <Icon name="search" size={20} color={brand.faint} />
        {typed > 0 ? <span>{query.slice(0, typed)}</span> : <span style={{color: brand.faint}}>{t('ابحث في يوتيوب…')}</span>}
        {typed > 0 && typed < query.length ? <span style={{width: 2, height: 20, background: brand.primary}} /> : null}
      </div>
      {/* results */}
      {[0, 1].map((i) => (
        <div
          key={i}
          style={abs({
            left: fr0[0],
            top: fr0[1] + i * 76,
            width: fr0[2],
            height: 70,
            display: 'flex',
            gap: 12,
            alignItems: 'center',
            opacity: clamp(results * 2 - i * 0.5),
            transform: `translateY(${(1 - clamp(results * 2 - i * 0.5)) * 10}px)`,
          })}
        >
          <div style={{width: 112, height: 63, borderRadius: 10, background: i === 0 ? 'radial-gradient(70% 90% at 40% 40%, #1F5A70, #081620)' : 'radial-gradient(70% 90% at 40% 40%, #3C3163, #120E22)', flex: 'none'}} />
          <div style={{flex: 1}}>
            <div style={{fontSize: 14, fontWeight: 700}}>{i === 0 ? t('وثائقي: أسرار أعماق المحيط') : t('المحيط الهادئ من الفضاء')}</div>
            <div style={{fontSize: 12, color: brand.muted, marginTop: 3}}>{i === 0 ? t('عالم الطبيعة · 44:10') : t('رحلات هادئة · 18:02')}</div>
          </div>
        </div>
      ))}
      {/* send a link */}
      <div style={abs({[startSide]: 20, top: lk[1] - 26, fontSize: 13, fontWeight: 700, color: brand.muted})}>{t('أرسل رابطاً إلى الشاشة')}</div>
      <div
        style={abs({
          left: lk[0],
          top: lk[1],
          width: lk[2],
          height: lk[3],
          borderRadius: 15,
          background: '#1E2632',
          border: '1px solid rgba(234,240,246,0.13)',
          display: 'flex',
          alignItems: 'center',
          padding: '0 12px',
          boxSizing: 'border-box',
          fontSize: 13,
          fontFamily: brand.mono,
          direction: 'ltr',
          color: link > 0 ? '#BFEFEA' : brand.faint,
          overflow: 'hidden',
          whiteSpace: 'nowrap',
        })}
      >
        {link > 0 ? 'https://youtu.be/dQw…' : ''}
        {link <= 0 ? <span style={{fontFamily: brand.font, direction: dir, width: '100%', textAlign: startSide}}>{t('الصق رابط يوتيوب')}</span> : null}
      </div>
      <div
        style={abs({
          left: sd[0],
          top: sd[1],
          width: sd[2],
          height: sd[3],
          borderRadius: 15,
          background: brand.primary,
          color: brand.onPrimary,
          display: 'grid',
          placeItems: 'center',
          fontSize: 14,
          fontWeight: 800,
          boxShadow: `0 8px 22px ${rgba(brand.primary, 0.22)}`,
        })}
      >
        {t('شغّل')}
      </div>
      <TabBar active={0} />
    </div>
  );
};

/** Parent section: PIN pad. `filled` 0..4 */
export const PhoneParentPinPage: React.FC<{filled?: number}> = ({filled = 0}) => {
  const d = pr('phone_parent_pin', 'dots');
  const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '', '0', '⌫'];
  return (
    <div style={abs({inset: 0, background: brand.bg, ...font})}>
      <StatusBar />
      <TopBar />
      <div style={{position: 'absolute', left: 0, right: 0, top: 128, display: 'flex', flexDirection: 'column', alignItems: 'center'}}>
        <div style={{width: 64, height: 64, borderRadius: 20, background: rgba(brand.amber, 0.13), display: 'grid', placeItems: 'center'}}>
          <Icon name="shield" size={36} color={brand.amber} />
        </div>
        <div style={{fontSize: 22, fontWeight: 800, marginTop: 12}}>{t('قسم الأب')}</div>
        <div style={{fontSize: 14, color: brand.muted, marginTop: 4}}>{t('أدخل رمز الأب (منفصل عن رمز التلفاز)')}</div>
      </div>
      <div style={abs({left: d[0], top: d[1], width: d[2], height: d[3], display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 22, direction: 'ltr'})}>
        {[0, 1, 2, 3].map((i) => (
          <div key={i} style={{width: 22, height: 22, borderRadius: 99, background: i < filled ? brand.amber : 'transparent', border: `2.5px solid ${i < filled ? brand.amber : 'rgba(234,240,246,0.3)'}`}} />
        ))}
      </div>
      <div style={abs({left: 40, right: 40, top: 360, display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14, direction: 'ltr'})}>
        {keys.map((k, i) => (
          <div key={i} style={{height: 70, borderRadius: 18, background: k ? '#1E2632' : 'transparent', display: 'grid', placeItems: 'center', fontSize: 26, fontWeight: 700}}>
            {k}
          </div>
        ))}
      </div>
      <TabBar active={2} />
    </div>
  );
};

/** Parent section panel. */
export const PhoneParentPage: React.FC<{minutes?: number; unlocked?: number; toggled?: number}> = ({minutes = 45, unlocked = 0, toggled = 0}) => {
  const p15 = pr('phone_parent', 'plus15');
  const ut = pr('phone_parent', 'unlockTimeUp');
  const cl = pr('phone_parent', 'childList');
  const kids = [t('قناة الحروف'), t('حكايات قبل النوم'), t('علوم للصغار')];
  return (
    <div style={abs({inset: 0, background: brand.bg, ...font})}>
      <StatusBar />
      <TopBar who={t('الأب')} />
      {/* quota */}
      <div style={card({left: 16, right: 16, top: 114, height: 180})} />
      <div style={abs({[startSide]: 32, top: 130, display: 'flex', alignItems: 'center', gap: 10})}>
        <div style={{width: 34, height: 34, borderRadius: 11, background: rgba(brand.amber, 0.13), display: 'grid', placeItems: 'center'}}>
          <Icon name="hourglass" size={19} color={brand.amber} />
        </div>
        <div style={{fontSize: 16.5, fontWeight: 800}}>{t('حصة اليوم · الطفل')}</div>
      </div>
      <div style={abs({[startSide]: 32, top: 176, fontSize: 30, fontWeight: 800, color: brand.kids})}>{t('{n} دقيقة', {n: Math.round(minutes)})}</div>
      <div style={abs({left: p15[0], top: p15[1], width: p15[2], height: p15[3], borderRadius: 15, background: brand.amber, color: brand.onAmber, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 5, fontSize: 15, fontWeight: 800})}><span style={{unicodeBidi: 'isolate', direction: 'ltr'}}>+15</span><span>{isEn ? t('دقائق') : t('دقيقة')}</span></div>
      <div style={abs({left: 16 + 16, top: p15[1], width: p15[2] - 32, height: p15[3], borderRadius: 15, background: '#1E2632', display: 'grid', placeItems: 'center', fontSize: 15, fontWeight: 800})}><span style={{unicodeBidi: 'isolate', direction: 'ltr'}}>−15</span></div>
      {/* time up */}
      <div style={card({left: 16, right: 16, top: 306, height: 150, background: unlocked > 0.5 ? brand.surface : 'rgba(255,181,71,0.08)', border: `1px solid ${unlocked > 0.5 ? 'rgba(234,240,246,0.07)' : 'rgba(255,181,71,0.28)'}`})} />
      <div style={abs({[startSide]: 32, top: 324, fontSize: 15, fontWeight: 800, color: unlocked > 0.5 ? brand.primary : '#FFD69B'})}>{unlocked > 0.5 ? t('تم فتح الشاشة') : t('انتهى الوقت على الشاشة')}</div>
      <div style={abs({[startSide]: 32, top: 348, fontSize: 13, color: brand.muted})}>{t('الطفل: انتهت حصة اليوم')}</div>
      <div
        style={abs({
          left: ut[0] + 16,
          top: ut[1],
          width: ut[2] - 32,
          height: ut[3],
          borderRadius: 15,
          background: unlocked > 0.5 ? rgba(brand.primary, 0.14) : brand.amber,
          color: unlocked > 0.5 ? brand.primary : brand.onAmber,
          display: 'grid',
          placeItems: 'center',
          fontSize: 15,
          fontWeight: 800,
        })}
      >
        {unlocked > 0.5 ? t('✓ مفتوحة') : t('فتح انتهاء الوقت')}
      </div>
      {/* child allow-list */}
      <div style={card({left: 16, right: 16, top: 470, height: 250})} />
      <div style={abs({[startSide]: 32, top: 486, display: 'flex', alignItems: 'center', gap: 10})}>
        <div style={{width: 34, height: 34, borderRadius: 11, background: rgba(brand.kids, 0.13), display: 'grid', placeItems: 'center'}}>
          <Icon name="star" size={19} color={brand.kids} />
        </div>
        <div style={{fontSize: 16.5, fontWeight: 800}}>{t('قائمة الطفل')}</div>
      </div>
      {kids.map((k, i) => {
        const on = i < 2 ? 1 : toggled;
        return (
          <div key={k} style={abs({left: 32, right: 32, top: 524 + i * 40, height: 36, display: 'flex', alignItems: 'center', justifyContent: 'space-between'})}>
            <span style={{fontSize: 14.5, fontWeight: 600}}>{k}</span>
            <div style={{width: 44, height: 26, borderRadius: 99, background: on > 0.5 ? brand.kids : '#2A3546', position: 'relative', direction: 'ltr'}}>
              <div style={{position: 'absolute', top: 3, left: mix(3, 21, on), width: 20, height: 20, borderRadius: 99, background: on > 0.5 ? '#382800' : '#8A97A8'}} />
            </div>
          </div>
        );
      })}
      <div style={abs({left: cl[0] + 16, top: cl[1], width: cl[2] - 32, height: cl[3], borderRadius: 15, background: '#1E2632', display: 'grid', placeItems: 'center', fontSize: 15, fontWeight: 800})}>{t('إدارة القائمة')}</div>
      <TabBar active={2} />
    </div>
  );
};

/** Takeout import page. */
export const PhoneTakeoutPage: React.FC<{drop?: number; progress?: number; done?: number}> = ({drop = 0, progress = 0, done = 0}) => {
  const dz = pr('phone_takeout_import', 'drop');
  const pg = pr('phone_takeout_import', 'progress');
  const dn = pr('phone_takeout_import', 'done');
  return (
    <div style={abs({inset: 0, background: brand.bg, ...font})}>
      <StatusBar />
      <TopBar who={t('الأب')} />
      <div style={abs({[startSide]: 20, top: 122, fontSize: 19, fontWeight: 800})}>{t('استيراد الاشتراكات')}</div>
      <div style={abs({[startSide]: 20, top: 150, fontSize: 13, color: brand.muted})}>{t('ملف subscriptions.csv من Google Takeout')}</div>
      <div
        style={abs({
          left: dz[0],
          top: dz[1],
          width: dz[2],
          height: dz[3],
          borderRadius: 22,
          border: `2px dashed ${drop > 0.9 ? brand.primary : 'rgba(234,240,246,0.2)'}`,
          background: drop > 0.9 ? rgba(brand.primary, 0.06) : 'transparent',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 10,
        })}
      >
        <div style={{transform: `translateY(${(1 - drop) * -120}px)`, opacity: clamp(drop * 2), display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6}}>
          <div style={{position: 'relative', width: 64, height: 78}}>
            <Icon name="file" size={78} color={brand.primary} stroke={1.6} style={{position: 'absolute', left: -7, top: 0}} />
            <div style={{position: 'absolute', left: 9, top: 42, padding: '1px 6px', borderRadius: 6, background: brand.primary, color: brand.onPrimary, fontSize: 11, fontWeight: 800, fontFamily: brand.mono}}>CSV</div>
          </div>
          <div style={{fontSize: 13.5, fontFamily: brand.mono, color: brand.text, direction: 'ltr'}}>subscriptions.csv</div>
        </div>
        {drop <= 0.05 ? <div style={{fontSize: 14, color: brand.muted}}>{t('اختر الملف أو اسحبه هنا')}</div> : null}
      </div>
      <div style={abs({left: pg[0], top: pg[1] + 10, width: pg[2], height: 10, borderRadius: 6, background: 'rgba(234,240,246,0.1)', overflow: 'hidden', direction: dir})}>
        <div style={{width: `${progress * 100}%`, height: '100%', background: brand.primary, borderRadius: 6}} />
      </div>
      <div
        style={abs({
          left: dn[0],
          top: dn[1],
          width: dn[2],
          height: dn[3],
          borderRadius: 16,
          background: rgba(brand.primary, 0.12),
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          padding: '0 14px',
          boxSizing: 'border-box',
          opacity: done,
          transform: `translateY(${(1 - done) * 10}px)`,
        })}
      >
        <Icon name="check" size={22} color={brand.primary} stroke={3} />
        <span style={{fontSize: 15, fontWeight: 800, color: brand.primary}}>{t('تم استيراد 42 قناة')}</span>
      </div>
      <TabBar active={2} />
    </div>
  );
};

/** Camera viewfinder scanning the TV's QR. scan 0..1 (brackets closing), lock 0..1 (found). */
export const PhoneCameraPage: React.FC<{scan: number; lock: number; f: number}> = ({scan, lock, f}) => {
  const q = 200;
  const cx = PW / 2;
  const cy = PH / 2 - 20;
  const b = mix(300, q + 26, scan);
  const corner = (sx: number, sy: number) => (
    <div
      style={abs({
        left: cx + sx * (b / 2) - (sx > 0 ? 34 : 0),
        top: cy + sy * (b / 2) - (sy > 0 ? 34 : 0),
        width: 34,
        height: 34,
        borderTop: sy < 0 ? `5px solid ${lock > 0.5 ? brand.primary : '#fff'}` : undefined,
        borderBottom: sy > 0 ? `5px solid ${lock > 0.5 ? brand.primary : '#fff'}` : undefined,
        borderLeft: sx < 0 ? `5px solid ${lock > 0.5 ? brand.primary : '#fff'}` : undefined,
        borderRight: sx > 0 ? `5px solid ${lock > 0.5 ? brand.primary : '#fff'}` : undefined,
        borderRadius: 8,
      })}
    />
  );
  return (
    <div style={abs({inset: 0, background: 'radial-gradient(80% 60% at 50% 45%, #1B2A33, #070A0D 80%)', ...font})}>
      {/* the TV seen through the camera */}
      <div style={abs({left: cx - 170, top: cy - 125, width: 340, height: 250, borderRadius: 10, background: '#0B0F14', boxShadow: '0 0 0 6px #05080B, 0 0 60px rgba(46,196,182,0.18)', filter: `blur(${(1 - scan) * 1.6}px)`})}>
        <div style={abs({left: 170 - q / 2, top: 125 - q / 2, width: q, height: q})}>
          <QrCode size={q} quiet={1} />
        </div>
      </div>
      {corner(-1, -1)}
      {corner(1, -1)}
      {corner(-1, 1)}
      {corner(1, 1)}
      {lock < 0.5 ? (
        <div style={abs({left: cx - b / 2 + 10, width: b - 20, top: cy - b / 2 + ((f * 7) % b), height: 3, background: `linear-gradient(90deg, transparent, ${brand.primary}, transparent)`, opacity: 0.9})} />
      ) : null}
      <div style={abs({inset: 0, background: brand.primary, opacity: lock > 0 && lock < 0.5 ? Math.sin(lock * 2 * Math.PI) * 0.3 : 0})} />
      <div
        style={abs({
          left: 30,
          right: 30,
          bottom: 120,
          padding: '12px 16px',
          borderRadius: 16,
          background: 'rgba(11,15,20,0.85)',
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          opacity: lock,
          transform: `translateY(${(1 - lock) * 16}px)`,
        })}
      >
        <Icon name="link" size={22} color={brand.primary} />
        <span style={{fontFamily: brand.mono, fontSize: 13, color: '#BFEFEA', direction: 'ltr'}}>{PAIR_URL_SHORT}</span>
      </div>
      <div style={abs({left: 0, right: 0, top: 70, textAlign: 'center', fontSize: 15, fontWeight: 700, color: '#fff'})}>{t('وجّه الكاميرا إلى الرمز')}</div>
    </div>
  );
};

/** A phone screen by manifest name: real capture if present, else the vector page. */
export const PhoneScreen: React.FC<{name: string; opacity?: number; children: React.ReactNode}> = ({name, opacity = 1, children}) => {
  if (opacity <= 0) return null;
  const src = screenSrc(name);
  return (
    <div style={abs({left: 0, top: 0, width: PW, height: PH, opacity})}>
      {src ? (
        <>
          <Img src={src} style={{width: PW, height: PH, display: 'block'}} />
          <ScreenPatch name={name} patch="base" width={PW} />
        </>
      ) : (
        children
      )}
    </div>
  );
};

/** Finger tap marker (phone space). */
export const Tap: React.FC<{f: number; at: number; x: number; y: number}> = ({f, at, x, y}) => {
  const t = (f - at) / 16;
  if (t < -0.4 || t > 1) return null;
  const pre = clamp((f - (at - 6)) / 6);
  const s = t < 0 ? pre : 1 - t * 0.2;
  return (
    <>
      <div style={abs({left: x - 22, top: y - 22, width: 44, height: 44, borderRadius: 99, background: 'rgba(234,240,246,0.35)', border: '2px solid rgba(255,255,255,0.8)', transform: `scale(${s})`, opacity: t < 0 ? pre : 1 - t})} />
      {t > 0 ? <div style={abs({left: x - 22 - t * 26, top: y - 22 - t * 26, width: 44 + t * 52, height: 44 + t * 52, borderRadius: 99, border: `2px solid ${brand.primary}`, opacity: 1 - t})} /> : null}
    </>
  );
};
