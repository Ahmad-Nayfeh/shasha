// Renders the two guides with Playwright's Chromium and copies each into the install kit:
//   ar: guide.html     -> ../دليل-شاشة.pdf     and «Shasha-Setup/دليل شاشة.pdf»           (right to left)
//   en: en/guide.html  -> ../Shasha-Guide.pdf  and «Shasha-Setup/Shasha Guide (English).pdf» (left to right)
// Then run tools/make-setup-zip.sh.
//
//   node build.mjs                      # both PDFs
//   node build.mjs --lang en            # one of them (ar or en)
//   node build.mjs --png <dir>          # also every page as a PNG in <dir>/<lang>/, to look at before publishing
//   node build.mjs --png <dir> --width 1280   # the PNGs that wide (the TV's in-app guide; build.sh --pages)
//   node build.mjs --draft ...          # overflowing pages are only reported (to look at them)
//
// Needs the npm package playwright-core (any folder: set PLAYWRIGHT_DIR to the folder that holds its
// node_modules) and a Chromium: the one Playwright manages (PLAYWRIGHT_BROWSERS_PATH), or CHROMIUM_PATH.
// Every .page must fit its A4 sheet: the build fails and names the pages whose content overflows.

import { createRequire } from "node:module";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";
import fs from "node:fs";

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);

function loadPlaywright() {
  const places = [process.env.PLAYWRIGHT_DIR, here].filter(Boolean);
  for (const dir of places) {
    try {
      return createRequire(path.join(dir, "noop.js"))("playwright-core");
    } catch {}
  }
  try {
    return require("playwright-core");
  } catch {
    console.error("playwright-core not found. Install it outside the repository, e.g.\n" +
      "  npm install --prefix /tmp/pw playwright-core && PLAYWRIGHT_DIR=/tmp/pw node build.mjs");
    process.exit(2);
  }
}

const { chromium } = loadPlaywright();
const args = process.argv.slice(2);
const pngDir = args.includes("--png") ? path.resolve(args[args.indexOf("--png") + 1] || "pages") : null;
const width = args.includes("--width") ? Number(args[args.indexOf("--width") + 1]) : 794;
const only = args.includes("--lang") ? args[args.indexOf("--lang") + 1] : null;
const GUIDES = {
  ar: { html: "guide.html", pdf: "دليل-شاشة.pdf", kit: "دليل شاشة.pdf" },
  en: { html: path.join("en", "guide.html"), pdf: "Shasha-Guide.pdf", kit: "Shasha Guide (English).pdf" },
};
if (only && !GUIDES[only]) { console.error(`--lang: ar or en, not ${only}`); process.exit(2); }

const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
try {
  for (const [lang, guide] of Object.entries(GUIDES)) {
    if (only && lang !== only) continue;
    const out = path.resolve(here, "..", guide.pdf);
    const kitCopy = path.resolve(here, "..", "..", "Shasha-Setup", guide.kit);
    const page = await browser.newPage({ viewport: { width: 900, height: 1200 } });
    await page.goto(pathToFileURL(path.join(here, guide.html)).href, { waitUntil: "load" });
    await page.evaluate(async () => {
      await document.fonts.ready;
      await Promise.all([...document.images].map((img) => img.complete ? null : new Promise((ok) => { img.onload = img.onerror = ok; })));
    });

    const report = await page.evaluate(() => {
      const broken = [...document.images].filter((img) => !img.naturalWidth).map((img) => img.getAttribute("src"));
      const fonts = [...document.fonts].filter((f) => f.status !== "loaded").map((f) => `${f.family} ${f.weight}`);
      const overflow = [...document.querySelectorAll(".page")].flatMap((p, i) => {
        const box = p.getBoundingClientRect();
        const bottomLimit = box.bottom - parseFloat(getComputedStyle(p).paddingBottom) + 1;
        const late = [...p.querySelectorAll("*")].filter((el) => {
          if (el.closest(".ftr") || el.closest(".hdr") || el.closest(".cover")) return false;
          const r = el.getBoundingClientRect();
          return r.height > 0 && (r.bottom > bottomLimit || r.left < box.left - 1 || r.right > box.right + 1);
        });
        return late.length ? [`page ${i + 1}: ${late.length} element(s) past the margin, e.g. <${late[0].tagName.toLowerCase()} class="${late[0].className}">`] : [];
      });
      return { broken, fonts, overflow, pages: document.querySelectorAll(".page").length };
    });
    if (report.broken.length) throw new Error(`missing pictures: ${report.broken.join(", ")}`);
    if (report.fonts.length) throw new Error(`fonts not loaded: ${report.fonts.join(", ")}`);
    if (report.overflow.length) {
      const message = `content overflows:\n  ${report.overflow.join("\n  ")}`;
      if (!args.includes("--draft")) throw new Error(message);
      console.warn(message);
    }

    await page.emulateMedia({ media: "print" });
    await page.pdf({ path: out, format: "A4", printBackground: true, preferCSSPageSize: true, margin: { top: 0, right: 0, bottom: 0, left: 0 } });
    fs.copyFileSync(out, kitCopy);
    console.log(`${path.relative(process.cwd(), out)} (and Shasha-Setup/${guide.kit}): ${report.pages} pages, ${(fs.statSync(out).size / 1048576).toFixed(1)} MB`);

    if (pngDir) {
      const dir = path.join(pngDir, lang);
      fs.mkdirSync(dir, { recursive: true });
      for (const old of fs.readdirSync(dir)) if (/^page-\d+\.png$/.test(old)) fs.rmSync(path.join(dir, old));
      await page.emulateMedia({ media: "screen" });
      await page.setViewportSize({ width: 794, height: 1123 });
      const shooter = width === 794 ? page : await browser.newPage({ viewport: { width: 794, height: 1123 }, deviceScaleFactor: width / 794 });
      if (shooter !== page) {
        await shooter.goto(pathToFileURL(path.join(here, guide.html)).href, { waitUntil: "load" });
        await shooter.evaluate(async () => {
          await document.fonts.ready;
          await Promise.all([...document.images].map((img) => img.complete ? null : new Promise((ok) => { img.onload = img.onerror = ok; })));
        });
      }
      const pages = await shooter.$$(".page");
      for (let i = 0; i < pages.length; i++) {
        await pages[i].screenshot({ path: path.join(dir, `page-${String(i + 1).padStart(2, "0")}.png`) });
      }
      console.log(`${pages.length} PNG pages in ${dir}`);
      if (shooter !== page) await shooter.close();
    }
    await page.close();
  }
} finally {
  await browser.close();
}
