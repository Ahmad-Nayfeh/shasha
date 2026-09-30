// Renders docs/install-guide.md to «Shasha-Setup/اقرأني - طريقة التثبيت.pdf»: the printable guide inside
// the install kit. A4, right to left, IBM Plex Sans Arabic (app/src/main/res/font), with the guide's pictures.
//
//   PLAYWRIGHT_DIR=/tmp/pdfdeps node tools/make-install-pdf.mjs [--allow-missing] [--html out.html]
//
// Needs Node 18+ and three npm packages installed OUTSIDE the repository, in the folder PLAYWRIGHT_DIR
// (the one holding their node_modules):
//   npm install --prefix /tmp/pdfdeps playwright-core marked github-slugger
// and a Chromium Playwright can launch: the one it manages (PLAYWRIGHT_BROWSERS_PATH) or CHROMIUM_PATH.
// Do not run "playwright install" for this. A picture the guide links to but that does not exist yet fails
// the build; --allow-missing draws a placeholder instead (a draft). Links to other files of the repository
// become links to GitHub, since the PDF travels without them. Then run tools/make-setup-zip.sh.

import { createRequire } from "node:module";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";
import fs from "node:fs";
import os from "node:os";

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const guide = path.join(repo, "docs", "install-guide.md");
const out = path.join(repo, "Shasha-Setup", "اقرأني - طريقة التثبيت.pdf");
const repoUrl = "https://github.com/Ahmad-Nayfeh/shasha/blob/main/";
const fontDir = path.join(repo, "app", "src", "main", "res", "font");
const logo = path.join(repo, "branding", "exports", "logo-lockup-on-light-512.png");

const args = process.argv.slice(2);
const allowMissing = args.includes("--allow-missing");
const htmlOut = args.includes("--html") ? path.resolve(args[args.indexOf("--html") + 1]) : null;

function load(name) {
  for (const dir of [process.env.PLAYWRIGHT_DIR, repo].filter(Boolean)) {
    try {
      return createRequire(path.join(dir, "noop.js"))(name);
    } catch {}
  }
  try {
    return createRequire(import.meta.url)(name);
  } catch {
    console.error(`${name} not found. Install the packages outside the repository, e.g.\n` +
      "  npm install --prefix /tmp/pdfdeps playwright-core marked github-slugger\n" +
      "  PLAYWRIGHT_DIR=/tmp/pdfdeps node tools/make-install-pdf.mjs");
    process.exit(2);
  }
}

const { chromium } = load("playwright-core");
const { Marked } = load("marked");
const sluggerModule = load("github-slugger");
const GithubSlugger = sluggerModule.default ?? sluggerModule;

const escapeHtml = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
const guideDir = path.dirname(guide);
const slugger = new GithubSlugger();
const missing = [];

const marked = new Marked({
  gfm: true,
  renderer: {
    // The same ids GitHub gives headings, so the guide's own #links work in the PDF too.
    heading({ tokens, depth, text }) {
      const id = slugger.slug(text.replace(/[*`]/g, ""));
      return `<h${depth} id="${escapeHtml(id)}">${this.parser.parseInline(tokens)}</h${depth}>\n`;
    },
    image({ href, text }) {
      const file = path.resolve(guideDir, decodeURI(href));
      if (!fs.existsSync(file)) {
        missing.push(path.relative(repo, file));
        return `<span class="missing">${escapeHtml(path.basename(file))}</span>`;
      }
      return `<img src="${pathToFileURL(file).href}" alt="${escapeHtml(text)}">`;
    },
    link({ href, tokens }) {
      const inner = this.parser.parseInline(tokens);
      if (href.startsWith("#") || /^[a-z][a-z0-9+.-]*:/i.test(href)) return `<a href="${escapeHtml(href)}">${inner}</a>`;
      const [target, anchor] = href.split("#");
      const rel = path.relative(repo, path.resolve(guideDir, decodeURI(target))).split(path.sep).join("/");
      return `<a href="${escapeHtml(repoUrl + encodeURI(rel) + (anchor ? `#${anchor}` : ""))}">${inner}</a>`;
    },
    // GitHub alerts ("> [!TIP]") become a tinted box without the marker.
    blockquote({ tokens }) {
      const body = this.parser.parse(tokens);
      const alert = body.match(/^<p>\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*/);
      if (alert) return `<aside class="alert ${alert[1].toLowerCase()}"><p>${body.slice(alert[0].length)}</aside>\n`;
      return `<blockquote>${body}</blockquote>\n`;
    },
    // A paragraph holding only a picture becomes a figure: kept whole on one page.
    paragraph({ tokens }) {
      const inner = this.parser.parseInline(tokens);
      if (tokens.length === 1 && tokens[0].type === "image") return `<figure>${inner}</figure>\n`;
      return `<p>${inner}</p>\n`;
    },
  },
});

const body = marked.parse(fs.readFileSync(guide, "utf8"));
if (missing.length && !allowMissing) {
  console.error(`missing pictures (add them, or build a draft with --allow-missing):\n  ${missing.join("\n  ")}`);
  process.exit(1);
}

const face = (file, weight) =>
  `@font-face { font-family: "Plex Arabic"; font-weight: ${weight}; src: url("${pathToFileURL(path.join(fontDir, file)).href}"); }`;
const html = `<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<title>دليل التثبيت خطوة بخطوة: شاشة</title>
<style>
${face("plex_arabic_regular.ttf", 400)}
${face("plex_arabic_medium.ttf", 500)}
${face("plex_arabic_semibold.ttf", 600)}
${face("plex_arabic_bold.ttf", 700)}
/* Arabic letters inside code use Plex (shaped); Latin letters and the tree lines stay monospace. */
@font-face { font-family: "Plex Arabic Letters"; src: url("${pathToFileURL(path.join(fontDir, "plex_arabic_regular.ttf")).href}");
             unicode-range: U+0600-06FF, U+0750-077F, U+08A0-08FF, U+FB50-FDFF, U+FE70-FEFF; }
@page { size: A4; margin: 15mm 16mm 17mm; }
html { font-family: "Plex Arabic", sans-serif; font-size: 10.4pt; line-height: 1.62; color: #1d2329; }
body { margin: 0; }
.logo { text-align: center; margin: 2mm 0 5mm; }
.logo img { width: 58mm; }
h1 { font-size: 23pt; font-weight: 700; text-align: center; margin: 0 0 4mm; color: #0f3f3a; }
h1 + p { text-align: center; font-size: 11pt; margin: 0 8mm 5mm; }
h2 { font-size: 14.5pt; font-weight: 700; color: #0f3f3a; margin: 8mm 0 2.5mm; padding-bottom: 1.2mm;
     border-bottom: 0.5mm solid #2fbfab; break-after: avoid; }
h3 { font-size: 11.5pt; font-weight: 600; margin: 4.5mm 0 1.5mm; break-after: avoid; }
p { margin: 1.5mm 0; }
ul, ol { margin: 1.5mm 0; padding-inline-start: 7mm; }
li { margin: 0.8mm 0; }
strong { font-weight: 700; }
a { color: #0b6e63; text-decoration: none; }
figure { margin: 3mm 0; text-align: center; break-inside: avoid; }
figure img { max-width: 100%; max-height: 82mm; border-radius: 2.5mm; border: 0.3mm solid #cfd8dc; }
.missing { display: inline-block; width: 120mm; height: 40mm; line-height: 40mm; border: 0.6mm dashed #e0625b;
           border-radius: 2.5mm; color: #b3261e; font-size: 10pt; direction: ltr; }
table { width: 100%; border-collapse: collapse; margin: 2.5mm 0; font-size: 9.4pt; line-height: 1.5; }
tr { break-inside: avoid; }
th, td { border: 0.3mm solid #cfd8dc; padding: 1.3mm 2mm; text-align: right; vertical-align: top; }
th { background: #e8f6f4; font-weight: 600; }
code { font-family: "Plex Arabic Letters", "DejaVu Sans Mono", Menlo, Consolas, monospace, "Plex Arabic"; font-size: 0.88em;
       background: #eef1f3; border-radius: 1mm; padding: 0 1mm; unicode-bidi: isolate; }
pre { direction: ltr; text-align: left; background: #f4f6f8; border: 0.3mm solid #cfd8dc; border-radius: 2mm;
      padding: 2.5mm 3.5mm; font-size: 8.6pt; line-height: 1.5; white-space: pre-wrap; break-inside: avoid; }
pre code { background: none; padding: 0; font-size: 1em; unicode-bidi: normal; }
blockquote { margin: 2.5mm 0; padding: 1.5mm 4mm; border-inline-start: 1.2mm solid #9aa7b0; background: #f6f8f9; }
.alert { margin: 3mm 0; padding: 2mm 4mm; border-inline-start: 1.2mm solid #2fbfab; background: #eaf8f6; border-radius: 1.5mm; }
.alert p { margin: 0.5mm 0; }
hr { border: none; border-top: 0.3mm solid #cfd8dc; margin: 6mm 0 3mm; }
</style>
</head>
<body>
<div class="logo"><img src="${pathToFileURL(logo).href}" alt="شاشة"></div>
${body}
</body>
</html>
`;
if (htmlOut) fs.writeFileSync(htmlOut, html);

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "shasha-guide-"));
const tmpHtml = path.join(tmpDir, "install-guide.html");
fs.writeFileSync(tmpHtml, html);
const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
try {
  const page = await browser.newPage();
  await page.goto(pathToFileURL(tmpHtml).href, { waitUntil: "load" });
  const report = await page.evaluate(async () => {
    await document.fonts.ready;
    return {
      broken: [...document.images].filter((img) => !img.naturalWidth).map((img) => img.getAttribute("src")),
      fonts: [...document.fonts].filter((f) => f.status === "error").map((f) => `${f.family} ${f.weight}`),
    };
  });
  if (report.broken.length) throw new Error(`pictures that do not load: ${report.broken.join(", ")}`);
  if (report.fonts.length) throw new Error(`fonts that do not load: ${report.fonts.join(", ")}`);
  await page.pdf({
    path: out,
    format: "A4",
    preferCSSPageSize: true,
    printBackground: true,
    displayHeaderFooter: true,
    headerTemplate: "<div></div>",
    footerTemplate: '<div style="width:100%;text-align:center;font-size:8pt;color:#7a868f;font-family:sans-serif">' +
      '<span class="pageNumber"></span> / <span class="totalPages"></span></div>',
  });
} finally {
  await browser.close();
  fs.rmSync(tmpDir, { recursive: true, force: true });
}
const size = (fs.statSync(out).size / 1048576).toFixed(1);
console.log(`${path.relative(process.cwd(), out)}: ${size} MB${missing.length ? `, DRAFT: ${missing.length} picture(s) missing` : ""}`);
