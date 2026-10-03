// Copy the app's screenshots into public/screens/ following the mapping in src/screens.json, and the English version's
// into public/screens-en/ following src/screens-en.json.
//   node tools/sync-screens.mjs [/path/to/shasha-repo]        (./sync-screens.sh does this)
//
// For every manifest entry:
//   1. "source" (repo-relative path, e.g. app/src/test/screenshots/home.png) — THE mapping; it always wins;
//   2. no source: a file named exactly like the entry's "file" in app/ or remote/src/test/screenshots/;
//   3. nothing found: an existing copy in public/screens/ is kept (and flagged), else the renderer draws the
//      labelled placeholder.
// The English captures (promo/screens-en/<golden>.png) come from the app's English recording, see PromoEnglish in the
// app's tests:   ./gradlew :app:recordRoborazziDebug -PshashaPromoEnglish --tests '*ScreenshotTest'
// A screen with no English capture is not an error: the English version draws its Arabic one (lib/screens.ts).
// Captures are checked for size: TV 1920x1080; phone = the 390x844 CSS viewport at any scale (@2x = 780x1688).
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const repo = process.argv[2] || '/home/user/shasha';

const dirs = ['app/src/test/screenshots', 'remote/src/test/screenshots'].map((d) => path.join(repo, d));
const pngSize = (p) => {
  const b = fs.readFileSync(p);
  return [b.readUInt32BE(16), b.readUInt32BE(20)];
};
const sizeProblem = (info, w, h) => {
  if (info.device === 'phone') return Math.abs(w / h - 390 / 844) < 0.004 ? '' : `   !! expected a 390x844 viewport (any scale), got ${w}x${h}`;
  return w === 1920 && h === 1080 ? '' : `   !! expected 1920x1080, got ${w}x${h}`;
};
const sameFile = (a, b) => {
  try {
    return fs.readFileSync(a).equals(fs.readFileSync(b));
  } catch {
    return false;
  }
};

/** Copies the captures of one manifest into its public/ folder; `lang` only words the report. */
function sync(manifestFile, folder, lang) {
  const manifest = JSON.parse(fs.readFileSync(path.join(root, manifestFile), 'utf8'));
  const outDir = path.join(root, 'public', folder);
  fs.mkdirSync(outDir, {recursive: true});
  const screens = Object.entries(manifest.screens);
  console.log(`${lang} (${manifestFile} -> public/${folder}/): ${screens.length} screens`);
  let copied = 0;
  let unchanged = 0;
  const kept = [];
  const missing = [];
  for (const [name, info] of screens) {
    const dst = path.join(outDir, info.file);
    const src = info.source
      ? [path.join(repo, info.source)].find((c) => fs.existsSync(c))
      : dirs.map((d) => path.join(d, info.file)).find((c) => fs.existsSync(c));
    if (src) {
      const same = sameFile(src, dst);
      if (!same) fs.copyFileSync(src, dst);
      const [w, h] = pngSize(dst);
      console.log(`${same ? 'same  ' : 'copied'}  ${name.padEnd(24)} <- ${path.relative(repo, src)} (${w}x${h})${sizeProblem(info, w, h)}`);
      same ? unchanged++ : copied++;
    } else if (fs.existsSync(dst)) {
      kept.push(`${name} (source ${info.source ?? '-'} not found; kept the old public/${folder}/${info.file})`);
    } else {
      missing.push(`${name}${info.source ? ` (source ${info.source} not found)` : ''}${info.vector ? ' [drawn as vector]' : ''}`);
    }
  }
  const without = lang === 'English' ? 'without a capture (the Arabic one is used)' : 'without a capture (placeholder)';
  console.log(`\n${copied} copied, ${unchanged} unchanged, ${kept.length} kept, ${missing.length} ${without}:`);
  for (const k of kept) console.log('  kept    ' + k);
  for (const m of missing) console.log('  missing ' + m);
  console.log('');
}

sync('src/screens.json', 'screens', 'Arabic');
sync('src/screens-en.json', 'screens-en', 'English');
