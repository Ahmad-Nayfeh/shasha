import React from 'react';
import {rgba} from '../brand';
import {digits, dir, isEn, startSide, t} from '../lib/lang';
import {REPO_PATH} from '../lib/installer';
import {LogoMark} from './Brand';
import {Icon} from './Icons';
import {BACK_FLIP, C, MH, MW, Screen390, StatusBar, Thumb, thumbBg, Toggle390} from './MobileUi';
import {clamp} from '../lib/anim';

// The parts of the phone around the app: the browser with the release page, Android's own dialogs for installing
// from a new source, the launcher (home screen) with a picture-in-picture window, the switched-off screen. Drawn
// in the system's neutral light/dark Material look, with no Google or maker branding. Right-to-left in the Arabic
// video, left-to-right (the buttons of a dialog at its end, the primary one last) in the English one.

export const MOBILE_APK = 'shasha-mobile-1.2.0-1065.apk';

const L = {bg: '#F6F8FA', card: '#FFFFFF', text: '#1F2328', muted: '#59636E', line: '#D1D9E0', green: '#1A7F37', blue: '#0969DA'};

// ------------------------------------------------------------------------------------------------ browser
export const BrowserPage: React.FC<{scroll?: number; hiAsset?: number; tap?: number; snack?: number}> = ({scroll = 0, hiAsset = 0, tap = 0, snack = 0}) => (
  <Screen390 bg={L.bg} style={{color: L.text}}>
    <StatusBar dark={false} />
    <div style={{position: 'absolute', left: 0, top: 34, width: MW, height: 60, display: 'flex', alignItems: 'center', gap: 10, padding: '0 14px', background: '#FFFFFF', borderBottom: `1px solid ${L.line}`, zIndex: 5}}>
      <div style={{flex: 1, height: 40, borderRadius: 20, background: '#EDF1F5', display: 'flex', alignItems: 'center', gap: 8, padding: '0 14px', direction: 'ltr', fontSize: 14.5, color: L.text}}>
        <Icon name="lock" size={16} color={L.muted} stroke={2.2} />
        <span style={{whiteSpace: 'nowrap', overflow: 'hidden'}}>{REPO_PATH}/releases</span>
      </div>
    </div>
    <div style={{position: 'absolute', left: 0, top: 94, width: MW, bottom: 0, overflow: 'hidden'}}>
      <div style={{transform: `translateY(${-scroll}px)`, padding: '18px 16px'}}>
        <div style={{fontSize: 24, fontWeight: 700}}>Releases</div>
        <div style={{marginTop: 14, background: L.card, border: `1px solid ${L.line}`, borderRadius: 12, padding: 16, direction: 'ltr'}}>
          <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
            <span style={{fontSize: 20, fontWeight: 700}}>v1.2.0-1065</span>
            <span style={{padding: '1px 10px', borderRadius: 99, border: `1px solid ${L.green}`, color: L.green, fontSize: 12.5, fontWeight: 600}}>Latest</span>
          </div>
          <div style={{fontSize: 14, color: L.muted, marginTop: 8, direction: dir, textAlign: startSide}}>{t('شاشة ١٫٢٫٠: تطبيق الجوال، وتحديث بضغطة واحدة.')}</div>
          <div style={{fontSize: 15, fontWeight: 700, margin: '18px 0 8px'}}>Assets</div>
          {[
            [MOBILE_APK, '4.02 MB', true],
            ['shasha-1.2.0-1065.apk', '19 MB', false],
            ['Shasha-Setup.zip', '42.7 MB', false],
          ].map(([name, size, hi]) => (
            <div key={name as string} style={{display: 'flex', alignItems: 'center', gap: 10, height: 50, padding: '0 10px', margin: '0 -10px', borderTop: `1px solid ${L.line}`, borderRadius: hi ? 8 : 0, background: hi ? `rgba(9,105,218,${0.1 * hiAsset})` : 'transparent', boxShadow: hi && tap ? `0 0 0 ${2 * tap}px rgba(9,105,218,${0.5 * tap})` : undefined}}>
              <Icon name="apk" size={20} color={L.blue} stroke={2} />
              <span style={{flex: 1, fontSize: 13, color: L.blue, fontWeight: hi ? 700 : 500, whiteSpace: 'nowrap', overflow: 'hidden'}}>{name as string}</span>
              <span style={{fontSize: 12.5, color: L.muted}}>{size as string}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
    {snack > 0 ? (
      <div style={{position: 'absolute', left: 12, right: 12, bottom: 24, height: 54, borderRadius: 12, background: '#2B3138', color: '#F1F3F5', display: 'flex', alignItems: 'center', padding: '0 16px', gap: 12, opacity: snack, transform: `translateY(${(1 - snack) * 24}px)`, fontSize: 15, fontWeight: 500}}>
        <Icon name="check" size={22} color="#7EE0A0" stroke={3} />
        <span style={{flex: 1}}>{t('اكتمل التنزيل')}</span>
        <span style={{color: '#8AB4FF', fontWeight: 700}}>{t('فتح')}</span>
      </div>
    ) : null}
  </Screen390>
);

// ------------------------------------------------------------------------------------------------ install from a new source
// actions: [label, primary?]; in English the buttons sit at the dialog's end (right), the primary one last.
const SysDialog: React.FC<{t: number; title: string; body: string; actions: Array<[string, boolean?]>; hiAction?: number}> = ({t: p, title, body, actions, hiAction = -1}) => (
  <>
    <div style={{position: 'absolute', left: 0, top: 0, width: MW, height: MH, background: `rgba(0,0,0,${0.55 * p})`}} />
    <div style={{position: 'absolute', left: 30, right: 30, top: 270, opacity: clamp(p * 1.5), transform: `scale(${0.94 + 0.06 * p})`, background: '#2A313A', borderRadius: 28, padding: '26px 24px 18px', direction: dir, color: '#EAF0F6'}}>
      <div style={{fontSize: 22, fontWeight: 700, lineHeight: 1.4}}>{title}</div>
      <div style={{fontSize: 16, color: '#C5CDD6', marginTop: 12, lineHeight: 1.65}}>{body}</div>
      <div style={{display: 'flex', justifyContent: isEn ? 'flex-end' : 'flex-start', gap: 10, marginTop: 22}}>
        {actions.map(([a, strong], i) => (
          <div key={a} style={{padding: '10px 16px', borderRadius: 20, fontSize: 16, fontWeight: 700, color: strong ? '#9CD2FF' : '#C5CDD6', background: i === hiAction ? 'rgba(156,210,255,0.18)' : 'transparent'}}>
            {a}
          </div>
        ))}
      </div>
    </div>
  </>
);

/** "For your security your phone does not allow installing unknown apps from this source" over the browser. */
export const UnknownSourceDialog: React.FC<{t: number; hi?: number}> = ({t: p, hi = 0}) => (
  <SysDialog
    t={p}
    title={t('التثبيت غير مسموح')}
    body={t('لأمانك، لا يسمح هاتفك بتثبيت تطبيقات غير معروفة من هذا المصدر.')}
    actions={isEn ? [[t('إلغاء')], [t('الإعدادات'), true]] : [[t('الإعدادات'), true], [t('إلغاء')]]}
    hiAction={hi > 0.5 ? (isEn ? 1 : 0) : -1}
  />
);

/** The system page "Install unknown apps" with the switch for this source. p = switch state. */
export const UnknownSourcesPage: React.FC<{p: number; glow?: number}> = ({p, glow = 0}) => (
  <Screen390 bg="#14181D">
    <StatusBar />
    <div style={{position: 'absolute', left: 0, top: 34, width: MW, height: 60, display: 'flex', alignItems: 'center', gap: 14, padding: '0 16px', fontSize: 21, fontWeight: 600}}>
      <Icon name="arrowRight" size={26} color="#EAF0F6" stroke={2.2} style={BACK_FLIP} />
      {t('تثبيت التطبيقات غير المعروفة')}
    </div>
    <div style={{position: 'absolute', left: 0, top: 120, width: MW, padding: '0 18px'}}>
      <div style={{display: 'flex', alignItems: 'center', gap: 16, marginBottom: 26}}>
        <div style={{width: 54, height: 54, borderRadius: 14, background: '#2A313A', display: 'grid', placeItems: 'center'}}>
          <Icon name="link" size={28} color="#9CD2FF" stroke={2.1} />
        </div>
        <div style={{fontSize: 19, fontWeight: 600}}>{t('المتصفح')}</div>
      </div>
      <div style={{display: 'flex', alignItems: 'center', gap: 16, padding: '16px 18px', borderRadius: 24, background: glow ? `rgba(46,196,182,${0.1 + 0.1 * glow})` : '#1D232A', boxShadow: glow ? `0 0 0 ${2 * glow}px rgba(46,196,182,${0.55 * glow})` : undefined}}>
        <div style={{flex: 1, fontSize: 18, fontWeight: 600}}>{t('السماح من هذا المصدر')}</div>
        <Toggle390 p={p} />
      </div>
      <div style={{fontSize: 14, color: '#A8B2BD', lineHeight: 1.7, marginTop: 20}}>
        {t('قد يعرّض الهاتف وبياناتك للخطر عند تثبيت تطبيقات من مصادر غير معروفة. اسمح فقط لمصدر تثق به.')}
      </div>
    </div>
  </Screen390>
);

/** The package installer. In English its buttons are Cancel · Install (and Done · Open) at the end, as on Android. */
export const InstallPrompt: React.FC<{state: 'ask' | 'installing' | 'done'; tapInstall?: number; tapOpen?: number}> = ({state, tapInstall = 0, tapOpen = 0}) => {
  const primary = (label: string, glow: number) => (
    <div style={{padding: '14px 28px', borderRadius: 26, fontSize: 17, fontWeight: 700, background: '#9CD2FF', color: '#002C4D', boxShadow: glow ? `0 0 ${24 * glow}px rgba(156,210,255,0.7)` : undefined}}>{label}</div>
  );
  const plain = (label: string) => <div style={{padding: '14px 24px', borderRadius: 26, fontSize: 17, fontWeight: 700, color: '#9CD2FF'}}>{label}</div>;
  return (
    <Screen390 bg="#14181D">
      <StatusBar />
      <div style={{position: 'absolute', left: 0, top: 150, width: MW, textAlign: 'center'}}>
        <div style={{width: 96, height: 96, margin: '0 auto', borderRadius: 26, background: 'linear-gradient(135deg, #1E2632, #0B0F14)', display: 'grid', placeItems: 'center', boxShadow: 'inset 0 0 0 1.5px rgba(255,255,255,0.1)'}}>
          <LogoMark size={86} />
        </div>
        <div style={{fontSize: 26, fontWeight: 700, marginTop: 22}}>{t('شاشة')}</div>
        <div style={{fontSize: 16.5, color: '#C5CDD6', marginTop: 14, padding: '0 36px', lineHeight: 1.7}}>
          {state === 'ask' ? t('هل تريد تثبيت هذا التطبيق؟') : state === 'installing' ? t('جارٍ التثبيت…') : t('تم تثبيت التطبيق.')}
        </div>
        {state === 'installing' ? (
          <div style={{margin: '26px 70px 0', height: 6, borderRadius: 3, background: '#2E3947', overflow: 'hidden'}}>
            <div style={{height: '100%', width: '55%', background: '#9CD2FF', float: startSide}} />
          </div>
        ) : null}
      </div>
      <div style={{position: 'absolute', left: 22, right: 22, bottom: 40, display: 'flex', gap: 12, justifyContent: isEn ? 'flex-end' : 'flex-start'}}>
        {state === 'ask' ? (
          isEn ? (
            <>
              {plain(t('إلغاء'))}
              {primary(t('تثبيت'), tapInstall)}
            </>
          ) : (
            <>
              {primary(t('تثبيت'), tapInstall)}
              {plain(t('إلغاء'))}
            </>
          )
        ) : state === 'done' ? (
          isEn ? (
            <>
              {plain(t('تم'))}
              {primary(t('فتح'), tapOpen)}
            </>
          ) : (
            <>
              {primary(t('فتح'), tapOpen)}
              {plain(t('تم'))}
            </>
          )
        ) : null}
      </div>
    </Screen390>
  );
};

// ------------------------------------------------------------------------------------------------ launcher, PiP, screen off
/** A neutral home screen: wallpaper, plain app tiles, a dock. `children` = floating windows on top. */
export const LauncherPage: React.FC<{children?: React.ReactNode; notif?: number}> = ({children, notif = 0}) => (
  <Screen390 bg="#0E1218">
    <div style={{position: 'absolute', inset: 0, background: 'radial-gradient(90% 60% at 30% 20%, #1D3550, #0E1218 70%)'}} />
    <StatusBar />
    <div style={{position: 'absolute', left: 0, top: 150, width: MW, textAlign: 'center', fontSize: 64, fontWeight: 300}}>{digits('9:41')}</div>
    <div style={{position: 'absolute', left: 0, top: 232, width: MW, textAlign: 'center', fontSize: 16, color: '#B8C4D2'}}>{t('السبت، ٣ أكتوبر')}</div>
    <div style={{position: 'absolute', left: 28, right: 28, top: 330, display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 28, justifyItems: 'center'}}>
      {['#3D6B8F', '#8F5B3D', '#5B8F4E', '#7A4E8F', '#8F7A3D', '#3D8F83', '#8F3D55', '#4E5B8F'].map((c, i) => (
        <div key={i} style={{width: 62, height: 62, borderRadius: 20, background: `linear-gradient(135deg, ${c}, ${rgba(c, 0.55)})`}} />
      ))}
    </div>
    <div style={{position: 'absolute', left: 22, right: 22, bottom: 24, height: 84, borderRadius: 30, background: 'rgba(255,255,255,0.1)', display: 'flex', justifyContent: 'space-around', alignItems: 'center'}}>
      {['#4E8F6B', '#8F6B4E', '#4E6B8F', '#6B4E8F'].map((c, i) => (
        <div key={i} style={{width: 56, height: 56, borderRadius: 18, background: c}} />
      ))}
    </div>
    {notif > 0 ? (
      <div style={{position: 'absolute', left: 14, right: 14, top: 40, borderRadius: 26, background: 'rgba(30,38,50,0.97)', padding: '14px 16px', opacity: clamp(notif * 1.5), transform: `translateY(${(1 - notif) * -50}px)`, display: 'flex', alignItems: 'center', gap: 14, boxShadow: '0 20px 40px rgba(0,0,0,0.5)'}}>
        <Thumb i={0} w={64} h={64} radius={12} />
        <div style={{flex: 1}}>
          <div style={{fontSize: 12.5, color: C.muted}}>{t('شاشة · جارٍ التشغيل')}</div>
          <div style={{fontSize: 15, fontWeight: 700, marginTop: 2, lineHeight: 1.35}}>{t('رحلة إلى أعماق المحيط')}</div>
        </div>
        <div style={{width: 44, height: 44, borderRadius: 999, background: C.primary, display: 'grid', placeItems: 'center'}}>
          <Icon name="pause" size={24} color={C.onPrimary} stroke={2.4} />
        </div>
      </div>
    ) : null}
    {children}
  </Screen390>
);

/** The switched-off screen: black glass with a faint reflection. */
export const ScreenOff: React.FC = () => (
  <Screen390 bg="#020304">
    <div style={{position: 'absolute', inset: 0, background: 'linear-gradient(125deg, rgba(255,255,255,0.05), rgba(255,255,255,0) 40%)'}} />
  </Screen390>
);

/** A notes-like app the user works in while the video floats above it. */
export const NotesPage: React.FC<{scroll?: number}> = ({scroll = 0}) => (
  <Screen390 bg="#F4F1EA" style={{color: '#2B2620'}}>
    <StatusBar dark={false} />
    <div style={{position: 'absolute', left: 0, top: 34, width: MW, height: 56, display: 'flex', alignItems: 'center', padding: '0 18px', fontSize: 22, fontWeight: 700}}>{t('قائمة التسوق')}</div>
    <div style={{position: 'absolute', left: 0, top: 100, width: MW, overflow: 'hidden', bottom: 0}}>
      <div style={{transform: `translateY(${-scroll}px)`}}>
        {[t('خبز'), t('حليب'), t('بيض'), t('طماطم'), t('زيتون'), t('جبنة'), t('تمر'), t('شاي'), t('أرز'), t('عدس'), t('ليمون'), t('نعناع')].map((s, i) => (
          <div key={s} style={{display: 'flex', alignItems: 'center', gap: 14, height: 64, padding: '0 22px', borderBottom: '1px solid #E4DFD3', fontSize: 19}}>
            <div style={{width: 24, height: 24, borderRadius: 7, border: '2px solid #A59E8F', background: i % 3 === 0 ? '#8FB39A' : 'transparent'}} />
            {s}
          </div>
        ))}
      </div>
    </div>
  </Screen390>
);

/** The picture-in-picture window (phone space): w x w*9/16, rounded, with a thin frame. */
export const PipWindow: React.FC<{x: number; y: number; w: number; seed?: number; opacity?: number}> = ({x, y, w, seed = 0, opacity = 1}) => (
  <div style={{position: 'absolute', left: x, top: y, width: w, height: (w * 9) / 16, borderRadius: 14, overflow: 'hidden', background: thumbBg(seed), opacity, boxShadow: '0 16px 36px rgba(0,0,0,0.6), 0 0 0 1.5px rgba(255,255,255,0.18)'}}>
    <div style={{position: 'absolute', left: '18%', top: '52%', width: '64%', height: '40%', borderRadius: '50%', background: 'radial-gradient(50% 50% at 50% 50%, rgba(255,255,255,0.12), transparent 70%)'}} />
    <div style={{position: 'absolute', left: 6, top: 6, width: 26, height: 26, borderRadius: 999, background: 'rgba(0,0,0,0.55)', display: 'grid', placeItems: 'center'}}>
      <Icon name="x" size={14} color="#fff" stroke={2.6} />
    </div>
  </div>
);
