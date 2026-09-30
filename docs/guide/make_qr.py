"""Draws the QR codes of the developer's other apps («حول» / "About" in both guides) into docs/guide/qr/.

The SVGs are kept in the repository, so the guide builds without this script; run it only when a link changes.

    python3 docs/guide/make_qr.py      # needs the qrcode package (pip install qrcode)
"""

import os

import qrcode
import qrcode.image.svg

HERE = os.path.dirname(os.path.abspath(__file__))
LINKS = {
    "shadhra-site": "https://shadhra.app",
    "shadhra-play": "https://play.google.com/store/apps/details?id=app.shadhra",
    "silah": "https://github.com/Ahmad-Nayfeh/silah",
    "masarif": "https://github.com/Ahmad-Nayfeh/masarif",
    "khatwa": "https://github.com/Ahmad-Nayfeh/khatwa",
}


def main():
    out = os.path.join(HERE, "qr")
    os.makedirs(out, exist_ok=True)
    for name, url in LINKS.items():
        qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=2,
                           image_factory=qrcode.image.svg.SvgPathFillImage)
        qr.add_data(url)
        qr.make(fit=True)
        path = os.path.join(out, name + ".svg")
        qr.make_image().save(path)
        with open(path, encoding="utf-8") as f:  # no hairlines between the modules
            svg = f.read().replace("<svg ", '<svg shape-rendering="crispEdges" ', 1)
        with open(path, "w", encoding="utf-8") as f:
            f.write(svg)
        print(f"qr/{name}.svg  {url}")


if __name__ == "__main__":
    main()
