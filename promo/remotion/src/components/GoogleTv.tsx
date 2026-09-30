import React from 'react';
import {brand, rgba} from '../brand';
import {clamp, mix} from '../lib/anim';
import {Launcher} from './Launcher';

// Redrawn Google-TV-style settings (not a capture of TCL's UI): side panel on the right with a list,
// light focus pill, toggles, and the system toast. English labels as on the device. Screen space 1920x1080.

export type GtvItem = {label: string; sub?: string; toggle?: number; icon?: string};
export type GtvPanel = {title: string; items: GtvItem[]};

const PANEL_W = 700;
const ITEM_H = 92;
const LIST_TOP = 200;

const ItemIcon: React.FC<{name?: string; color: string}> = ({name, color}) => {
  const s = {stroke: color, strokeWidth: 2, fill: 'none', strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const};
  const d: Record<string, string> = {
    user: 'M12 4a4 4 0 1 0 0 8a4 4 0 1 0 0-8z M4.5 20.5a7.5 7.5 0 0 1 15 0',
    apps: 'M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z',
    wifi: 'M2.5 9a14 14 0 0 1 19 0 M5.5 12.5a9.5 9.5 0 0 1 13 0 M8.8 16a5 5 0 0 1 6.4 0 M12 19.5h.01',
    display: 'M4 5h16v11H4z M9 20h6',
    remote: 'M9 3h6v18H9z M12 7h.01 M12 11h.01',
    system: 'M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z M12 2v3 M12 19v3 M2 12h3 M19 12h3',
    privacy: 'M12 3l7 3v5.5c0 4.4-3 8.2-7 9.5-4-1.3-7-5.1-7-9.5V6z',
    info: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 11v6 M12 7.5h.01',
    code: 'M8 7l-5 5 5 5 M16 7l5 5-5 5',
    clock: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 7v5l3 2',
    globe: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M3 12h18 M12 3a14 14 0 0 1 0 18 M12 3a14 14 0 0 0 0 18',
    storage: 'M4 6h16v4H4z M4 14h16v4H4z',
    power: 'M12 3v8 M6.3 6.8a8 8 0 1 0 11.4 0',
    dot: 'M12 11.5h.01',
  };
  return (
    <svg width={40} height={40} viewBox="0 0 24 24" style={{flex: 'none'}}>
      <path d={d[name ?? 'dot'] ?? d.dot} {...s} />
    </svg>
  );
};

const Toggle: React.FC<{on: number; focused: boolean}> = ({on, focused}) => (
  <div style={{width: 64, height: 36, borderRadius: 99, background: on > 0.5 ? brand.primary : focused ? '#9AA0A6' : '#5F6368', position: 'relative', flex: 'none', transition: 'none'}}>
    <div style={{position: 'absolute', top: 4, left: mix(4, 32, on), width: 28, height: 28, borderRadius: 99, background: on > 0.5 ? '#E0FFFB' : '#E8EAED', boxShadow: '0 2px 4px rgba(0,0,0,0.3)'}} />
  </div>
);

const Panel: React.FC<{panel: GtvPanel; focus: number; x: number; opacity: number}> = ({panel, focus, x, opacity}) => {
  // focus may be fractional (gliding between items)
  const fi = Math.round(focus);
  return (
    <div style={{position: 'absolute', top: 0, left: x, width: PANEL_W, height: 1080, opacity}}>
      <div style={{position: 'absolute', left: 64, top: 86, fontSize: 46, fontWeight: 400, color: '#E8EAED', fontFamily: "'IBM Plex Sans Arabic', sans-serif", direction: 'ltr'}}>{panel.title}</div>
      {/* focus pill (glides) */}
      <div
        style={{
          position: 'absolute',
          left: 40,
          width: PANEL_W - 80,
          top: LIST_TOP + focus * ITEM_H,
          height: ITEM_H - 8,
          borderRadius: 20,
          background: '#E8EAED',
          boxShadow: '0 8px 24px rgba(0,0,0,0.35)',
        }}
      />
      {panel.items.map((it, i) => {
        const focused = i === fi;
        const c = focused ? '#202124' : '#E8EAED';
        return (
          <div
            key={it.label + i}
            style={{
              position: 'absolute',
              left: 64,
              right: 64,
              top: LIST_TOP + i * ITEM_H,
              height: ITEM_H - 8,
              display: 'flex',
              alignItems: 'center',
              gap: 26,
              direction: 'ltr',
              fontFamily: "'IBM Plex Sans Arabic', sans-serif",
            }}
          >
            {it.icon ? <ItemIcon name={it.icon} color={focused ? '#202124' : '#BDC1C6'} /> : null}
            <div style={{flex: 1, minWidth: 0}}>
              <div style={{fontSize: 31, fontWeight: 500, color: c, whiteSpace: 'nowrap'}}>{it.label}</div>
              {it.sub ? <div style={{fontSize: 23, color: focused ? '#3C4043' : '#9AA0A6', marginTop: 2, whiteSpace: 'nowrap'}}>{it.sub}</div> : null}
            </div>
            {it.toggle !== undefined ? <Toggle on={it.toggle} focused={focused} /> : null}
          </div>
        );
      })}
    </div>
  );
};

/**
 * The settings side panel over a dimmed launcher. `panels` are shown by index with a push transition:
 * `panel` = current index (fractional during the slide), `focus[i]` = focus row per panel.
 */
export const GoogleTvSettings: React.FC<{
  panels: GtvPanel[];
  panel: number;
  focus: number[];
  open: number; // 0..1 panel slide-in from the right
  toast?: {text: string; opacity: number} | null;
  dim?: number;
}> = ({panels, panel, focus, open, toast, dim = 0.62}) => {
  const i0 = Math.floor(panel);
  const t = panel - i0;
  return (
    <div style={{position: 'absolute', left: 0, top: 0, width: 1920, height: 1080, overflow: 'hidden'}}>
      <Launcher />
      <div style={{position: 'absolute', inset: 0, background: `rgba(0,0,0,${dim * clamp(open * 1.3)})`}} />
      <div
        style={{
          position: 'absolute',
          top: 0,
          right: 0,
          width: PANEL_W,
          height: 1080,
          background: '#202124',
          boxShadow: '-30px 0 60px rgba(0,0,0,0.45)',
          transform: `translateX(${(1 - open) * PANEL_W}px)`,
          overflow: 'hidden',
        }}
      >
        <div style={{position: 'absolute', inset: 0}}>
          {panels[i0] ? <Panel panel={panels[i0]} focus={focus[i0] ?? 0} x={-t * 260} opacity={1 - t} /> : null}
          {t > 0 && panels[i0 + 1] ? <Panel panel={panels[i0 + 1]} focus={focus[i0 + 1] ?? 0} x={(1 - t) * 260} opacity={t} /> : null}
        </div>
      </div>
      {toast && toast.opacity > 0 ? (
        <div
          style={{
            position: 'absolute',
            left: (1920 - PANEL_W) / 2,
            bottom: 90,
            transform: `translate(-50%, ${(1 - toast.opacity) * 20}px)`,
            opacity: toast.opacity,
            padding: '22px 40px',
            borderRadius: 18,
            background: '#3C4043',
            color: '#F1F3F4',
            fontSize: 32,
            fontFamily: "'IBM Plex Sans Arabic', sans-serif",
            direction: 'ltr',
            whiteSpace: 'nowrap',
            boxShadow: '0 12px 32px rgba(0,0,0,0.5)',
          }}
        >
          {toast.text}
        </div>
      ) : null}
    </div>
  );
};

/** "Allow USB debugging?" system dialog (redrawn), focus on Allow. */
export const AllowDebugDialog: React.FC<{t: number; checked: number; focus: 'check' | 'allow' | 'cancel'; pressed?: number}> = ({t, checked, focus, pressed = 0}) => (
  // Android TV's prompt: a checkbox and the two buttons «Cancel» / «OK» (INSTALL_FLOW v2 step 6).

  <div style={{position: 'absolute', left: 0, top: 0, width: 1920, height: 1080, overflow: 'hidden'}}>
    <div style={{position: 'absolute', inset: 0, background: `rgba(0,0,0,${0.55 * t})`}} />
    <div
      style={{
        position: 'absolute',
        left: 960 - 520,
        top: 540 - 300,
        width: 1040,
        height: 600,
        borderRadius: 36,
        background: '#2D2E31',
        boxShadow: '0 40px 90px rgba(0,0,0,0.6)',
        opacity: t,
        transform: `scale(${mix(0.94, 1, t)})`,
        direction: 'ltr',
        fontFamily: "'IBM Plex Sans Arabic', sans-serif",
        color: '#E8EAED',
        padding: '64px 72px',
        boxSizing: 'border-box',
      }}
    >
      <div style={{fontSize: 46, fontWeight: 500}}>Allow USB debugging?</div>
      <div style={{fontSize: 27, color: '#BDC1C6', marginTop: 24, lineHeight: 1.5}}>
        The computer's RSA key fingerprint is:
        <br />
        <span style={{fontFamily: brand.mono, fontSize: 24}}>3F:A1:9C:42:7B:E0:55:D8:1A:6C:0F:93:BE:24:71:C6</span>
      </div>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 22,
          marginTop: 40,
          padding: '14px 18px',
          borderRadius: 16,
          background: focus === 'check' ? 'rgba(232,234,237,0.12)' : 'transparent',
        }}
      >
        <div style={{width: 38, height: 38, borderRadius: 8, border: `3px solid ${checked > 0.5 ? brand.primary : '#9AA0A6'}`, background: checked > 0.5 ? brand.primary : 'transparent', display: 'grid', placeItems: 'center'}}>
          {checked > 0.5 ? (
            <svg width={26} height={26} viewBox="0 0 24 24">
              <path d="M5 12.5l4.5 4.5L19 7.5" stroke="#04201D" strokeWidth={3.2} fill="none" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          ) : null}
        </div>
        <div style={{fontSize: 30}}>Always allow from this computer</div>
      </div>
      <div style={{position: 'absolute', right: 72, bottom: 56, display: 'flex', gap: 24}}>
        {(['cancel', 'allow'] as const).map((k) => (
          <div
            key={k}
            style={{
              padding: '18px 56px',
              borderRadius: 999,
              fontSize: 30,
              fontWeight: 600,
              background: focus === k ? (k === 'allow' ? brand.primary : '#E8EAED') : 'rgba(232,234,237,0.08)',
              color: focus === k ? (k === 'allow' ? brand.onPrimary : '#202124') : '#E8EAED',
              transform: `scale(${focus === k ? 1 - pressed * 0.04 : 1})`,
              boxShadow: focus === k && k === 'allow' ? `0 0 30px ${rgba(brand.primary, 0.5)}` : undefined,
            }}
          >
            {k === 'allow' ? 'OK' : 'Cancel'}
          </div>
        ))}
      </div>
    </div>
  </div>
);

/** «Pair device with pairing code» dialog of Android's Wireless debugging (redrawn). */
export const PairCodeDialog: React.FC<{t: number; code: string; address: string}> = ({t, code, address}) => (
  <div style={{position: 'absolute', left: 0, top: 0, width: 1920, height: 1080, overflow: 'hidden'}}>
    <div style={{position: 'absolute', inset: 0, background: `rgba(0,0,0,${0.55 * t})`}} />
    <div
      style={{
        position: 'absolute',
        left: 960 - 500,
        top: 540 - 270,
        width: 1000,
        height: 540,
        borderRadius: 36,
        background: '#2D2E31',
        boxShadow: '0 40px 90px rgba(0,0,0,0.6)',
        opacity: t,
        transform: `scale(${mix(0.94, 1, t)})`,
        direction: 'ltr',
        fontFamily: "'IBM Plex Sans Arabic', sans-serif",
        color: '#E8EAED',
        padding: '60px 72px',
        boxSizing: 'border-box',
      }}
    >
      <div style={{fontSize: 44, fontWeight: 500}}>Pair with device</div>
      <div style={{fontSize: 28, color: '#BDC1C6', marginTop: 34}}>Wi-Fi pairing code</div>
      <div style={{fontSize: 92, fontWeight: 600, letterSpacing: 10, fontFamily: brand.mono, color: '#FFFFFF', marginTop: 6}}>{code}</div>
      <div style={{fontSize: 28, color: '#BDC1C6', marginTop: 26}}>IP address &amp; Port</div>
      <div style={{fontSize: 40, fontFamily: brand.mono, marginTop: 6}}>{address}</div>
    </div>
  </div>
);
