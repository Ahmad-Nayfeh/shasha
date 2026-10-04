import React from 'react';

// Line icon set drawn for the video (24-unit grid, round caps/joins). Stroke width is set by the caller
// (3 at 24 units ≈ storyboard's "stroke 3px rounded" at ~48–64 px sizes, scaled).
type IconDef = {d: string; fill?: string; extra?: React.ReactNode};

const I: Record<string, IconDef> = {
  search: {d: 'M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z M16.2 16.2L20.5 20.5'},
  lock: {d: 'M6 11h12a1.5 1.5 0 0 1 1.5 1.5v7A1.5 1.5 0 0 1 18 21H6a1.5 1.5 0 0 1-1.5-1.5v-7A1.5 1.5 0 0 1 6 11z M8 11V8a4 4 0 0 1 8 0v3'},
  unlock: {d: 'M6 11h12a1.5 1.5 0 0 1 1.5 1.5v7A1.5 1.5 0 0 1 18 21H6a1.5 1.5 0 0 1-1.5-1.5v-7A1.5 1.5 0 0 1 6 11z M8 11V8a4 4 0 0 1 7.6-1.7'},
  shield: {d: 'M12 3l7 3v5.5c0 4.4-3 8.2-7 9.5-4-1.3-7-5.1-7-9.5V6z'},
  shieldCheck: {d: 'M12 3l7 3v5.5c0 4.4-3 8.2-7 9.5-4-1.3-7-5.1-7-9.5V6z M9 12l2.2 2.2L15.5 10'},
  hourglass: {d: 'M6 3h12 M6 21h12 M7.5 3c0 4.5 4.5 5.5 4.5 9s-4.5 4.5-4.5 9 M16.5 3c0 4.5-4.5 5.5-4.5 9s4.5 4.5 4.5 9 M9.5 18.5h5'},
  clock: {d: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 7.5V12l3 2'},
  star: {d: 'M12 3.5l2.6 5.3 5.8.8-4.2 4.1 1 5.8L12 16.8l-5.2 2.7 1-5.8-4.2-4.1 5.8-.8z'},
  phone: {d: 'M8.5 2.5h7A2.5 2.5 0 0 1 18 5v14a2.5 2.5 0 0 1-2.5 2.5h-7A2.5 2.5 0 0 1 6 19V5a2.5 2.5 0 0 1 2.5-2.5z M11 18.5h2'},
  tv: {d: 'M4 4.5h16A1.5 1.5 0 0 1 21.5 6v10A1.5 1.5 0 0 1 20 17.5H4A1.5 1.5 0 0 1 2.5 16V6A1.5 1.5 0 0 1 4 4.5z M8.5 21h7'},
  rss: {d: 'M5 11a8 8 0 0 1 8 8 M5 5a14 14 0 0 1 14 14', extra: <circle cx="6" cy="18" r="1.6" fill="currentColor" stroke="none" />},
  check: {d: 'M5 12.5l4.5 4.5L19 7.5'},
  x: {d: 'M6.5 6.5l11 11 M17.5 6.5l-11 11'},
  keyboard: {d: 'M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z M6.5 10h.01 M10 10h.01 M13.5 10h.01 M17 10h.01 M7.5 14h9'},
  door: {d: 'M6 21V4.5A1.5 1.5 0 0 1 7.5 3h8A1.5 1.5 0 0 1 17 4.5V21 M4 21h15 M13.5 12.5h.01'},
  exit: {d: 'M14 4h4.5A1.5 1.5 0 0 1 20 5.5v13a1.5 1.5 0 0 1-1.5 1.5H14 M10 16.5L5.5 12 10 7.5 M5.5 12H15'},
  home: {d: 'M4 10.5l8-6.5 8 6.5V19a1.5 1.5 0 0 1-1.5 1.5H15v-6H9v6H5.5A1.5 1.5 0 0 1 4 19z'},
  back: {d: 'M9 14L4 9l5-5 M4 9h10.5a5.5 5.5 0 0 1 0 11H11'},
  play: {d: 'M8 5.5v13l10.5-6.5z', fill: 'currentColor'},
  pause: {d: 'M7 5h3v14H7z M14 5h3v14h-3z', fill: 'currentColor'},
  sliders: {d: 'M4 7h9 M17 7h3 M4 17h3 M11 17h9 M15 5v4 M9 15v4'},
  wifi: {d: 'M2.5 9a14 14 0 0 1 19 0 M5.5 12.5a9.5 9.5 0 0 1 13 0 M8.8 16a5 5 0 0 1 6.4 0 M12 19.5h.01'},
  terminal: {d: 'M4 4.5h16A1.5 1.5 0 0 1 21.5 6v12a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 18V6A1.5 1.5 0 0 1 4 4.5z M6.5 9.5l3 2.5-3 2.5 M12 15h5'},
  download: {d: 'M12 3.5v11 M7.5 10.5l4.5 4.5 4.5-4.5 M5 20.5h14'},
  layers: {d: 'M12 3.5l9 4.8-9 4.8-9-4.8z M3 12.5l9 4.8 9-4.8 M3 16.5l9 4.8 9-4.8'},
  barChart: {d: 'M3.5 20.5h17 M6.5 20.5v-6 M11 20.5V8 M15.5 20.5v-9 M20 20.5V4.5'},
  user: {d: 'M12 4a4 4 0 1 0 0 8a4 4 0 1 0 0-8z M4.5 20.5a7.5 7.5 0 0 1 15 0'},
  userSlash: {d: 'M12 4a4 4 0 1 0 0 8a4 4 0 1 0 0-8z M4.5 20.5a7.5 7.5 0 0 1 11.2-6.5 M3.5 3.5l17 17'},
  file: {d: 'M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z M14 3v5h5'},
  doc: {d: 'M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z M14 3v5h5 M9 13h6 M9 17h4'},
  tag: {d: 'M3.5 12V5A1.5 1.5 0 0 1 5 3.5h7l8.5 8.5-8.5 8.5z', extra: <circle cx="8" cy="8" r="1.5" fill="currentColor" stroke="none" />},
  warning: {d: 'M12 3.5l9.5 17h-19z M12 10v4.5 M12 17.5h.01'},
  medal: {d: 'M7.5 3l3 6 M16.5 3l-3 6 M12 9a6 6 0 1 0 0 12a6 6 0 1 0 0-12z M12 12.5l1 2 2.2.3-1.6 1.5.4 2.2-2-1.1-2 1.1.4-2.2-1.6-1.5 2.2-.3z'},
  flame: {d: 'M12 3c1 3.5 5.5 5.5 5.5 10.5a5.5 5.5 0 0 1-11 0c0-2.6 1.4-4 2.6-5.4.4 1.9 1.3 2.8 2.4 2.8 0-2.8-.8-4.9.5-7.9z'},
  calendar: {d: 'M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z M3 10h18 M8 3v4 M16 3v4'},
  box: {d: 'M12 3l8 4.5v9L12 21l-8-4.5v-9z M4 7.5l8 4.5 8-4.5 M12 12v9'},
  link: {d: 'M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1 M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1'},
  send: {d: 'M20.5 3.5L3.5 10l7 3 3 7z M10.5 13l4-4'},
  plug: {d: 'M9 2.5v5 M15 2.5v5 M6.5 7.5h11V11a5.5 5.5 0 0 1-11 0z M12 16.5v5'},
  list: {d: 'M9 6h11 M9 12h11 M9 18h11 M4.5 6h.01 M4.5 12h.01 M4.5 18h.01'},
  plus: {d: 'M12 5v14 M5 12h14'},
  minus: {d: 'M5 12h14'},
  refresh: {d: 'M20 11.5a8 8 0 1 1-2.4-5.7 M20 4v5h-5'},
  moon: {d: 'M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5z'},
  power: {d: 'M12 3v8 M6.3 6.8a8 8 0 1 0 11.4 0'},
  mic: {d: 'M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5.5 11a6.5 6.5 0 0 0 13 0 M12 17.5V21'},
  qr: {d: 'M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h2v2h-2z M18 18h2v2h-2z M14 18h2 M18 14h2'},
  heart: {d: 'M12 20s-7.5-4.6-7.5-10A4.5 4.5 0 0 1 12 7a4.5 4.5 0 0 1 7.5 3c0 5.4-7.5 10-7.5 10z'},
  grid: {d: 'M4 4h7v7H4z M13 4h7v7h-7z M4 13h7v7H4z M13 13h7v7h-7z'},
  subtitles: {d: 'M4 5h16a1.5 1.5 0 0 1 1.5 1.5v11A1.5 1.5 0 0 1 20 19H4a1.5 1.5 0 0 1-1.5-1.5v-11A1.5 1.5 0 0 1 4 5z M6.5 14.5h5 M14 14.5h3.5 M6.5 11h2 M11 11h6.5'},
  gauge: {d: 'M4 17a8 8 0 1 1 16 0 M12 17l4-5'},
  skip: {d: 'M5 5.5l9 6.5-9 6.5z M18 5.5v13', fill: 'none'},
  sparkle: {d: 'M12 3.5l1.8 5.2 5.2 1.8-5.2 1.8L12 17.5l-1.8-5.2L5 10.5l5.2-1.8z'},
  arrowRight: {d: 'M4.5 12h15 M13.5 6l6 6-6 6'},
  arrowLeft: {d: 'M19.5 12h-15 M10.5 6l-6 6 6 6'},
  pencil: {d: 'M4 20h4L19 9l-4-4L4 16z M13.5 6.5l4 4'},
  apk: {d: 'M7 3.5h10A1.5 1.5 0 0 1 18.5 5v14a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 19V5A1.5 1.5 0 0 1 7 3.5z M12 8v6 M9.5 11.5L12 14l2.5-2.5 M9 17h6'},
};

export type IconName = keyof typeof I;

export const Icon: React.FC<{
  name: IconName;
  size?: number;
  color?: string;
  stroke?: number;
  style?: React.CSSProperties;
}> = ({name, size = 48, color = 'currentColor', stroke = 2, style}) => {
  const def = I[name];
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke={color}
      strokeWidth={stroke}
      strokeLinecap="round"
      strokeLinejoin="round"
      style={{color, flex: 'none', display: 'block', ...style}}
    >
      <path d={def.d} fill={def.fill ?? 'none'} stroke={def.fill === 'currentColor' ? 'none' : color} />
      {def.extra}
    </svg>
  );
};
