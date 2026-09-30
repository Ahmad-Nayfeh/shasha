// Screenshot manifest access. The manifest (../screens.json) maps a storyboard screen name to a file in
// public/screens/ (copied from the repo by ./sync-screens.sh, see "source"), plus focus-highlight rectangles
// (in the capture's own px when "units" is "px", else relative 0..1) and optional patches.
// When the file is not in public/ yet, components render a labelled placeholder instead.
import {getStaticFiles, staticFile} from 'remotion';
import manifest from '../screens.json';

export type Rect = [number, number, number, number]; // x, y, w, h (relative, top-left origin)
export type PatchOp = {
  op: 'rect' | 'band' | 'crop' | 'text' | 'icon' | 'qr';
  [k: string]: unknown;
};
export type ScreenInfo = {
  file: string;
  title: string;
  kind: string;
  device?: 'tv' | 'phone';
  scenes?: string[];
  source?: string | null;
  units?: 'px' | 'rel';
  size?: [number, number];
  focus?: Record<string, Rect | Rect[]>;
  patches?: Record<string, PatchOp[] | string>;
};

const screens = (manifest as unknown as {screens: Record<string, ScreenInfo>}).screens;

export const screenInfo = (name: string): ScreenInfo => {
  const s = screens[name];
  if (!s) throw new Error('screens.json has no screen named ' + name);
  return s;
};

/** Native pixel size of a capture (what "px" rects are measured in). */
export const captureSize = (name: string): [number, number] => {
  const s = screenInfo(name);
  return s.size ?? (s.device === 'phone' ? [390, 844] : [1920, 1080]);
};

let available: Set<string> | null = null;
const availableFiles = () => {
  if (!available) {
    try {
      available = new Set(getStaticFiles().map((f) => f.name.replace(/^\/+/, '')));
    } catch {
      available = new Set();
    }
  }
  return available;
};

/** URL of the screenshot, or null when the file does not exist yet. */
export const screenSrc = (name: string): string | null => {
  const info = screenInfo(name);
  const rel = 'screens/' + info.file;
  return availableFiles().has(rel) ? staticFile(rel) : null;
};

export const hasScreen = (name: string) => screenSrc(name) !== null;

const toRel = (name: string, r: Rect): Rect => {
  const s = screenInfo(name);
  if (s.units !== 'px') return r;
  const [W, H] = captureSize(name);
  return [r[0] / W, r[1] / H, r[2] / W, r[3] / H];
};

/** Focus rectangle from the manifest (relative coords). */
export const focusRect = (name: string, key: string, index = 0): Rect => {
  const f = screenInfo(name).focus?.[key];
  if (!f) throw new Error(`screens.json: ${name}.focus.${key} missing`);
  return toRel(name, (Array.isArray(f[0]) ? (f as Rect[])[index] : (f as Rect)) as Rect);
};

export const focusRects = (name: string, key: string): Rect[] => {
  const f = screenInfo(name).focus?.[key];
  if (!f) throw new Error(`screens.json: ${name}.focus.${key} missing`);
  return (Array.isArray(f[0]) ? (f as Rect[]) : [f as Rect]).map((r) => toRel(name, r));
};

/** Patch ops of a screen (capture px), [] if none. */
export const screenPatch = (name: string, key: string): PatchOp[] => {
  const p = screenInfo(name).patches?.[key];
  return Array.isArray(p) ? p : [];
};
