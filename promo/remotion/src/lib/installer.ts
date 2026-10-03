// Everything the installation scenes show about the kit and the installer, in ONE place so a rename is a
// one-line change. Source of truth: the installers and the guide in the repo's Shasha-Setup/ folder (their own
// `say`/`info` lines are quoted below, English line + Arabic line; the English version of the video draws the English
// line of each pair). The names and sizes are those of the kit of release 1.2.1 (shasha-1.2.1-1066.apk): after a new
// release change KIT_TAG, KIT_APK and the sizes, then re-render the install scenes.
//   sizes: MB = bytes / 1048576 to one decimal, KB = bytes / 1024 rounded up (as a file manager lists them).
import {TV_IP} from './demo';
import {isEn, t} from './lang';

export const REPO_PATH = 'github.com/Ahmad-Nayfeh/shasha';
export const KIT_ZIP = 'Shasha-Setup.zip';
export const KIT_DIR = 'Shasha-Setup';
export const KIT_ZIP_SIZE = '45.1 MB'; // 47 320 977 bytes
export const KIT_DATE = '03/10/2026';
export const KIT_TAG = '1.2.1';

// installer file names: the real names of the files in the kit, the same in both versions (through t() only so that the
// checker sees them; their English is the name itself)
export const WIN_INSTALLER = t('Windows - ثبّت شاشة.bat');
export const MAC_INSTALLER = t('Mac - ثبّت شاشة.command');
export const LINUX_INSTALLER = 'install-linux.sh';
export const GUIDE_PDF = t('دليل شاشة.pdf');
export const GUIDE_PDF_EN = 'Shasha Guide (English).pdf';
/** The guide the video points at: the Arabic one in the Arabic version, the English one in the English version. */
export const GUIDE_FILE = isEn ? GUIDE_PDF_EN : GUIDE_PDF;
export const KIT_APK = 'shasha-1.2.1-1066.apk';
export const PHONE_DIR = 'Phone';
export const PHONE_APK = 'shasha-mobile-1.2.1-1066.apk'; // in Shasha-Setup/Phone/
export const PHONE_APK_SIZE = '4.0 MB'; // 4 230 678 bytes
export const TOOLS_DIR = 'tools';
/** What the installer prints for the TV (maker, model, Android version): an example. */
export const TV_NAME = 'Sony BRAVIA 4K VH2, Android 12 (API 31)';

export type KitKind = 'folder' | 'bat' | 'command' | 'sh' | 'pdf' | 'apk' | 'zip';
export type KitFile = {name: string; kind: KitKind; size: string; label?: string; date: string};
/** Row height of the kit's file list in the install scenes (8 rows must fit in the laptop window). */
export const KIT_ROW_H = 54;
/** The Shasha-Setup folder as a file manager lists it (folders first, then by name). */
export const KIT_FILES: KitFile[] = [
  {name: PHONE_DIR, kind: 'folder', size: '', label: t('تطبيق الجوال'), date: KIT_DATE},
  {name: TOOLS_DIR, kind: 'folder', size: '', label: t('أدوات: لا تلمسها'), date: KIT_DATE},
  {name: LINUX_INSTALLER, kind: 'sh', size: '1 KB', date: KIT_DATE}, // 508 bytes
  {name: MAC_INSTALLER, kind: 'command', size: '41 KB', label: t('مثبّت ماك'), date: KIT_DATE}, // 41 962 bytes
  {name: GUIDE_PDF_EN, kind: 'pdf', size: '3.0 MB', label: isEn ? t('الدليل') : undefined, date: KIT_DATE}, // 3 107 852 bytes; «the guide» is this one in the English version
  {name: KIT_APK, kind: 'apk', size: '21.2 MB', label: t('التطبيق'), date: KIT_DATE}, // 22 266 149 bytes
  {name: WIN_INSTALLER, kind: 'bat', size: '46 KB', label: t('مثبّت ويندوز'), date: KIT_DATE}, // 46 195 bytes
  {name: GUIDE_PDF, kind: 'pdf', size: '3.1 MB', label: isEn ? undefined : t('الدليل'), date: KIT_DATE}, // 3 200 385 bytes; «the guide» in the Arabic version
];

export type ConsoleLine = {t: number; text: string; kind?: 'banner' | 'say' | 'ar' | 'info' | 'ok' | 'warn' | 'input' | 'dim' | 'blank'; type?: number};

/** Console lines of a group, in this version's language: the English version draws only the English lines. */
const forLang = <T extends {kind?: string}>(lines: T[]): T[] => (isEn ? lines.filter((l) => l.kind !== 'ar') : lines);

/** The installer's opening lines: its banner, «Checking adb ...» and that line in Arabic (Arabic version only). `at` = the frame of each. */
export const openingLines = (at: [number, number, number]): ConsoleLine[] => [
  {t: at[0], text: t('  Shasha installer / مثبّت «شاشة»'), kind: 'banner'},
  {t: at[1], text: '==> Checking adb ...', kind: 'say'},
  ...forLang<ConsoleLine>([{t: at[2], text: t('التحقق من وجود adb ...'), kind: 'ar'}]),
];

/** The prompt the installer shows when it is started by a double-click (no address given). */
export const IP_PROMPT = {
  en: "Type the TV's IP address (Settings > Network & Internet > your network)",
  ar: t('اكتب عنوان IP للشاشة (الإعدادات ← الشبكة والإنترنت ← شبكتك)'),
  example: t('Example / مثال: 192.168.1.50'),
  label: 'IP: ',
};
/** What the address is typed after: the label with the console's indent in the Arabic version, as the installer prints it in the English one. */
export const IP_PROMPT_LABEL = isEn ? IP_PROMPT.label : `    ${IP_PROMPT.label}`;

/**
 * The question for the TV's address, as console lines around frame `at` (the typed address follows in the scene). The
 * English version draws the installer's real lines (its Google TV lines: the Fire TV line is left out); the Arabic one
 * keeps its own short form.
 */
export const ipPromptLines = (at: number): ConsoleLine[] =>
  isEn
    ? [
        {t: at - 4, text: "==> Type the TV's IP address", kind: 'say'},
        {t: at - 1, text: '    - Google TV / Android TV: Settings > Network & Internet > your Wi-Fi > IP address', kind: 'info'},
        {t: at + 1, text: '      (with a cable: Ethernet > under «Connected»).', kind: 'info'},
        {t: at + 2, text: '    Example: 192.168.1.50', kind: 'dim'},
      ]
    : [
        {t: at - 4, text: `==> ${IP_PROMPT.en}`, kind: 'say'},
        {t: at - 1, text: IP_PROMPT.ar, kind: 'ar'},
        {t: at + 2, text: `    ${IP_PROMPT.example}`, kind: 'dim'},
      ];

/** Installer output after the address is typed (connect → allow → install → permissions → YouTube → summary). */
export const consoleAfterIp = (serial = `${TV_IP}:5555`) => ({
  connect: forLang([
    {text: `==> Connecting to ${serial} ...`, kind: 'say'},
    {text: t('الاتصال بالشاشة {serial} ...', {serial}), kind: 'ar'},
    {text: `    connected to ${serial}`, kind: 'dim'},
  ]),
  allow: forLang([
    {text: '==> Look at the TV: it asks «Allow USB debugging?» (Fire TV asks the same)', kind: 'say'},
    {text: t('انظر إلى الشاشة: تسأل «Allow USB debugging?» (وFire TV تسأل السؤال نفسه)'), kind: 'ar'},
    {text: isEn ? '    With the remote, tick «Always allow from this computer», then press OK.' : '    Tick «Always allow from this computer», then press OK.', kind: 'info'},
    {text: t('    Waiting up to 60 s ... / بانتظار الموافقة حتى 60 ثانية ...'), kind: 'dim'},
  ]),
  connected: forLang([
    {text: t('    Connected. / تم الاتصال.'), kind: 'ok'},
    {text: `==> The TV: ${TV_NAME}`, kind: 'say'},
    {text: t('الشاشة: {name}', {name: TV_NAME}), kind: 'ar'},
  ]),
  install: forLang([
    {text: isEn ? '==> Installing the APK ...' : `==> Installing ${KIT_APK} ...`, kind: 'say'},
    {text: t('تثبيت التطبيق على الشاشة (قد يستغرق دقيقة) ...'), kind: 'ar'},
    {text: '    Success', kind: 'ok'},
  ]),
  grant: forLang([
    {text: isEn ? '==> Granting permissions: display over apps, usage access, install updates ...' : '==> Granting permissions (display over apps, usage access, install updates) ...', kind: 'say'},
    {text: t('منح الصلاحيات: العرض فوق التطبيقات، الوصول إلى الاستخدام، تثبيت التحديثات ...'), kind: 'ar'},
    ...['SYSTEM_ALERT_WINDOW', 'GET_USAGE_STATS', 'REQUEST_INSTALL_PACKAGES'].map((op) => ({text: isEn ? `    ${op}: allow` : `    ${op} -> allow`, kind: 'ok'})),
  ]),
  youtube: forLang([
    {text: '==> Disable the official YouTube app on the TV? (it can be re-enabled any time)', kind: 'say'},
    {text: t('هل تريد تعطيل تطبيق يوتيوب الرسمي على الشاشة؟ (يمكن إعادته في أي وقت)'), kind: 'ar'},
    {text: t('    Type y (yes) or n (no), then press Enter. / اكتب y (نعم) أو n (لا) ثم اضغط Enter.'), kind: 'dim'},
  ]),
  youtubeAnswer: 'Disable YouTube? [y/N] ',
  /** The y/n question's label as the console prints it before the typed answer. */
  youtubePrompt: `${isEn ? '' : '    '}Disable YouTube? [y/N] `,
  disabled: forLang([
    {text: t('    Disabled. To bring it back: / للإعادة:  (see the guide)'), kind: 'dim'},
    ...(isEn ? [{text: `        adb -s ${serial} shell pm enable com.google.android.youtube.tv`, kind: 'dim'}] : []),
  ]),
  start: forLang([
    {text: '==> Starting Shasha ...', kind: 'say'},
    {text: t('تشغيل التطبيق ...'), kind: 'ar'},
  ]),
  summary: forLang([
    {text: '==> Summary', kind: 'say'},
    {text: t('الملخص'), kind: 'ar'},
    {text: t('    TV / الشاشة:              {serial}', {serial}), kind: 'info'},
    {text: t('    Model / الطراز:            {name}', {name: TV_NAME}), kind: 'info'},
    {text: t('    APK / الملف:               {apk}', {apk: KIT_APK}), kind: 'info'},
    {text: t('    Permissions / الصلاحيات:   granted / ممنوحة'), kind: 'ok'},
    {text: t('    Guard / الحارس:            running / تعمل'), kind: 'ok'},
    {text: t('    YouTube / يوتيوب الرسمي:  disabled / معطّل'), kind: 'ok'},
    {text: '==> Done! Shasha is installed on the TV.', kind: 'say'},
    {text: t('تمّ! «شاشة» مثبّت على الشاشة.'), kind: 'ar'},
    {text: t('    Press any key to close. / اضغط أي زر للإغلاق.'), kind: 'dim'},
  ]),
});

/** Wireless-debugging fallback (the installer's pair_and_connect). */
export const PAIRING = {
  code: '482 913',
  pairAddress: `${TV_IP}:37215`,
  lines: forLang([
    {text: '==> Pairing with a code (Wireless debugging)', kind: 'say'},
    {text: t('الإقران برمز (Wireless debugging)'), kind: 'ar'},
    {text: '==> Type the address shown under the code, like 192.168.1.50:37215', kind: 'say'},
    {text: t('اكتب العنوان الظاهر تحت الرمز، مثل 192.168.1.50:37215'), kind: 'ar'},
  ]),
  addressLabel: t('Pairing address / عنوان الإقران: '),
  codeLabel: t('Code / الرمز: '),
};
