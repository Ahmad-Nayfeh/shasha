import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {clamp, easeInOut, easeOut, mix, tween} from '../lib/anim';
import {KIT_DATE, KIT_TAG, KitFile, KitKind} from '../lib/installer';
import {dir} from '../lib/lang';
import {isolateArabic, isolateLatin} from './Laptop';

// Redrawn, generic desktop UI for the installation steps (no vendor logos): a Windows-like and a macOS-like
// desktop, windows, a browser showing the repository page, a file manager, context menus, the SmartScreen and
// Gatekeeper prompts, the installer's console window and a mouse pointer. Everything lives in a 1280x800
// "screen space" (16:10) that the Laptop scales down.

export const DW = 1280;
export const DH = 800;
export type OS = 'win' | 'mac';
const UI = "'Segoe UI', 'IBM Plex Sans Arabic', 'IBM Plex Mono', sans-serif";
const ARABIC = /[؀-ۿ]/;

/** Mixed Arabic/Latin label (file names like «Windows - ثبّت شاشة.bat») shown LTR with the Arabic run isolated. */
export const Mixed: React.FC<{text: string; style?: React.CSSProperties}> = ({text, style}) => {
  // a file name such as «Windows - ثبّت شاشة.bat» or «اقرأني - طريقة التثبيت.pdf»: the span from the first to the
  // last Arabic letter is one RTL isolate, the rest (prefix, extension) stays LTR - as a file manager shows it.
  const m = text.match(/[\u0600-\u06FF][\s\S]*[\u0600-\u06FF]|[\u0600-\u06FF]/);
  if (!m || m.index === undefined) return <span style={{direction: 'ltr', unicodeBidi: 'isolate', ...style}}>{text}</span>;
  return (
    <span style={{direction: 'ltr', unicodeBidi: 'isolate', ...style}}>
      {text.slice(0, m.index)}
      <bdi dir="rtl" style={{unicodeBidi: 'isolate', direction: 'rtl'}}>
        {m[0]}
      </bdi>
      {text.slice(m.index + m[0].length)}
    </span>
  );
};

// ------------------------------------------------------------------------------------------------ desktop
export const Desktop: React.FC<{os: OS; children?: React.ReactNode; clock?: string; bare?: boolean; app?: string}> = ({os, children, clock = '9:41', bare = false, app = 'Finder'}) => (
  <div style={{position: 'absolute', left: 0, top: 0, width: DW, height: DH, overflow: 'hidden', fontFamily: UI}}>
    <div
      style={{
        position: 'absolute',
        inset: 0,
        background:
          os === 'win'
            ? 'radial-gradient(120% 90% at 70% 20%, #1D4A7A 0%, #10233F 45%, #0A1426 100%)'
            : 'radial-gradient(120% 90% at 30% 10%, #3B3F63 0%, #1E2340 45%, #0E1122 100%)',
      }}
    />
    {os === 'mac' && !bare ? (
      <div style={{position: 'absolute', left: 0, right: 0, top: 0, height: 34, background: 'rgba(20,22,34,0.55)', backdropFilter: 'blur(8px)', display: 'flex', alignItems: 'center', gap: 26, padding: '0 22px', color: '#E8EAF2', fontSize: 17, direction: 'ltr'}}>
        <div style={{width: 16, height: 16, borderRadius: 4, background: 'rgba(232,234,242,0.85)'}} />
        <b>{app}</b>
        <span>File</span>
        <span>Edit</span>
        <span>View</span>
        <span>{app === 'Finder' ? 'Go' : 'Window'}</span>
        <span style={{marginLeft: 'auto'}}>{clock}</span>
      </div>
    ) : null}
    {children}
    {os === 'win' && !bare ? (
      <div style={{position: 'absolute', left: 0, right: 0, bottom: 0, height: 60, background: 'rgba(16,22,34,0.86)', borderTop: '1px solid rgba(255,255,255,0.06)', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 14, direction: 'ltr'}}>
        {['#4C8DF6', '#E9B949', '#3F9C6B', '#7A7F8C', '#5B6BF0'].map((c, i) => (
          <div key={i} style={{width: 38, height: 38, borderRadius: 8, background: i === 0 ? 'rgba(255,255,255,0.12)' : 'transparent', display: 'grid', placeItems: 'center'}}>
            <div style={{width: 24, height: 24, borderRadius: i === 0 ? 4 : 6, background: c, opacity: 0.9}} />
          </div>
        ))}
        <div style={{position: 'absolute', right: 20, color: '#E8EAF2', fontSize: 16, textAlign: 'right', lineHeight: 1.25}}>
          {clock}
          <br />
          {KIT_DATE}
        </div>
      </div>
    ) : null}
  </div>
);

// ------------------------------------------------------------------------------------------------ window
export const Window: React.FC<{
  os: OS;
  x: number;
  y: number;
  w: number;
  h: number;
  title?: string;
  children?: React.ReactNode;
  t?: number; // entrance 0..1
  dark?: boolean;
  z?: number;
}> = ({os, x, y, w, h, title, children, t = 1, dark = false, z}) => {
  const barH = os === 'win' ? 44 : 40;
  return (
    <div
      style={{
        position: 'absolute',
        left: x,
        top: y,
        width: w,
        height: h,
        borderRadius: os === 'win' ? 10 : 14,
        overflow: 'hidden',
        background: dark ? '#0C0F14' : os === 'win' ? '#F3F4F7' : '#F5F5F7',
        boxShadow: '0 30px 80px rgba(0,0,0,0.5), 0 0 0 1px rgba(255,255,255,0.08)',
        opacity: clamp(t * 1.4),
        transform: `translateY(${(1 - t) * 24}px) scale(${mix(0.97, 1, t)})`,
        direction: 'ltr',
        fontFamily: UI,
        zIndex: z,
      }}
    >
      <div
        style={{
          height: barH,
          display: 'flex',
          alignItems: 'center',
          padding: '0 14px',
          gap: 10,
          background: dark ? '#1A1F27' : os === 'win' ? '#E6E8EE' : '#E7E7EA',
          color: dark ? '#D8DEE8' : '#2A2D34',
          fontSize: 16,
          borderBottom: `1px solid ${dark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.08)'}`,
        }}
      >
        {os === 'mac' ? (
          <>
            {['#FF5F57', '#FEBC2E', '#28C840'].map((c) => (
              <div key={c} style={{width: 14, height: 14, borderRadius: 99, background: c}} />
            ))}
            <div style={{flex: 1, textAlign: 'center', fontWeight: 600, marginRight: 56}}>{title ? <Mixed text={title} /> : null}</div>
          </>
        ) : (
          <>
            <div style={{width: 18, height: 18, borderRadius: 4, background: dark ? '#3C4658' : '#C9CFDA'}} />
            <div style={{flex: 1, fontWeight: 500, whiteSpace: 'nowrap', overflow: 'hidden'}}>{title ? <Mixed text={title} /> : null}</div>
            {['—', '▢', '✕'].map((g) => (
              <div key={g} style={{width: 46, textAlign: 'center', fontSize: 15, opacity: 0.75}}>
                {g}
              </div>
            ))}
          </>
        )}
      </div>
      <div style={{position: 'absolute', left: 0, right: 0, top: barH, bottom: 0}}>{children}</div>
    </div>
  );
};

// ------------------------------------------------------------------------------------------------ icons
export const FileIcon: React.FC<{kind: KitKind; size?: number}> = ({kind, size = 34}) => {
  const c: Record<KitKind, string> = {folder: '#F2C14E', bat: '#5B6B82', command: '#4B4F5C', sh: '#4B4F5C', pdf: '#E0564F', apk: '#35B37E', zip: '#C99A2E'};
  if (kind === 'folder')
    return (
      <svg width={size} height={size} viewBox="0 0 32 32">
        <path d="M3 8a3 3 0 0 1 3-3h6l3 3h11a3 3 0 0 1 3 3v13a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3z" fill="#E8B23A" />
        <path d="M3 12h26v12a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3z" fill="#F6CD5B" />
      </svg>
    );
  const label: Record<KitKind, string> = {folder: '', bat: '>_', command: '>_', sh: '$', pdf: 'PDF', apk: '', zip: 'ZIP'};
  return (
    <svg width={size} height={size} viewBox="0 0 32 32">
      <path d="M7 3h13l6 6v18a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z" fill="#FFFFFF" stroke="#AEB6C4" strokeWidth={1.2} />
      <path d="M20 3v6h6" fill="#E4E8EF" stroke="#AEB6C4" strokeWidth={1.2} />
      <rect x={6} y={15} width={20} height={10} rx={2.5} fill={c[kind]} />
      {kind === 'apk' ? (
        <g fill="#fff">
          <circle cx={12.5} cy={20} r={1.4} />
          <circle cx={19.5} cy={20} r={1.4} />
        </g>
      ) : (
        <text x={16} y={22.6} textAnchor="middle" fontSize={7.2} fontWeight={700} fill="#fff" fontFamily="IBM Plex Mono, monospace">
          {label[kind]}
        </text>
      )}
    </svg>
  );
};

// ------------------------------------------------------------------------------------------------ file manager
export const FileManager: React.FC<{
  os: OS;
  path: string[];
  files: Array<KitFile & {highlight?: number}>;
  selected?: number; // index, -1 none
  rowH?: number;
  labels?: number; // 0..1: show the descriptive labels (in the video's language) next to the files
}> = ({os, path, files, selected = -1, rowH = 56, labels = 0}) => (
  <div style={{position: 'absolute', inset: 0, background: os === 'win' ? '#FAFBFC' : '#FFFFFF', color: '#1F2329', direction: 'ltr', fontFamily: UI}}>
    <div style={{height: 52, display: 'flex', alignItems: 'center', gap: 10, padding: '0 20px', borderBottom: '1px solid #E3E6EB', fontSize: 17, color: '#4A5160'}}>
      <span style={{fontSize: 20, opacity: 0.55}}>‹ ›</span>
      {path.map((p, i) => (
        <React.Fragment key={i}>
          {i ? <span style={{opacity: 0.45}}>›</span> : null}
          <span style={{fontWeight: i === path.length - 1 ? 600 : 400, color: i === path.length - 1 ? '#1F2329' : '#5A6272'}}>
            <Mixed text={p} />
          </span>
        </React.Fragment>
      ))}
    </div>
    <div style={{display: 'flex', padding: '10px 20px 8px 20px', fontSize: 14, color: '#7A8292', borderBottom: '1px solid #EEF0F3'}}>
      <span style={{flex: 1, paddingLeft: 48}}>Name</span>
      <span style={{width: 150}}>{os === 'win' ? 'Date modified' : 'Date Modified'}</span>
      <span style={{width: 90, textAlign: 'right'}}>Size</span>
    </div>
    {files.map((f, i) => {
      const sel = i === selected;
      const hl = f.highlight ?? 0;
      return (
        <div
          key={f.name}
          style={{
            height: rowH,
            display: 'flex',
            alignItems: 'center',
            padding: '0 20px',
            fontSize: 19,
            background: sel ? (os === 'win' ? '#CFE3FC' : '#2F6FEB') : hl > 0 ? `rgba(46,196,182,${0.14 * hl})` : 'transparent',
            color: sel && os === 'mac' ? '#fff' : '#1F2329',
            borderRadius: 6,
            margin: '2px 8px',
            boxShadow: hl > 0 ? `inset 0 0 0 ${2 * hl}px rgba(46,196,182,0.8)` : undefined,
          }}
        >
          <FileIcon kind={f.kind} size={34} />
          <span style={{flex: 1, marginLeft: 14, whiteSpace: 'nowrap', display: 'flex', alignItems: 'center', gap: 14}}>
            <Mixed text={f.name} />
            {f.label && labels > 0 ? (
              <span
                style={{
                  direction: dir,
                  unicodeBidi: 'isolate',
                  fontFamily: brand.font,
                  fontSize: 17,
                  fontWeight: 600,
                  color: brand.onPrimary,
                  background: brand.primary,
                  borderRadius: 99,
                  padding: '2px 14px 4px',
                  opacity: labels,
                  transform: `translateX(${(1 - labels) * 16}px)`,
                }}
              >
                {f.label}
              </span>
            ) : null}
          </span>
          <span style={{width: 150, fontSize: 15, color: sel && os === 'mac' ? '#E8EEFF' : '#7A8292'}}>{f.date}</span>
          <span style={{width: 90, textAlign: 'right', fontSize: 15, color: sel && os === 'mac' ? '#E8EEFF' : '#7A8292'}}>{f.size}</span>
        </div>
      );
    })}
  </div>
);

// ------------------------------------------------------------------------------------------------ context menu
export const ContextMenu: React.FC<{os: OS; x: number; y: number; items: string[]; active?: number; t?: number}> = ({os, x, y, items, active = -1, t = 1}) => (
  <div
    style={{
      position: 'absolute',
      left: x,
      top: y,
      minWidth: 260,
      padding: '8px 6px',
      borderRadius: os === 'win' ? 10 : 9,
      background: os === 'win' ? 'rgba(249,250,252,0.98)' : 'rgba(236,236,240,0.97)',
      boxShadow: '0 18px 44px rgba(0,0,0,0.35), 0 0 0 1px rgba(0,0,0,0.08)',
      opacity: clamp(t * 1.5),
      transform: `scale(${mix(0.96, 1, t)})`,
      transformOrigin: 'left top',
      direction: 'ltr',
      fontFamily: UI,
      fontSize: 18,
      color: '#1F2329',
      zIndex: 5,
    }}
  >
    {items.map((it, i) =>
      it === '-' ? (
        <div key={i} style={{height: 1, background: 'rgba(0,0,0,0.1)', margin: '6px 10px'}} />
      ) : (
        <div
          key={i}
          style={{
            padding: '8px 18px',
            borderRadius: 6,
            background: i === active ? (os === 'win' ? '#DCE8FB' : '#2F6FEB') : 'transparent',
            color: i === active && os === 'mac' ? '#fff' : '#1F2329',
            fontWeight: i === active ? 600 : 400,
            whiteSpace: 'nowrap',
          }}
        >
          {it}
        </div>
      ),
    )}
  </div>
);

// ------------------------------------------------------------------------------------------------ pointer
export type PointerKey = {f: number; x: number; y: number};
/** Mouse pointer gliding through keyframes; `clicks` = frames of a click (double-click = two close frames). */
export const Pointer: React.FC<{keys: PointerKey[]; clicks?: number[]; right?: number[]; from?: number; until?: number}> = ({keys, clicks = [], right = [], from = 0, until}) => {
  const f = useCurrentFrame();
  if (f < from || (until !== undefined && f > until) || !keys.length) return null;
  let x = keys[0].x;
  let y = keys[0].y;
  for (let i = 0; i < keys.length - 1; i++) {
    const a = keys[i];
    const b = keys[i + 1];
    if (f >= a.f && f <= b.f) {
      const t = easeInOut(clamp((f - a.f) / Math.max(1, b.f - a.f)));
      x = mix(a.x, b.x, t);
      y = mix(a.y, b.y, t);
    } else if (f > b.f) {
      x = b.x;
      y = b.y;
    }
  }
  const all = [...clicks.map((c) => ({c, r: false})), ...right.map((c) => ({c, r: true}))];
  const press = all.reduce((m, {c}) => Math.max(m, f >= c && f < c + 6 ? Math.sin(((f - c) / 6) * Math.PI) : 0), 0);
  return (
    <>
      {all.map(({c, r}, i) => {
        if (f < c || f > c + 18) return null;
        const t = tween(f, c, 18, easeOut);
        return (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: x - 30 * t,
              top: y - 30 * t,
              width: 60 * t,
              height: 60 * t,
              borderRadius: 99,
              border: `3px solid ${r ? brand.amber : brand.primary}`,
              opacity: 1 - t,
              zIndex: 20,
            }}
          />
        );
      })}
      <svg width={40} height={48} viewBox="0 0 20 24" style={{position: 'absolute', left: x - 3, top: y - 2, zIndex: 21, transform: `scale(${1 - press * 0.12})`, transformOrigin: '3px 2px', filter: 'drop-shadow(0 3px 5px rgba(0,0,0,0.45))'}}>
        <path d="M2 1.5 L2 19 L6.4 14.8 L9.3 21.5 L12.4 20.1 L9.6 13.6 L15.6 13.4 Z" fill="#fff" stroke="#111" strokeWidth={1.3} strokeLinejoin="round" />
      </svg>
    </>
  );
};

// ------------------------------------------------------------------------------------------------ browser
export const Browser: React.FC<{url: string; children?: React.ReactNode}> = ({url, children}) => (
  <div style={{position: 'absolute', inset: 0, background: '#0F1318', direction: 'ltr', fontFamily: UI}}>
    <div style={{height: 54, display: 'flex', alignItems: 'center', gap: 14, padding: '0 18px', background: '#1B2028', borderBottom: '1px solid rgba(255,255,255,0.06)'}}>
      <span style={{color: '#8C96A8', fontSize: 20}}>‹ › ⟳</span>
      <div style={{flex: 1, height: 34, borderRadius: 17, background: '#0F1318', display: 'flex', alignItems: 'center', padding: '0 18px', gap: 10, color: '#C9D1DE', fontSize: 17}}>
        <span style={{fontSize: 13, color: '#6FD3C8'}}>🔒</span>
        {url}
      </div>
    </div>
    <div style={{position: 'absolute', left: 0, right: 0, top: 54, bottom: 0}}>{children}</div>
  </div>
);

export type RepoRow = {name: string; kind: 'dir' | 'file' | 'zip'; msg: string; when: string; highlight?: number};
/** A generic code-hosting repository page (text only, no logos): header, branch bar, file table. */
export const RepoPage: React.FC<{owner: string; repo: string; rows: RepoRow[]; hover?: number}> = ({owner, repo, rows, hover = -1}) => (
  <div style={{position: 'absolute', inset: 0, background: '#0F1318', color: '#E6EDF3', fontFamily: UI}}>
    <div style={{padding: '22px 34px 14px', display: 'flex', alignItems: 'center', gap: 12, fontSize: 24}}>
      <span style={{color: '#7DB7FF'}}>{owner}</span>
      <span style={{color: '#7D8590'}}>/</span>
      <b style={{color: '#7DB7FF'}}>{repo}</b>
      <span style={{fontSize: 14, border: '1px solid #3D444D', borderRadius: 99, padding: '2px 10px', color: '#9198A1'}}>Private</span>
    </div>
    <div style={{display: 'flex', gap: 26, padding: '0 34px', fontSize: 16, color: '#9198A1', borderBottom: '1px solid #262C36'}}>
      {['Code', 'Issues', 'Pull requests', 'Actions', 'Releases'].map((t, i) => (
        <div key={t} style={{padding: '10px 2px 12px', borderBottom: i === 0 ? '2px solid #F78166' : '2px solid transparent', color: i === 0 ? '#E6EDF3' : '#9198A1'}}>
          {t}
        </div>
      ))}
    </div>
    <div style={{margin: '18px 34px', border: '1px solid #30363D', borderRadius: 10, overflow: 'hidden'}}>
      <div style={{background: '#161B22', padding: '12px 18px', fontSize: 15, color: '#9198A1', display: 'flex', gap: 12}}>
        <b style={{color: '#E6EDF3'}}>Ahmad-Nayfeh</b> Installer kit {KIT_TAG} <span style={{marginLeft: 'auto'}}>today</span>
      </div>
      {rows.map((r, i) => (
        <div
          key={r.name}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 14,
            padding: '0 18px',
            height: 46,
            fontSize: 17,
            borderTop: '1px solid #21262D',
            background: i === hover ? '#1C2430' : r.highlight ? `rgba(46,196,182,${0.16 * r.highlight})` : 'transparent',
            boxShadow: r.highlight ? `inset 0 0 0 ${2 * r.highlight}px rgba(46,196,182,0.85)` : undefined,
          }}
        >
          <span style={{width: 20, color: r.kind === 'dir' ? '#7DB7FF' : '#9198A1'}}>{r.kind === 'dir' ? '▰' : '▭'}</span>
          <span style={{width: 330, color: r.kind === 'zip' ? '#E6EDF3' : '#C9D1D9', fontWeight: r.kind === 'zip' ? 600 : 400, textDecoration: i === hover ? 'underline' : undefined}}>{r.name}</span>
          <span style={{flex: 1, color: '#7D8590', fontSize: 15}}>{r.msg}</span>
          <span style={{color: '#7D8590', fontSize: 15}}>{r.when}</span>
        </div>
      ))}
    </div>
  </div>
);

/** The page of one binary file: name, size and the download button (focus ring drawn by the caller). */
export const RepoFilePage: React.FC<{owner: string; repo: string; file: string; size: string; pressed?: number; done?: number}> = ({owner, repo, file, size, pressed = 0, done = 0}) => (
  <div style={{position: 'absolute', inset: 0, background: '#0F1318', color: '#E6EDF3', fontFamily: UI}}>
    <div style={{padding: '22px 34px 14px', fontSize: 22, color: '#9198A1'}}>
      <span style={{color: '#7DB7FF'}}>{repo}</span> / <b style={{color: '#E6EDF3'}}>{file}</b>
    </div>
    <div style={{margin: '10px 34px', border: '1px solid #30363D', borderRadius: 10, overflow: 'hidden'}}>
      <div style={{background: '#161B22', padding: '12px 18px', display: 'flex', alignItems: 'center', gap: 14, fontSize: 16, color: '#9198A1'}}>
        <span>{size}</span>
        <div style={{marginLeft: 'auto', display: 'flex', gap: 10}}>
          <div style={{padding: '6px 14px', border: '1px solid #3D444D', borderRadius: 8}}>Raw</div>
          <div
            style={{
              width: 46,
              height: 36,
              border: `1px solid ${pressed > 0 ? brand.primary : '#3D444D'}`,
              borderRadius: 8,
              display: 'grid',
              placeItems: 'center',
              background: pressed > 0 ? rgba(brand.primary, 0.25 * pressed) : 'transparent',
            }}
          >
            <svg width={22} height={22} viewBox="0 0 24 24">
              <path d="M12 4v11 M7 10l5 5 5-5 M5 19h14" stroke="#E6EDF3" strokeWidth={2.2} fill="none" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </div>
        </div>
      </div>
      <div style={{height: 300, display: 'grid', placeItems: 'center', color: '#7D8590', fontSize: 18}}>View raw · Binary file not shown.</div>
    </div>
    {done > 0 ? (
      <div
        style={{
          position: 'absolute',
          right: 30,
          top: 14,
          width: 360,
          padding: '14px 18px',
          borderRadius: 12,
          background: '#232A35',
          boxShadow: '0 16px 40px rgba(0,0,0,0.5)',
          display: 'flex',
          alignItems: 'center',
          gap: 14,
          opacity: clamp(done * 2),
          transform: `translateY(${(1 - clamp(done * 2)) * -10}px)`,
        }}
      >
        <FileIcon kind="zip" size={36} />
        <div style={{flex: 1}}>
          <div style={{fontSize: 16, color: '#E6EDF3'}}>{file}</div>
          <div style={{height: 6, borderRadius: 3, background: 'rgba(255,255,255,0.12)', marginTop: 8, overflow: 'hidden'}}>
            <div style={{width: `${clamp(done) * 100}%`, height: '100%', background: brand.primary}} />
          </div>
        </div>
      </div>
    ) : null}
  </div>
);

// ------------------------------------------------------------------------------------------------ prompts
/** Microsoft Defender SmartScreen prompt (redrawn): «More info» reveals the publisher and «Run anyway». */
export const SmartScreen: React.FC<{t: number; more: number; file: string; runPressed?: number}> = ({t, more, file, runPressed = 0}) => (
  <div
    style={{
      position: 'absolute',
      left: 190,
      top: 90,
      width: 900,
      height: 560,
      background: '#1F5FA8',
      color: '#fff',
      boxShadow: '0 30px 80px rgba(0,0,0,0.55)',
      opacity: clamp(t * 1.5),
      transform: `scale(${mix(0.96, 1, t)})`,
      direction: 'ltr',
      fontFamily: UI,
      padding: '46px 56px',
      boxSizing: 'border-box',
      zIndex: 8,
    }}
  >
    <div style={{fontSize: 40, fontWeight: 300}}>Windows protected your PC</div>
    <div style={{fontSize: 20, marginTop: 24, lineHeight: 1.5, opacity: 0.95}}>
      Microsoft Defender SmartScreen prevented an unrecognized app from starting. Running this app might put your PC at risk.
    </div>
    <div style={{fontSize: 20, marginTop: 16, textDecoration: 'underline', fontWeight: 600, opacity: 1 - more}}>More info</div>
    <div style={{marginTop: -26, opacity: more, fontSize: 19, lineHeight: 1.7}}>
      <div>
        App: <Mixed text={file} />
      </div>
      <div>Publisher: Unknown publisher</div>
    </div>
    <div style={{position: 'absolute', right: 56, bottom: 40, display: 'flex', gap: 14}}>
      {more > 0.5 ? (
        <div style={{padding: '12px 30px', border: '2px solid #fff', fontSize: 19, background: runPressed > 0 ? `rgba(255,255,255,${0.25 * runPressed})` : 'transparent'}}>Run anyway</div>
      ) : null}
      <div style={{padding: '12px 30px', background: '#fff', color: '#1F5FA8', fontSize: 19, fontWeight: 600}}>Don't run</div>
    </div>
  </div>
);

/** Where the «Done» button of the macOS 15 prompt is (desktop space), for the pointer. */
export const MAC_NOT_OPENED = {x: 390, y: 140, w: 500, h: 340, doneX: 752, doneY: 140 + 340 - 22 - 19};
/**
 * The macOS 15 (Sequoia) prompt after a double-click on a script downloaded from the internet (redrawn): the file is
 * «Not Opened»; the way on is System Settings → Privacy & Security → «Open Anyway».
 */
export const MacNotOpened: React.FC<{t: number; file: string; pressed?: number}> = ({t, file, pressed = 0}) => (
  <div
    style={{
      position: 'absolute',
      left: MAC_NOT_OPENED.x,
      top: MAC_NOT_OPENED.y,
      width: MAC_NOT_OPENED.w,
      height: MAC_NOT_OPENED.h,
      padding: '28px 30px 0',
      boxSizing: 'border-box',
      borderRadius: 18,
      background: 'rgba(238,238,242,0.98)',
      boxShadow: '0 30px 80px rgba(0,0,0,0.5)',
      opacity: clamp(t * 1.5),
      transform: `scale(${mix(0.95, 1, t)})`,
      direction: 'ltr',
      fontFamily: UI,
      color: '#1F2329',
      textAlign: 'center',
      zIndex: 8,
    }}
  >
    <div style={{position: 'relative', width: 64, height: 64, margin: '0 auto 16px'}}>
      <div style={{width: 64, height: 64, borderRadius: 14, background: '#4B4F5C', display: 'grid', placeItems: 'center', color: '#fff', fontFamily: 'IBM Plex Mono, monospace', fontWeight: 700}}>{'>_'}</div>
      <div style={{position: 'absolute', right: -10, bottom: -10, width: 30, height: 30, borderRadius: 99, background: '#E5484D', border: '3px solid #EEEEF2', display: 'grid', placeItems: 'center', color: '#fff', fontWeight: 800, fontSize: 19}}>!</div>
    </div>
    <div style={{fontSize: 18, fontWeight: 700, lineHeight: 1.35}}>
      “<Mixed text={file} />” Not Opened
    </div>
    <div style={{fontSize: 15, color: '#555B66', marginTop: 10, lineHeight: 1.4}}>
      Apple could not verify “<Mixed text={file} />” is free of malware that may harm your Mac or compromise your privacy.
    </div>
    <div style={{position: 'absolute', left: 30, right: 30, bottom: 22, display: 'flex', gap: 10}}>
      <div style={{flex: 1, height: 38, display: 'grid', placeItems: 'center', borderRadius: 8, background: '#fff', border: '1px solid rgba(0,0,0,0.12)', fontSize: 16}}>Move to Trash</div>
      <div style={{flex: 1, height: 38, display: 'grid', placeItems: 'center', borderRadius: 8, background: pressed > 0 ? '#1E5BD8' : '#2F6FEB', color: '#fff', fontSize: 16, fontWeight: 600}}>Done</div>
    </div>
  </div>
);

/** System Settings of macOS 15 (redrawn): the window, its sidebar rows and the «Open Anyway» button (desktop space). */
export const MAC_SETTINGS = {x: 140, y: 70, w: 1000, h: 640, side: 270, bar: 40, rowH: 44, top: 76, privacyRow: 5};
export const MAC_SIDEBAR = ['Wi-Fi', 'Bluetooth', 'Network', 'Notifications', 'Sound', 'Privacy & Security', 'Desktop & Dock', 'Displays'];
/** Centre of a sidebar row. */
export const macSidebarY = (i: number) => MAC_SETTINGS.y + MAC_SETTINGS.bar + MAC_SETTINGS.top + i * MAC_SETTINGS.rowH + MAC_SETTINGS.rowH / 2;
export const MAC_OPEN_ANYWAY = {x: MAC_SETTINGS.x + MAC_SETTINGS.w - 36 - 22 - 75, y: MAC_SETTINGS.y + MAC_SETTINGS.bar + 312 + 65};

export const MacSettings: React.FC<{t: number; go: number; file: string; pressed?: number; glow?: number}> = ({t, go, file, pressed = 0, glow = 0}) => {
  const S = MAC_SETTINGS;
  const dots = ['#4C8DF6', '#5B6BF0', '#3F9C6B', '#E5484D', '#E9B949', '#2F6FEB', '#7A7F8C', '#4C8DF6'];
  const sel = go > 0.4 ? S.privacyRow : 0;
  const card = {background: '#fff', borderRadius: 12, border: '1px solid rgba(0,0,0,0.08)'} as const;
  return (
    <Window os="mac" x={S.x} y={S.y} w={S.w} h={S.h} title="System Settings" t={t}>
      <div style={{position: 'absolute', inset: 0, display: 'flex', background: '#F5F5F7', color: '#1F2329', direction: 'ltr', fontFamily: UI}}>
        <div style={{width: S.side, background: '#E8E8ED', borderRight: '1px solid rgba(0,0,0,0.08)', boxSizing: 'border-box', padding: '14px 12px'}}>
          <div style={{height: 34, borderRadius: 9, background: 'rgba(0,0,0,0.07)', color: '#7A8292', fontSize: 16, display: 'flex', alignItems: 'center', padding: '0 12px', marginBottom: S.top - 34 - 14}}>Search</div>
          {MAC_SIDEBAR.map((name, i) => (
            <div key={name} style={{height: S.rowH - 4, margin: '2px 0', borderRadius: 9, display: 'flex', alignItems: 'center', gap: 12, padding: '0 10px', fontSize: 17, background: i === sel ? '#2F6FEB' : 'transparent', color: i === sel ? '#fff' : '#1F2329'}}>
              <div style={{width: 24, height: 24, borderRadius: 6, background: dots[i]}} />
              {name}
            </div>
          ))}
        </div>
        <div style={{position: 'relative', flex: 1}}>
          <div style={{position: 'absolute', inset: 0, opacity: 1 - go}}>
            <div style={{position: 'absolute', left: 36, top: 20, fontSize: 28, fontWeight: 700}}>Wi-Fi</div>
            <div style={{...card, position: 'absolute', left: 36, right: 36, top: 76, height: 130, padding: '0 22px', display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 14, fontSize: 18}}>
              <div style={{display: 'flex', alignItems: 'center'}}>
                <span style={{flex: 1}}>Wi-Fi</span>
                <div style={{width: 46, height: 26, borderRadius: 13, background: '#34C759', position: 'relative'}}>
                  <div style={{position: 'absolute', right: 3, top: 3, width: 20, height: 20, borderRadius: 10, background: '#fff'}} />
                </div>
              </div>
              <div style={{display: 'flex', fontSize: 17, color: '#555B66'}}>
                <span style={{flex: 1}}>HomeWiFi</span>
                <span>Connected</span>
              </div>
            </div>
          </div>
          <div style={{position: 'absolute', inset: 0, opacity: go}}>
            <div style={{position: 'absolute', left: 36, top: 20, fontSize: 28, fontWeight: 700}}>Privacy &amp; Security</div>
            <div style={{...card, position: 'absolute', left: 36, right: 36, top: 76, height: 184, overflow: 'hidden', fontSize: 18}}>
              {['Location Services', 'Contacts', 'Calendars', 'Photos'].map((n, i) => (
                <div key={n} style={{height: 46, display: 'flex', alignItems: 'center', padding: '0 22px', borderTop: i ? '1px solid rgba(0,0,0,0.07)' : undefined}}>
                  <span style={{flex: 1}}>{n}</span>
                  <span style={{color: '#9AA1AE'}}>›</span>
                </div>
              ))}
            </div>
            <div style={{position: 'absolute', left: 38, top: 282, fontSize: 16, fontWeight: 700, color: '#555B66'}}>Security</div>
            <div style={{...card, position: 'absolute', left: 36, right: 36, top: 312, height: 130, display: 'flex', alignItems: 'center', padding: '0 22px', gap: 18}}>
              <div style={{flex: 1, fontSize: 17, lineHeight: 1.45}}>
                “<Mixed text={file} />” was blocked to protect your Mac.
              </div>
              <div
                style={{
                  width: 150,
                  height: 38,
                  display: 'grid',
                  placeItems: 'center',
                  borderRadius: 8,
                  background: pressed > 0 ? '#D9DCE3' : '#fff',
                  border: '1px solid rgba(0,0,0,0.18)',
                  fontSize: 16,
                  fontWeight: 600,
                  boxShadow: glow > 0 ? `0 0 0 ${4 * glow}px ${rgba(brand.primary, 0.55 * glow)}` : undefined,
                }}
              >
                Open Anyway
              </div>
            </div>
          </div>
        </div>
      </div>
    </Window>
  );
};

// ------------------------------------------------------------------------------------------------ console
export type CLine = {t: number; text: string; kind?: string; type?: number; typeFrom?: number; prompt?: string};
/**
 * The installer's own console window (cmd on Windows, Terminal on Mac). `kind`: say (bold "==>" line), ar
 * (the Arabic line under it, shaped RTL), ok (teal), warn (amber), dim, input (typed at `type` chars/frame
 * after the prompt text in `prompt`), banner.
 */
export const ConsoleView: React.FC<{lines: Array<CLine & {prompt?: string}>; fontSize?: number; lineH?: number; maxLines?: number; pad?: number}> = ({
  lines,
  fontSize = 21,
  lineH = 33,
  maxLines = 22,
  pad = 22,
}) => {
  const f = useCurrentFrame();
  const shown = lines.filter((l) => f >= l.t);
  const n = shown.length;
  const last = shown[n - 1];
  const scrollT = last ? clamp((f - last.t) / 5) : 1;
  const extra = Math.max(0, n - maxLines);
  const offset = (extra - (n > maxLines ? 1 - scrollT : 0)) * lineH;
  const cursorOn = Math.floor(f / 15) % 2 === 0;
  return (
    <div style={{position: 'absolute', inset: 0, background: '#0C0F14', overflow: 'hidden', fontFamily: brand.mono}}>
      <div style={{position: 'absolute', left: pad, right: pad, top: pad - 6, bottom: 10, overflow: 'hidden'}}>
        <div style={{transform: `translateY(${-offset}px)`}}>
          {shown.map((l, i) => {
            const isLast = i === n - 1;
            const color =
              l.kind === 'say' ? '#F1F5F9' : l.kind === 'ok' ? brand.primaryBright : l.kind === 'warn' ? brand.amber : l.kind === 'ar' ? '#AEB9C8' : l.kind === 'dim' ? '#6E7A8C' : l.kind === 'banner' ? brand.primary : '#C9D2DE';
            if (l.kind === 'input') {
              const k = clamp(Math.floor((f - (l.typeFrom ?? l.t)) * (l.type ?? 0.5)), 0, l.text.length);
              const typing = k < l.text.length;
              return (
                <div key={i} style={{height: lineH, lineHeight: `${lineH}px`, fontSize, whiteSpace: 'pre', color: '#F1F5F9'}}>
                  <span style={{color: '#C9D2DE'}}>{l.prompt ? isolateArabic(l.prompt) : null}</span>
                  <span style={{color: brand.amberBright, fontWeight: 600}}>{l.text.slice(0, k)}</span>
                  {(typing || isLast) && cursorOn ? <span style={{display: 'inline-block', width: fontSize * 0.55, height: fontSize * 1.05, background: brand.primary, verticalAlign: 'middle', marginLeft: 2}} /> : null}
                </div>
              );
            }
            return (
              <div
                key={i}
                style={{
                  height: lineH,
                  lineHeight: `${lineH}px`,
                  fontSize: l.kind === 'ar' ? fontSize * 0.95 : fontSize,
                  whiteSpace: 'pre',
                  color,
                  fontWeight: l.kind === 'say' || l.kind === 'banner' ? 600 : 400,
                  opacity: clamp((f - l.t) / 3),
                  ...(l.kind === 'ar' ? {direction: 'rtl', unicodeBidi: 'plaintext', textAlign: 'left', fontFamily: brand.font, paddingLeft: fontSize * 2.4} : {}),
                }}
              >
                {l.kind === 'ar' ? isolateLatin(l.text.trim()) : isolateArabic(l.text)}
                {isLast && l.kind !== 'ar' && cursorOn && f - l.t > 20 ? null : null}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};

/** Spread `items` (text+kind) over frames from `start`, `gap` frames apart. */
export const timed = (start: number, items: Array<{text: string; kind?: string}>, gap = 3): CLine[] => items.map((it, i) => ({...it, t: start + i * gap}));
