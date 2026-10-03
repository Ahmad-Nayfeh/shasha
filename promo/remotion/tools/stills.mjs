// Render several stills of the Main composition with ONE bundle + ONE browser (cheap on a shared box).
//   node tools/stills.mjs <outDir> name:frame [name:frame ...]      (frame = absolute frame in Main)
//   node tools/stills.mjs <outDir> s03_home@120 ...                 (frame relative to a scene start)
//   node tools/stills.mjs <outDir> --comp=Mobile m05_search@120     (the phone video: scene ids m..)
//   node tools/stills.mjs <outDir> --comp=ScreensDebug home:0 search:1 (another composition, absolute frames)
//   --props='{"captions":false,"grain":false}'   input props;   --jpeg   JPEG (q95) instead of PNG
import {bundle} from '@remotion/bundler';
import {openBrowser, renderStill, selectComposition} from '@remotion/renderer';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const PW_CHROME = '/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell';
const browserExecutable = fs.existsSync(PW_CHROME) ? PW_CHROME : null; // null: Remotion's own browser
const [outDir, ...rest] = process.argv.slice(2);
const compArg = rest.find((a) => a.startsWith('--comp='));
const compId = compArg ? compArg.slice(7) : 'Main';
const propsArg = rest.find((a) => a.startsWith('--props='));
const jpegArg = rest.find((a) => a === '--jpeg');
const specs = rest.filter((a) => !a.startsWith('--'));
if (!outDir || !specs.length) {
  console.error('usage: node tools/stills.mjs <outDir> name:frame|scene@rel ...');
  process.exit(2);
}
const timeline = JSON.parse(fs.readFileSync(path.join(root, compId === 'Mobile' ? 'src/data/timeline-mobile.json' : 'src/data/timeline.json'), 'utf8'));
const jobs = specs.map((s) => {
  if (s.includes('@')) {
    const [id, rel] = s.split('@');
    const sc = timeline.scenes.find((x) => x.id === id);
    if (!sc) throw new Error('no scene ' + id);
    return {name: `${id}_f${rel}`, frame: sc.start_frame + Number(rel)};
  }
  const [name, frame] = s.split(':');
  return {name, frame: Number(frame)};
});
fs.mkdirSync(outDir, {recursive: true});
const t0 = Date.now();
const serveUrl = await bundle({entryPoint: path.join(root, 'src/index.ts'), publicDir: path.join(root, 'public')});
console.log(`bundled in ${((Date.now() - t0) / 1000).toFixed(1)}s`);
const browser = await openBrowser('chrome', {browserExecutable, chromiumOptions: {}});
const inputProps = propsArg ? JSON.parse(propsArg.slice(8)) : {};
const composition = await selectComposition({serveUrl, id: compId, inputProps, puppeteerInstance: browser, browserExecutable});
for (const j of jobs) {
  const t = Date.now();
  const output = path.join(outDir, `${j.name}.${jpegArg ? 'jpg' : 'png'}`);
  await renderStill({composition, serveUrl, output, frame: j.frame, inputProps, puppeteerInstance: browser, browserExecutable, imageFormat: jpegArg ? 'jpeg' : 'png', ...(jpegArg ? {jpegQuality: 95} : {}), overwrite: true, timeoutInMilliseconds: 60000});
  console.log(`${j.name} (frame ${j.frame}) ${((Date.now() - t) / 1000).toFixed(1)}s -> ${output}`);
}
await browser.close({silent: true});
fs.rmSync(serveUrl, {recursive: true, force: true}); // the bundle is a ~180 MB copy of public/ in the temp dir
