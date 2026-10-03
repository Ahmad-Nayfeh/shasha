// Which version this render draws: the Arabic original (default) or the English one. One bundle draws one language,
// chosen by the input prop `lang` ("en"), which Remotion hands to the bundle before any module runs:
//   LANG=en ./render-final.sh …   ·   node tools/stills.mjs out --lang=en …   ·   --props='{"lang":"en"}'
// Arabic stays the default, so the Arabic videos render exactly as before.
//
// English text: every Arabic string the video draws goes through t(); its English is in ../i18n/en-*.json (tour =
// the TV video's feature tour, install = its installation steps, mobile = the phone video), keyed by the Arabic text
// itself (so the Arabic source stays readable). The spoken anchors (beatsFor(scene).at('…')) are mapped in
// ../i18n/anchors-en-*.json, per scene, from the Arabic phrase to the English narration's phrase.
// tools/check_i18n.mjs lists any string or anchor that has no English yet.
import {getInputProps} from 'remotion';
import enInstall from '../i18n/en-install.json';
import enMobile from '../i18n/en-mobile.json';
import enTour from '../i18n/en-tour.json';

export type Lang = 'ar' | 'en';

const readLang = (): Lang => {
  try {
    return (getInputProps() as {lang?: unknown}).lang === 'en' ? 'en' : 'ar';
  } catch {
    return 'ar';
  }
};

export const LANG: Lang = readLang();
export const isEn = LANG === 'en';
/** Text direction of the version: 'rtl' for Arabic, 'ltr' for English. */
export const dir: 'rtl' | 'ltr' = isEn ? 'ltr' : 'rtl';
/** CSS side where a line of text starts ('right' in Arabic, 'left' in English). */
export const startSide: 'right' | 'left' = isEn ? 'left' : 'right';
/** CSS side where a line of text ends. */
export const endSide: 'right' | 'left' = isEn ? 'right' : 'left';
/** +1 in Arabic, -1 in English: flips a horizontal offset or arrow that follows the reading direction. */
export const flowSign = isEn ? -1 : 1;

const EN = {...enTour, ...enInstall, ...enMobile} as unknown as Record<string, string>;
const warned = new Set<string>();

/**
 * The text in this render's language. `ar` is the Arabic text (the key); `vars` fills {name} placeholders in both
 * languages, e.g. t('بقيت {n} دقائق', {n: 5}). A string with no English yet is drawn in Arabic and logged.
 */
export const t = (ar: string, vars?: Record<string, string | number>): string => {
  let s = ar;
  if (isEn) {
    const en = EN[ar];
    if (en === undefined) {
      if (!warned.has(ar)) {
        warned.add(ar);
        console.warn('[i18n] no English for: ' + ar);
      }
    } else s = en;
  }
  if (vars) for (const [k, v] of Object.entries(vars)) s = s.split('{' + k + '}').join(String(v));
  return s;
};

/** Digits for on-screen counters: Arabic-Indic in the Arabic version, Western in the English one. */
export const digits = (n: number | string) =>
  isEn ? String(n) : String(n).replace(/[0-9]/g, (d) => '٠١٢٣٤٥٦٧٨٩'[+d]);
