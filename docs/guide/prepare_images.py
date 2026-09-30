"""Makes the small JPG copies of the screenshots that the guide uses (docs/guide/img/).

The guide (guide.html) refers to img/<name>.jpg; <name> is the name of a real screenshot in
app/src/test/screenshots (TV, 1920x1080) or remote/src/test/screenshots (phone page). Smaller JPG copies
keep the PDF light. The install pictures are used as they are, from docs/images/install.

    python3 docs/guide/prepare_images.py      # needs Pillow (pip install Pillow)
"""

import os
import re

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SOURCES = [
    os.path.join(ROOT, "app", "src", "test", "screenshots"),
    os.path.join(ROOT, "remote", "src", "test", "screenshots"),
]
OUT = os.path.join(HERE, "img")
TV_WIDTH = 1280
PHONE_WIDTH = 600


def main():
    html = open(os.path.join(HERE, "guide.html"), encoding="utf-8").read()
    names = sorted(set(re.findall(r'src="img/([\w.-]+)\.jpg"', html)))
    os.makedirs(OUT, exist_ok=True)
    wanted = set()
    for name in names:
        src = next((os.path.join(d, name + ".png") for d in SOURCES if os.path.exists(os.path.join(d, name + ".png"))), None)
        if src is None:
            raise SystemExit(f"no screenshot named {name}.png")
        im = Image.open(src).convert("RGB")
        width = PHONE_WIDTH if im.height > im.width else TV_WIDTH
        if im.width > width:
            im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
        path = os.path.join(OUT, name + ".jpg")
        im.save(path, "JPEG", quality=82, optimize=True, progressive=True)
        wanted.add(name + ".jpg")
        print(f"img/{name}.jpg  {im.width}x{im.height}  {os.path.getsize(path) // 1024} KB")
    for old in sorted(set(os.listdir(OUT)) - wanted):
        os.remove(os.path.join(OUT, old))
        print(f"removed img/{old}")


if __name__ == "__main__":
    main()
