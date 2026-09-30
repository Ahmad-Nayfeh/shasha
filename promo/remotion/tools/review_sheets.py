"""Review stills of every scene: three frames per scene (early, middle, late), rendered with captions, then packed
into contact sheets (3x3 at 640x360 each) to LOOK at.

  ../.venv/bin/python tools/review_sheets.py <out_dir> [scene_id ...]
"""
import json, subprocess, sys
from pathlib import Path

RM = Path(__file__).resolve().parents[1]
out = Path(sys.argv[1])
only = set(sys.argv[2:])
tl = json.loads((RM / 'src/data/timeline.json').read_text())
specs, names = [], []
for sc in tl['scenes']:
    if only and sc['id'] not in only:
        continue
    d = sc['duration_frames']
    for tag, rel in (('a', int(d * 0.3)), ('b', int(d * 0.62)), ('c', d - 12)):
        specs.append(f'{sc["id"]}@{rel}')
        names.append(f'{sc["id"]}_f{rel}')
png = out / 'png'
subprocess.run(['node', 'tools/stills.mjs', str(png), *specs], cwd=RM, check=True)
from PIL import Image, ImageDraw  # noqa: E402
W, H = 640, 360
for k in range(0, len(names), 9):
    sheet = Image.new('RGB', (W * 3, H * 3), (5, 8, 11))
    for j, n in enumerate(names[k:k + 9]):
        im = Image.open(png / f'{n}.png').convert('RGB').resize((W, H), Image.LANCZOS)
        ImageDraw.Draw(im).text((8, 6), n, fill=(255, 200, 80))
        sheet.paste(im, ((j % 3) * W, (j // 3) * H))
    p = out / f'sheet_{k // 9:02d}.jpg'
    sheet.save(p, quality=85)
    print(p)
