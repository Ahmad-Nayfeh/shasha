import {continueRender, delayRender, staticFile} from 'remotion';

const faces: Array<[string, string, string]> = [
  ['IBM Plex Sans Arabic', '400', 'IBMPlexSansArabic-Regular.ttf'],
  ['IBM Plex Sans Arabic', '500', 'IBMPlexSansArabic-Medium.ttf'],
  ['IBM Plex Sans Arabic', '600', 'IBMPlexSansArabic-SemiBold.ttf'],
  ['IBM Plex Sans Arabic', '700', 'IBMPlexSansArabic-Bold.ttf'],
  // IBM Plex Mono (Latin subset from Google Fonts, SIL OFL 1.1) for the terminal / code.
  ['IBM Plex Mono', '400', 'IBMPlexMono-400.woff2'],
  ['IBM Plex Mono', '500', 'IBMPlexMono-500.woff2'],
  ['IBM Plex Mono', '600', 'IBMPlexMono-600.woff2'],
  // DejaVu Sans Mono (Bitstream Vera licence, see LICENSE-DejaVu.txt): the monospace face of the app's JVM
  // screenshot tests, used to re-letter a detail of a capture (the TV address in the pairing URL).
  ['Capture Mono', '400', 'DejaVuSansMono.ttf'],
];

let loaded = false;
export const loadFonts = () => {
  if (loaded || typeof document === 'undefined') return;
  loaded = true;
  const handle = delayRender('Loading IBM Plex fonts');
  Promise.all(
    faces.map(([family, weight, file]) => {
      const fmt = file.endsWith('.woff2') ? 'woff2' : 'truetype';
      const face = new FontFace(family, `url(${staticFile('fonts/' + file)}) format('${fmt}')`, {weight});
      return face.load().then((f) => {
        (document.fonts as any).add(f);
      });
    }),
  )
    .then(() => continueRender(handle))
    .catch((e) => {
      console.error(e);
      continueRender(handle);
    });
};
