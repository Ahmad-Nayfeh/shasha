import {Config} from '@remotion/cli/config';
import fs from 'node:fs';

// Use a preinstalled Playwright Chromium headless shell when there is one (Remotion's own download may be
// blocked on the build machine); elsewhere Remotion downloads its own browser.
const PW_CHROME = '/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell';
if (fs.existsSync(PW_CHROME)) Config.setBrowserExecutable(PW_CHROME);
// The machine is shared with Android builds: one frame at a time.
Config.setConcurrency(1);
Config.setVideoImageFormat('jpeg');
Config.setJpegQuality(92);
Config.setCodec('h264');
Config.setCrf(20);
Config.setPixelFormat('yuv420p');
Config.setDelayRenderTimeoutInMilliseconds(60000);
