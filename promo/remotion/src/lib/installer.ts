// Everything the installation scenes show about the kit and the installer, in ONE place so a rename is a
// one-line change. Source of truth: /INSTALL_FLOW.md (v2) and the installers in the repo's Shasha-Setup/ folder
// (their own `say`/`info` lines are quoted below, English line + Arabic line).
import {TV_IP} from './demo';

export const REPO_PATH = 'github.com/Ahmad-Nayfeh/shasha';
export const KIT_ZIP = 'Shasha-Setup.zip';
export const KIT_DIR = 'Shasha-Setup';
export const KIT_ZIP_SIZE = '25.1 MB';

// installer file names (the install agent may fall back to ASCII names: install-windows.bat / install-mac.command)
export const WIN_INSTALLER = 'Windows - ثبّت شاشة.bat';
export const MAC_INSTALLER = 'Mac - ثبّت شاشة.command';
export const LINUX_INSTALLER = 'install-linux.sh';
export const GUIDE_PDF = 'اقرأني - طريقة التثبيت.pdf';
export const KIT_APK = 'shasha-1.0.0-1001.apk';
export const TOOLS_DIR = 'tools';

export type KitKind = 'folder' | 'bat' | 'command' | 'sh' | 'pdf' | 'apk' | 'zip';
export type KitFile = {name: string; kind: KitKind; size: string; label?: string; date: string};
/** The Shasha-Setup folder as a file manager lists it (folders first, then by name). */
export const KIT_FILES: KitFile[] = [
  {name: TOOLS_DIR, kind: 'folder', size: '', label: 'أدوات: لا تلمسها', date: '30/09/2026'},
  {name: MAC_INSTALLER, kind: 'command', size: '34 KB', label: 'مثبّت ماك', date: '30/09/2026'},
  {name: WIN_INSTALLER, kind: 'bat', size: '31 KB', label: 'مثبّت ويندوز', date: '30/09/2026'},
  {name: GUIDE_PDF, kind: 'pdf', size: '2.4 MB', label: 'دليل مصوّر', date: '30/09/2026'},
  {name: LINUX_INSTALLER, kind: 'sh', size: '1 KB', date: '30/09/2026'},
  {name: KIT_APK, kind: 'apk', size: '9.2 MB', label: 'التطبيق', date: '30/09/2026'},
];

/** The prompt the installer shows when it is started by a double-click (no address given). */
export const IP_PROMPT = {
  en: "Type the TV's IP address (Settings > Network & Internet > your network)",
  ar: 'اكتب عنوان IP للشاشة (الإعدادات ← الشبكة والإنترنت ← شبكتك)',
  example: 'Example / مثال: 192.168.1.50',
  label: 'IP: ',
};

export type ConsoleLine = {t: number; text: string; kind?: 'banner' | 'say' | 'ar' | 'info' | 'ok' | 'warn' | 'input' | 'dim' | 'blank'; type?: number};

/** Installer output after the address is typed (connect → allow → install → permissions → YouTube → summary). */
export const consoleAfterIp = (serial = `${TV_IP}:5555`) => ({
  connect: [
    {text: `==> Connecting to ${serial} ...`, kind: 'say'},
    {text: `الاتصال بالشاشة ${serial} ...`, kind: 'ar'},
    {text: `    connected to ${serial}`, kind: 'dim'},
  ],
  allow: [
    {text: '==> Look at the TV: it asks «Allow USB debugging?»', kind: 'say'},
    {text: 'انظر إلى الشاشة: تسأل «Allow USB debugging?»', kind: 'ar'},
    {text: '    Tick «Always allow from this computer», then press OK.', kind: 'info'},
    {text: '    Waiting up to 60 s ... / بانتظار الموافقة حتى 60 ثانية ...', kind: 'dim'},
  ],
  connected: [{text: '    Connected. / تم الاتصال.', kind: 'ok'}],
  install: [
    {text: `==> Installing ${KIT_APK} ...`, kind: 'say'},
    {text: 'تثبيت التطبيق على الشاشة (قد يستغرق دقيقة) ...', kind: 'ar'},
    {text: '    Success', kind: 'ok'},
  ],
  grant: [
    {text: '==> Granting permissions (display over apps, usage access, install updates) ...', kind: 'say'},
    {text: 'منح الصلاحيات: العرض فوق التطبيقات، الوصول إلى الاستخدام، تثبيت التحديثات ...', kind: 'ar'},
    {text: '    SYSTEM_ALERT_WINDOW -> allow', kind: 'ok'},
    {text: '    GET_USAGE_STATS -> allow', kind: 'ok'},
    {text: '    REQUEST_INSTALL_PACKAGES -> allow', kind: 'ok'},
  ],
  youtube: [
    {text: '==> Disable the official YouTube app on the TV? (it can be re-enabled any time)', kind: 'say'},
    {text: 'هل تريد تعطيل تطبيق يوتيوب الرسمي على الشاشة؟ (يمكن إعادته في أي وقت)', kind: 'ar'},
    {text: '    Type y (yes) or n (no), then press Enter. / اكتب y (نعم) أو n (لا) ثم اضغط Enter.', kind: 'dim'},
  ],
  youtubeAnswer: 'Disable YouTube? [y/N] ',
  start: [
    {text: '==> Starting Shasha ...', kind: 'say'},
    {text: 'تشغيل التطبيق ...', kind: 'ar'},
  ],
  summary: [
    {text: '==> Summary', kind: 'say'},
    {text: 'الملخص', kind: 'ar'},
    {text: `    TV / الشاشة:              ${serial}`, kind: 'info'},
    {text: `    APK / الملف:               ${KIT_APK}`, kind: 'info'},
    {text: '    Permissions / الصلاحيات:   granted / ممنوحة', kind: 'ok'},
    {text: '    Guard / الحارس:            running / تعمل', kind: 'ok'},
    {text: '    YouTube / يوتيوب الرسمي:  disabled / معطّل', kind: 'ok'},
    {text: '==> Done! Shasha is installed on the TV.', kind: 'say'},
    {text: 'تمّ! «شاشة» مثبّت على الشاشة.', kind: 'ar'},
    {text: '    Press any key to close. / اضغط أي زر للإغلاق.', kind: 'dim'},
  ],
});

/** Wireless-debugging fallback (the installer's pair_and_connect). */
export const PAIRING = {
  code: '482 913',
  pairAddress: `${TV_IP}:37215`,
  lines: [
    {text: '==> Pairing with a code (Wireless debugging)', kind: 'say'},
    {text: 'الإقران برمز (Wireless debugging)', kind: 'ar'},
    {text: '==> Type the address shown under the code, like 192.168.1.50:37215', kind: 'say'},
    {text: 'اكتب العنوان الظاهر تحت الرمز، مثل 192.168.1.50:37215', kind: 'ar'},
  ],
  addressLabel: 'Pairing address / عنوان الإقران: ',
  codeLabel: 'Code / الرمز: ',
};
