// Renders guide.html to ../دليل-شاشة.pdf («دليل شاشة», the one guide for the household) with Playwright's
// Chromium, and copies it into the install kit as «Shasha-Setup/دليل شاشة.pdf». Then run tools/make-setup-zip.sh.
//
//   node build.mjs                 # the PDF
//   node build.mjs --png <dir>     # also every page as a PNG, to look at before publishing
//   node build.mjs --draft ...     # overflowing pages are only reported (to look at them)
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
const out = path.resolve(here, "..", "دليل-شاشة.pdf");
const kitCopy = path.resolve(here, "..", "..", "Shasha-Setup", "دليل شاشة.pdf");

const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
try {
  const page = await browser.newPage({ viewport: { width: 900, height: 1200 } });
  await page.goto(pathToFileURL(path.join(here, "guide.html")).href, { waitUntil: "load" });
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
  console.log(`${path.relative(process.cwd(), out)} (and Shasha-Setup/دليل شاشة.pdf): ${report.pages} pages, ${(fs.statSync(out).size / 1048576).toFixed(1)} MB`);

  if (pngDir) {
    fs.mkdirSync(pngDir, { recursive: true });
    await page.emulateMedia({ media: "screen" });
    await page.setViewportSize({ width: 794, height: 1123 });
    const pages = await page.$$(".page");
    for (let i = 0; i < pages.length; i++) {
      await pages[i].screenshot({ path: path.join(pngDir, `page-${String(i + 1).padStart(2, "0")}.png`) });
    }
    console.log(`${pages.length} PNG pages in ${pngDir}`);
  }
} finally {
  await browser.close();
}
