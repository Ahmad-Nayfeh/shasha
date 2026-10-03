// English version check (see src/lib/lang.ts):   node tools/check_i18n.mjs [--quiet]
//  1. every t('…') key has an English text in src/i18n/en-*.json;
//  2. no Arabic string literal is drawn without t() (anchors, comments and console text are fine);
//  3. every spoken anchor a scene looks up (b.at('…') / b.end('…') / tryAt('…'), and Main.tsx) has an English anchor
//     in src/i18n/anchors-en-*.json, and that English phrase is spoken in the scene (src/data/words*-en.json).
// Exit code 1 when anything is missing.
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const src = path.join(root, 'src');
const quiet = process.argv.includes('--quiet');
const read = (p) => fs.readFileSync(p, 'utf8');
const json = (p) => JSON.parse(read(path.join(root, p)));
const AR = /[؀-ۿ]/;

const files = [];
const walk = (d) => {
  for (const e of fs.readdirSync(d, {withFileTypes: true})) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (/\.(tsx?|mjs)$/.test(e.name)) files.push(p);
  }
};
walk(src);

const EN = {};
for (const a of ['tour', 'install', 'mobile']) Object.assign(EN, json(`src/i18n/en-${a}.json`));

// Remove comments (keep line numbers): // … and /* … */ outside strings.
const stripComments = (s) => {
  let out = '';
  let i = 0;
  let q = null;
  while (i < s.length) {
    const c = s[i];
    if (q) {
      out += c;
      if (c === '\\') {
        out += s[i + 1] ?? '';
        i += 2;
        continue;
      }
      if (c === q) q = null;
      i++;
    } else if (c === "'" || c === '"' || c === '`') {
      q = c;
      out += c;
      i++;
    } else if (c === '/' && s[i + 1] === '/') {
      while (i < s.length && s[i] !== '\n') i++;
    } else if (c === '/' && s[i + 1] === '*') {
      i += 2;
      while (i < s.length && !(s[i] === '*' && s[i + 1] === '/')) {
        if (s[i] === '\n') out += '\n';
        i++;
      }
      i += 2;
    } else {
      out += c;
      i++;
    }
  }
  return out;
};

const missingKeys = new Map();
const unwrapped = [];
const lit = /(['"`])((?:\\.|(?!\1)[^\\])*)\1/g;
// JSX text between tags: >نص<
const jsxText = />([^<>{}]*[؀-ۿ][^<>{}]*)</g;
for (const f of files) {
  const rel = path.relative(root, f);
  // lib/lang.ts (the digit table) and lib/timeline.ts (word folding) hold Arabic letters that are never drawn.
  if (rel.startsWith('src/i18n') || /TestArabic|ScreensDebug|src\/lib\/(lang|timeline)\.ts$/.test(rel)) continue;
  const code = stripComments(read(f));
  const lineOf = (idx) => code.slice(0, idx).split('\n').length;
  let m;
  while ((m = lit.exec(code))) {
    const text = m[2];
    if (!AR.test(text)) continue;
    const before = code.slice(Math.max(0, m.index - 24), m.index);
    if (/\bt\(\s*$/.test(before)) {
      if (EN[text] === undefined) missingKeys.set(text, `${rel}:${lineOf(m.index)}`);
      continue;
    }
    if (/(?:\.at|\.end|\btryAt|\bat|\bend)\(\s*$/.test(before)) continue; // spoken anchors
    if (/console\.\w+\(\s*$/.test(before) || /throw new Error\(\s*$/.test(before)) continue;
    unwrapped.push(`${rel}:${lineOf(m.index)}  ${text.slice(0, 70)}`);
  }
  while ((m = jsxText.exec(code))) {
    unwrapped.push(`${rel}:${lineOf(m.index)}  (JSX text) ${m[1].trim().slice(0, 70)}`);
  }
}

// --- anchors ------------------------------------------------------------------------------------------------
const ANCH = {};
for (const a of ['tour', 'install', 'mobile']) Object.assign(ANCH, json(`src/i18n/anchors-en-${a}.json`));
const words = {...json('src/data/words-en.json'), ...json('src/data/words-mobile-en.json')};
const norm = (w) =>
  w
    .normalize('NFKC')
    .replace(/[^\p{L}\p{N}]/gu, '')
    .toLowerCase();
const spoken = (sid, phrase, occ) => {
  const ws = (words[sid]?.words ?? []).map((x) => norm(x.w));
  const toks = phrase.split(/\s+/).map(norm).filter(Boolean);
  let n = 0;
  for (let i = 0; i + toks.length <= ws.length; i++) {
    if (toks.every((t, k) => ws[i + k] === t)) {
      if (n === occ) return true;
      n++;
    }
  }
  return false;
};
const index = read(path.join(src, 'scenes/index.ts'));
const compIds = {};
for (const [, sid, comp] of index.matchAll(/([sm]\d\d_[a-z_]+):\s*(\w+)/g)) (compIds[comp] ??= []).push(sid);
const lookups = []; // [file, sceneId, phrase, occurrence]
for (const f of fs.readdirSync(path.join(src, 'scenes'))) {
  if (!f.endsWith('.tsx')) continue;
  const code = stripComments(read(path.join(src, 'scenes', f)));
  const ids = compIds[f.replace('.tsx', '')] ?? [];
  const re = /(?:\bb|beats|[sm]\d\d|\w+Beats)\.(?:at|end)\('([^']+)'(?:\s*,\s*(\d+))?|tryAt\('([^']+)'(?:\s*,\s*(\d+))?/g;
  for (const m of code.matchAll(re)) {
    const phrase = m[1] ?? m[3];
    const occ = Number(m[2] ?? m[4] ?? 0);
    if (!AR.test(phrase)) continue;
    for (const sid of ids) lookups.push([f, sid, phrase, occ]);
  }
}
const mainCode = stripComments(read(path.join(src, 'Main.tsx')));
for (const [, v, sid] of mainCode.matchAll(/const (\w+) = sceneById\('([sm]\d\d_[a-z_]+)'\)/g)) {
  for (const m of mainCode.matchAll(new RegExp(`beatsFor\\(${v}\\)\\.(?:at|end)\\('([^']+)'`, 'g'))) lookups.push(['Main.tsx', sid, m[1], 0]);
}
const seen = new Set();
const noAnchor = [];
const notSpoken = [];
for (const [f, sid, phrase, occ] of lookups) {
  const key = `${sid}|${phrase}|${occ}`;
  if (seen.has(key)) continue;
  seen.add(key);
  const m = ANCH[sid]?.[`${phrase}#${occ}`] ?? (occ === 0 ? ANCH[sid]?.[phrase] : undefined);
  if (m === undefined) {
    noAnchor.push(`${sid} (${f}): «${phrase}»${occ ? ' #' + occ : ''}`);
    continue;
  }
  const [en, eo] = Array.isArray(m) ? m : [m, 0];
  if (!spoken(sid, en, eo)) notSpoken.push(`${sid} (${f}): «${phrase}» -> "${en}"${eo ? ' #' + eo : ''} is not spoken in the English voice`);
}

const show = (title, list) => {
  console.log(`${title}: ${list.length}`);
  if (!quiet) for (const x of list) console.log('  ' + x);
};
show('t() keys with no English', [...missingKeys].map(([k, w]) => `${w}  ${k}`));
show('Arabic strings drawn without t()', unwrapped);
show(`anchors with no English (of ${seen.size})`, noAnchor);
show('English anchors not found in the narration', notSpoken);
process.exit(missingKeys.size + unwrapped.length + noAnchor.length + notSpoken.length ? 1 : 0);
