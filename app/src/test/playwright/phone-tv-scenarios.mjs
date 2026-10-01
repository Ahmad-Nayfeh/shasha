// The phone page's rows of the phone <-> TV scenario matrix (docs/dev/phone-tv-scenarios.md): UI-01…UI-21, T-07,
// T-11, T-12 and PS-05's page part, in Chromium at 390 px, against ScenarioMatrixPageServer (ScenarioMatrixTest.kt:
// the TV's real rules behind the real server). Writes <SHASHA_MATRIX_DIR>/ui-results.json ([{id, title, pass, note}]),
// a screenshot per failed row in <SHASHA_MATRIX_DIR>/shots, then the "stop" file that ends the page server.
//
//   mkdir -p /tmp/mx
//   SHASHA_MATRIX_DIR=/tmp/mx ./gradlew :app:testDebugUnitTest --tests '*ScenarioMatrixPageServer*' --rerun &
//   SHASHA_MATRIX_DIR=/tmp/mx PLAYWRIGHT_DIR=/tmp/pw node app/src/test/playwright/phone-tv-scenarios.mjs
//
// Needs Node 18+, the npm package playwright-core (or playwright) outside the repository (PLAYWRIGHT_DIR is the folder
// holding its node_modules), and a Chromium: the one Playwright manages (PLAYWRIGHT_BROWSERS_PATH) or CHROMIUM_PATH.
// The overflow checks measure text, so install the phone's fonts (Roboto, Noto Sans Arabic; FONTCONFIG_FILE may name a
// fontconfig file that prefers them). Exit code 1 when a row fails.
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';

function loadPlaywright() {
  const places = [process.env.PLAYWRIGHT_DIR, process.cwd()].filter(Boolean);
  for (const dir of places) {
    for (const name of ['playwright-core', 'playwright']) {
      try { return createRequire(path.join(dir, 'noop.js'))(name); } catch (e) { /* next */ }
    }
  }
  console.error('playwright-core not found. Install it outside the repository, e.g.\n' +
    '  npm install --prefix /tmp/pw playwright-core && PLAYWRIGHT_DIR=/tmp/pw node phone-tv-scenarios.mjs');
  process.exit(2);
}
const { chromium } = loadPlaywright();

const DIR = process.env.SHASHA_MATRIX_DIR;
if (!DIR) { console.error('Set SHASHA_MATRIX_DIR to the folder the page server writes "ready" to.'); process.exit(2); }
const OUT = process.env.OUT || path.join(DIR, 'ui-results.json');
const SHOTS = process.env.SHOTS || path.join(DIR, 'shots');
const PIN = '1357';
fs.mkdirSync(SHOTS, { recursive: true });

const sleep = ms => new Promise(r => setTimeout(r, ms));
async function waitFile(name, ms = 60000) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) { if (fs.existsSync(path.join(DIR, name))) return; await sleep(100); }
  throw new Error('timeout waiting for ' + name);
}
await waitFile('ready', 180000);
const READY = fs.readFileSync(path.join(DIR, 'ready'), 'utf8').trim();
const BASE = READY.replace(/\/\?t=.*/, '');
const TOKEN = new URL(READY).searchParams.get('t');

/** Commands for the TV side (ScenarioMatrixPageServer). */
async function tv(...lines) {
  try { fs.unlinkSync(path.join(DIR, 'cmd.out')); } catch (e) { /* none */ }
  fs.writeFileSync(path.join(DIR, 'cmd.tmp'), lines.join('\n') + '\n');
  fs.renameSync(path.join(DIR, 'cmd.tmp'), path.join(DIR, 'cmd'));
  await waitFile('cmd.out', 30000);
  const out = fs.readFileSync(path.join(DIR, 'cmd.out'), 'utf8').trim().split('\n');
  fs.unlinkSync(path.join(DIR, 'cmd.out'));
  const bad = out.find(l => l.startsWith('ERROR') || l.startsWith('unknown'));
  if (bad) throw new Error('TV command failed: ' + bad + ' for ' + lines.join(' | '));
  return out;
}

const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined });
const results = [];
let ctx, page, requests, errors;

async function fresh(...cmds) {
  if (ctx) await ctx.close();
  await tv('fresh', ...cmds);
  ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'ar' });
  page = await ctx.newPage();
  requests = [];
  errors = [];
  page.on('request', r => { if (r.url().includes('/api/')) requests.push(r.method() + ' ' + new URL(r.url()).pathname + ' ' + (r.postData() || '')); });
  page.on('pageerror', e => errors.push(String(e)));
}
async function open(hash = '#screen', token = TOKEN) {
  await page.goto(BASE + '/?t=' + encodeURIComponent(token) + hash);
  await page.waitForSelector('#who:not([hidden])', { timeout: 10000 });
  await sleep(400);
}
async function login() {
  await page.goto(BASE + '/?t=' + encodeURIComponent(TOKEN) + '#parent');
  await page.waitForSelector('#login-pin');
  await page.fill('#login-pin', PIN);
  await page.click('#login-btn');
  await page.waitForSelector('#parent-hub:not([hidden]) .hrow', { timeout: 10000 });
  await sleep(500);
}
async function hub(id) {
  await page.click('.hrow[data-page="' + id + '"]');
  await page.waitForSelector('#parent-page:not([hidden]) #page-body');
  await sleep(700);
}
const visible = sel => page.locator(sel).first().isVisible();
const attr = (sel, a) => page.locator(sel).first().getAttribute(a);
const text = sel => page.locator(sel).first().innerText();
const overflow = () => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
async function sheetTitle() { await page.waitForSelector('.sheet h3', { timeout: 5000 }); return (await page.locator('.sheet h3').last().innerText()).trim(); }
async function toastText() { await page.waitForSelector('#toasts .toast', { timeout: 5000 }); return (await page.locator('#toasts .toast').last().innerText()).trim(); }
/** Waits for a toast that contains [part] (an earlier toast may still be showing). */
async function waitToast(part) {
  try { await page.waitForSelector('#toasts .toast:has-text("' + part + '")', { timeout: 8000 }); return true; } catch (e) { return false; }
}
/** A tap: aria-disabled buttons still take taps (they explain themselves), which Playwright's checks would refuse. */
const tap = sel => page.locator(sel).first().click({ force: true });

function check(cond, note) { if (!cond) throw new Error(note); }
async function row(id, title, body) {
  let pass = false, note = '';
  try {
    await body();
    const o = page ? await overflow() : 0;
    check(o <= 0, 'horizontal overflow of ' + o + ' px');
    check(!errors || errors.length === 0, 'page errors: ' + (errors || []).join('; '));
    pass = true;
  } catch (e) {
    note = String(e && e.message || e).split('\n')[0];
    try { await page.screenshot({ path: path.join(SHOTS, id + '.png'), fullPage: true }); } catch (x) { /* none */ }
  }
  results.push({ id, title, pass, note });
  console.log((pass ? 'PASS ' : 'FAIL ') + id + ' ' + title + (note ? ' — ' + note : ''));
}

// ---- the rows ----

await row('UI-09', 'idle: nothing on screen, no controls', async () => {
  await fresh();
  await open('#screen');
  check(await visible('#np-idle'), 'idle line hidden');
  check((await text('#np-idle-t')).includes('لا شيء يُعرض الآن على الشاشة'), 'idle text: ' + await text('#np-idle-t'));
  check(!(await visible('#np-body')), 'controls shown');
});

await row('UI-01', 'child: search locked and explained', async () => {
  await fresh('child');
  await open('#search');
  check(await visible('#search-lock'), 'no lock on the search tab');
  check(await visible('#search-off'), 'no explanation card');
  check(!(await visible('#search-on')), 'the search box is still there');
});

await row('UI-02', 'locked video: STOP disabled and explained, no request', async () => {
  await fresh('play outside0001');
  await open('#screen');
  await page.waitForSelector('#np-body:not([hidden])');
  check((await attr('#c-stop', 'aria-disabled')) === 'true', 'STOP not aria-disabled');
  check(await visible('#np-lock'), 'no lock line');
  const before = requests.filter(r => r.includes('/api/control')).length;
  await tap('#c-stop');
  check((await sheetTitle()).includes('الوضع الجاد'), 'no Serious Mode explanation');
  await sleep(300);
  check(requests.filter(r => r.includes('/api/control')).length === before, 'a control request was sent');
});

await row('UI-03', 'locked video: the link box disabled with the reason', async () => {
  await fresh('play outside0001');
  await open('#screen');
  check(await page.locator('#send-url').isDisabled(), 'link box enabled');
  check(await visible('#send-why'), 'no reason shown');
  check((await text('#send-why-t')).includes('الوضع الجاد'), 'reason: ' + await text('#send-why-t'));
});

await row('UI-04', 'locked video: other profiles disabled with the banner', async () => {
  await fresh('play outside0001');
  await open('#profiles');
  await page.waitForSelector('.ptile');
  check(await visible('#profiles-locked'), 'no banner');
  const other = page.locator('.ptile:not(.cur)').first();
  check((await other.getAttribute('aria-disabled')) === 'true', 'other tile not disabled');
  await other.click({ force: true });
  check((await sheetTitle()).includes('فيديو مقفول'), 'no explanation');
  check(!requests.some(r => r.startsWith('POST /api/profile ')), 'a switch was sent');
});

await row('UI-05', 'child time up: banner, open-time link, link box disabled', async () => {
  await fresh('child', 'used 3600');
  await open('#screen');
  const banner = await text('#screen-banner');
  check(banner.includes('انتهى وقت المشاهدة اليوم'), 'banner: ' + banner);
  check(await page.locator('#screen-banner a[href="#parent"]').count() === 1, 'no «فتح الوقت» link');
  check(await page.locator('#send-url').isDisabled(), 'link box enabled');
  check((await text('#send-why-t')).includes('انتهى وقت'), 'reason: ' + await text('#send-why-t'));
  check((await text('#np-idle-t')).includes('والشاشة مقفولة'), 'idle: ' + await text('#np-idle-t'));
});

await row('UI-06', 'child outside the schedule: banner and reason', async () => {
  await fresh('child', 'at 23:00');
  await open('#screen');
  check((await text('#screen-banner')).includes('خارج أوقات المشاهدة'), 'banner: ' + await text('#screen-banner'));
  check(await page.locator('#send-url').isDisabled(), 'link box enabled');
  check((await text('#send-why-t')).includes('خارج أوقات المشاهدة'), 'reason: ' + await text('#send-why-t'));
});

await row('UI-07', 'child with 4 minutes left: the warning banner', async () => {
  await fresh('child', 'used 3360');
  await open('#screen');
  const banner = await text('#screen-banner');
  check(banner.includes('الوقت يوشك على الانتهاء'), 'banner: ' + banner);
  check(banner.includes('4 دقائق'), 'minutes: ' + banner);
  check(!(await page.locator('#send-url').isDisabled()), 'link box disabled during a warning');
});

await row('UI-08', 'live: ±10 disabled and explained', async () => {
  await fresh('serious off', 'play livevideo01 live');
  await open('#screen');
  await page.waitForSelector('#np-body:not([hidden])');
  check((await attr('#c-back', 'aria-disabled')) === 'true' && (await attr('#c-fwd', 'aria-disabled')) === 'true', '±10 not disabled');
  await tap('#c-fwd');
  check(await waitToast('البث المباشر'), 'no explanation');
  check(!requests.some(r => r.includes('SEEK_BY')), 'a seek was sent');
});

await row('UI-10', 'the phone PIN count locked: login disabled with a countdown', async () => {
  await fresh();
  await page.goto(BASE + '/?t=' + encodeURIComponent(TOKEN) + '#parent');
  await page.waitForSelector('#login-pin');
  for (let i = 0; i < 5; i++) {
    await page.fill('#login-pin', '2468');
    await page.click('#login-btn');
    await sleep(500);
  }
  check(await page.locator('#login-btn').isDisabled(), 'login button enabled');
  check((await text('#login-err')).includes('محاولات خاطئة كثيرة'), 'text: ' + await text('#login-err'));
});

await row('UI-11', 'hub: a locked video and «إنهاء قفل هذا الفيديو»', async () => {
  await fresh('play outside0001');
  await login();
  check(await visible('#hub-now .lockpanel'), 'no lock panel');
  await page.click('#hub-now .lockpanel button');
  check(await waitToast('أُنهي الوضع الجاد'), 'no confirmation');
  await sleep(2600);
  check(!(await visible('#hub-now .lockpanel')), 'lock panel still shown');
  check((await tv('locked'))[0] === 'false', 'the TV still has a lock');
});

await row('UI-12', 'hub: +N buttons are the extra-minutes setting, none for an unlimited profile', async () => {
  await fresh('child', 'used 3600');
  await login();
  const labels = async () => (await page.locator('#hub-now .grid2 button').allInnerTexts()).map(s => s.trim());
  let l = await labels();
  check(JSON.stringify(l) === JSON.stringify(['15 دقيقة', '30 دقيقة', 'ساعة', 'بقية اليوم']), 'buttons: ' + l.join(','));
  await tv('extra 10,20');
  await sleep(2600);
  await page.reload(); await page.waitForSelector('#parent-hub:not([hidden]) .hrow'); await sleep(1200);
  l = await labels();
  check(JSON.stringify(l) === JSON.stringify(['10 دقائق', '20 دقيقة', 'بقية اليوم']), 'after the change: ' + l.join(','));
  await page.click('#hub-now .grid2 button >> nth=0');
  check(await waitToast('أُضيفت'), 'no confirmation');
  await fresh();
  await login();
  check(await page.locator('#hub-now .grid2').count() === 0, 'buttons for an unlimited profile');
  check((await text('#hub-now')).includes('لا حد يومي'), 'no explanation');
});

await row('UI-13', 'Serious Mode off: the escape settings hidden, the summary warns', async () => {
  await fresh('serious off');
  await login();
  const sum = page.locator('.hrow[data-page="serious"] .t span');
  check((await sum.getAttribute('class') || '').includes('warn'), 'summary not a warning');
  await hub('serious');
  check(await page.locator('.setting[data-key="serious_mode"]').count() === 1, 'no Serious Mode switch');
  check(await page.locator('.setting[data-key="adult_escape"]').count() === 0, 'escape still shown');
  check(await page.locator('.setting[data-key="escape_hold_seconds"]').count() === 0, 'hold still shown');
});

await row('UI-14', 'no phrase of one\'s own: «عبارتي الخاصة» disabled with its reason', async () => {
  await fresh();
  await login();
  await hub('serious');
  const s = page.locator('.setting[data-key="phrase_mode"]');
  const off = s.locator('[aria-disabled="true"]');
  check(await off.count() === 1, 'no disabled choice');
  check((await s.innerText()).includes('اكتب عبارتك الخاصة'), 'no reason');
});

await row('UI-15', 'extra minutes at 4 choices: the others disabled with the reason', async () => {
  await fresh('extra 15,30,60,90');
  await login();
  await hub('playback');
  const s = page.locator('.setting[data-key="extra_minutes"]');
  check(await s.locator('[aria-disabled="true"]').count() >= 1, 'nothing disabled');
  check((await s.innerText()).includes('اخترت 4 خيارات'), 'no reason');
});

await row('UI-16', 'a section at its defaults: its reset disabled', async () => {
  await fresh();
  await login();
  await hub('quality');
  const b = page.locator('#page-body .foot button');
  check(await b.isDisabled(), 'reset enabled');
  check((await b.innerText()).includes('كلها على وضعها الأصلي'), 'text: ' + await b.innerText());
});

await row('UI-17', 'profiles page: deletes and the child\'s comments disabled with reasons; quota steps are the TV\'s', async () => {
  await fresh('child');
  await login();
  await hub('profiles');
  const editors = page.locator('.pe');
  const n = await editors.count();
  check(n >= 2, 'editors: ' + n);
  for (let i = 0; i < n; i++) {
    const e = editors.nth(i);
    const del = e.locator('.pe-foot button');
    check((await del.getAttribute('aria-disabled')) === 'true', 'delete enabled in editor ' + i);
    check((await e.locator('.pe-foot .why').innerText()).length > 0, 'no reason in editor ' + i);
  }
  const kid = editors.filter({ hasText: 'طفل' }).first();
  check((await kid.locator('button[role="switch"][aria-label="إخفاء التعليقات"]').getAttribute('aria-disabled')) === 'true', 'child comments switch enabled');
  // The quota stepper walks the TV's choices: 60 → 90 → 120.
  const v = kid.locator('.stepper .v');
  check((await v.innerText()).includes('ساعة'), 'quota: ' + await v.innerText());
  await kid.locator('.stepper button[aria-label="حصة أكثر"]').click();
  check((await v.innerText()).includes('90'), 'next quota: ' + await v.innerText());
});

await row('UI-18', 'a wrong token: the unpaired page asks for the QR', async () => {
  await fresh();
  await page.goto(BASE + '/?t=wrong-token-0000000000000000');
  check((await page.locator('h1').first().innerText()).includes('امسح رمز QR'), 'no unpaired page');
});

await row('UI-19', 'the TV unreachable: the offline banner', async () => {
  await fresh();
  await open('#screen');
  await page.route('**/api/**', r => r.abort());
  await page.waitForSelector('#offline:not([hidden])', { timeout: 15000 });
  await page.unroute('**/api/**');
});

await row('UI-20', 'adultEntryPin off: the PIN section warns', async () => {
  await fresh('aep off');
  await login();
  const sum = page.locator('.hrow[data-page="pin"] .t span');
  check((await sum.innerText()).includes('ملف الكبار يُفتح دون رمز'), 'summary: ' + await sum.innerText());
});

await row('T-11', 'share → screen: sent once, the link leaves the address', async () => {
  await fresh('serious off');
  const link = 'https://www.youtube.com/watch?v=outside0001';
  await page.goto(BASE + '/?t=' + encodeURIComponent(TOKEN) + '&v=' + link);
  check((await sheetTitle()).includes('أُرسل إلى الشاشة') || (await sheetTitle()).includes('جارٍ'), 'no sent sheet');
  await page.waitForFunction(() => /أُرسل إلى الشاشة/.test(document.querySelector('.sheet h3')?.textContent || ''), null, { timeout: 15000 });
  check(!page.url().includes('v='), 'link still in the address: ' + page.url());
  check(requests.filter(r => r.startsWith('POST /api/play')).length === 1, 'plays: ' + requests.filter(r => r.startsWith('POST /api/play')).length);
  check((await tv('status'))[0].includes('playing=outside0001'), 'TV: ' + (await tv('status'))[0]);
});

await row('T-12', 'a shared link without the token: asked first', async () => {
  await fresh('serious off');
  await open('#screen'); // remembers the token
  requests.length = 0;
  await page.goto(BASE + '/?v=https://www.youtube.com/watch?v=pl' + 'video0001', { waitUntil: 'load' });
  await page.waitForFunction(() => /إرسال هذا الرابط/.test(document.querySelector('.sheet h3')?.textContent || ''), null, { timeout: 15000 });
  check(!requests.some(r => r.startsWith('POST /api/play')), 'sent before the yes');
  await page.locator('.sheet .acts .btn.primary').click();
  await page.waitForFunction(() => /أُرسل إلى الشاشة/.test(document.querySelector('.sheet h3')?.textContent || ''), null, { timeout: 15000 });
  check(requests.filter(r => r.startsWith('POST /api/play')).length === 1, 'plays: ' + requests.filter(r => r.startsWith('POST /api/play')).length);
});

await row('PS-05', 'the session ends mid-action: the PIN sheet, then the same change is saved', async () => {
  await fresh();
  await login();
  await hub('playback');
  await tv('revoke');
  await page.locator('.setting[data-key="seek_step_seconds"] button', { hasText: '30' }).first().click();
  check((await sheetTitle()).includes('انتهت جلسة قسم الأب'), 'no PIN sheet');
  await page.fill('.sheet input', PIN);
  await page.click('.sheet button[type="submit"]');
  await sleep(2000);
  const s = await page.locator('.setting[data-key="seek_step_seconds"] [aria-pressed="true"]').first().innerText();
  check(s.includes('30'), 'not saved: ' + s);
});

await row('UI-21', 'no horizontal overflow on any view', async () => {
  await fresh('child');
  for (const hash of ['#screen', '#search', '#profiles', '#parent']) {
    await open(hash);
    const o = await overflow();
    check(o <= 0, hash + ' overflows ' + o);
  }
  await login();
  for (const id of ['profiles', 'allow', 'block', 'serious', 'playback', 'pin', 'sponsorblock', 'quality', 'remote', 'subs', 'stats', 'backup', 'reset']) {
    await hub(id);
    const o = await overflow();
    check(o <= 0, id + ' overflows ' + o);
    await page.click('#parent-page .back');
    await page.waitForSelector('#parent-hub:not([hidden])');
  }
});

await row('T-07', 'the token regenerated on the TV: the open page shows the fatal overlay and forgets the token', async () => {
  await fresh();
  await open('#screen');
  const fresh2 = (await tv('rotate'))[0];
  check(fresh2 && fresh2.length >= 20, 'no new token');
  await page.waitForSelector('#fatal:not([hidden])', { timeout: 10000 });
  const kept = await page.evaluate(() => localStorage.getItem('shasha.token'));
  check(kept === null, 'the old token is still remembered');
});

fs.writeFileSync(OUT, JSON.stringify(results, null, 1));
fs.writeFileSync(path.join(DIR, 'stop'), '');
await browser.close();
console.log(results.filter(r => r.pass).length + '/' + results.length + ' UI rows pass');
if (results.some(r => !r.pass)) process.exitCode = 1;
