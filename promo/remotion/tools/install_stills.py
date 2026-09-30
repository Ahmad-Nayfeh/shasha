"""Render the illustrations of the written install guide from the install scenes (captions and grain off) and
save them as 1600 px wide JPGs (Pillow, quality 88, progressive) with the names the guide links to.

  ../.venv/bin/python tools/install_stills.py <out_dir>        (e.g. /home/user/shasha/docs/images/install)
Frames are chosen from the narration beats (tools/beats.py), so they follow a re-synthesised narration.
"""
import math, subprocess, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from beats import Beats  # noqa: E402
from PIL import Image  # noqa: E402  (pip install pillow)

RM = Path(__file__).resolve().parents[1]
out = Path(sys.argv[1] if len(sys.argv) > 1 else RM / 'out/install_stills')
out.mkdir(parents=True, exist_ok=True)


def frames():
    s14 = Beats('s14_devmode')
    t_press, t_show = s14.at('واضغط الزر'), s14.at('حتى تظهر')
    gap = max(7, min(11, (t_show - t_press - 4) // 7))
    taps6 = t_press + 4 + 6 * gap
    s15 = Beats('s15_debugging')
    ok_net = max(s15.at('ففعله') + 2, s15.at('وإن وجدت') + 16)
    s16 = Beats('s16_find_ip')
    s13 = Beats('s13_folder')
    s19 = Beats('s19_type_ip')
    type_at = s19.at('اكتب العنوان') + 6
    s20 = Beats('s20_allow')
    s22 = Beats('s22_automatic')
    s24 = Beats('s24_phone')
    return {
        'tv-settings-about': s14.start + t_press - 2,
        'tv-build-7-presses': s14.start + taps6 + 18,
        'tv-developer-options': s15.start + ok_net + 22,
        'tv-find-ip': s16.start + s16.at('أرقاما') + 2,
        'pc-kit-folder': s13.start + s13.duration - 10,
        'pc-run-script': s19.start + type_at + math.ceil(12 / 0.45) + 4,
        'tv-allow-debugging': s20.start + s20.at('ثم اضغط') + 2,
        'pc-success': s22.start + s22.at('ثم يعرض') + 40,
        'phone-scan-qr': s24.start + s24.at('وامسح') + 30,
    }


if __name__ == '__main__':
    fr = frames()
    only = set(sys.argv[2:])
    specs = [f'{k}:{v}' for k, v in fr.items() if not only or k in only]
    tmp = RM / 'out/.install_png'
    subprocess.run(['node', 'tools/stills.mjs', str(tmp), '--props={"captions":false,"grain":false}', *specs], cwd=RM, check=True)
    for k in fr:
        if only and k not in only:
            continue
        src = tmp / f'{k}.png'
        dst = out / f'{k}.jpg'
        im = Image.open(src).convert('RGB')
        im = im.resize((1600, round(im.height * 1600 / im.width)), Image.LANCZOS)
        im.save(dst, 'JPEG', quality=88, optimize=True, progressive=True)
        print(dst, dst.stat().st_size // 1024, 'KB')
