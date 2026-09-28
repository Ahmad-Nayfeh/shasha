#!/usr/bin/env python3
"""Builds every Shasha brand asset from code: SVG sources, PNG exports and Android resources.

    pip install cairosvg fonttools uharfbuzz pillow
    python3 branding/tools/build_brand.py

Outputs (overwritten on every run):
    branding/*.svg, branding/exports/*.png|gif
    app/src/main/res/{drawable,drawable-xhdpi,drawable-xxhdpi,mipmap-*,values}/...  (branding-owned files only)
"""
import io
import math
import os
import sys

import cairosvg
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
import brandgeo as g  # noqa: E402
from brandgeo import Tx, fmt  # noqa: E402
from brandtype import Wordmark, text_path  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BR = os.path.join(ROOT, "branding")
EXP = os.path.join(BR, "exports")
RES = os.path.join(ROOT, "app", "src", "main", "res")
WM = Wordmark()


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def png(svg, path=None, width=None, height=None):
    data = cairosvg.svg2png(bytestring=svg.encode("utf-8"), output_width=width, output_height=height)
    if path:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
    return data


def svg_doc(w, h, body, defs="", title="شاشة"):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {fmt(w)} {fmt(h)}" '
            f'width="{fmt(w)}" height="{fmt(h)}">\n<title>{title}</title>\n'
            f"<defs>{defs}</defs>\n{body}\n</svg>\n")


def lin(id_, x1, y1, x2, y2, c1, c2):
    return (f'<linearGradient id="{id_}" gradientUnits="userSpaceOnUse" x1="{fmt(x1)}" y1="{fmt(y1)}" '
            f'x2="{fmt(x2)}" y2="{fmt(y2)}"><stop offset="0" stop-color="{c1}"/>'
            f'<stop offset="1" stop-color="{c2}"/></linearGradient>')


# ======================================================================================
# SVG building blocks
# ======================================================================================
def mark_svg(t, pfx="m", on_light=False, mono=None):
    """(defs, body) for the mark under transform t. mono = single colour (no gradients)."""
    p = g.mark_paths(t)
    screen_play = p["screen"] + p["play"]
    dots = "".join(p["dots"])
    if mono:
        return "", (f'<path fill="{mono}" fill-rule="evenodd" d="{screen_play}"/>\n'
                    f'<path fill="{mono}" d="{dots}"/>')
    tt, tb = (g.TEAL_ON_LIGHT_TOP, g.TEAL_ON_LIGHT_BOTTOM) if on_light else (g.TEAL_TOP, g.TEAL_BOTTOM)
    at, ab = (g.AMBER_ON_LIGHT_TOP, g.AMBER_ON_LIGHT_BOTTOM) if on_light else (g.AMBER_TOP, g.AMBER_BOTTOM)
    top_dot_y = g.dot_centers()[2][1] - g.DOT / 2
    low_dot_y = g.dot_centers()[0][1] + g.DOT / 2
    defs = (lin(f"{pfx}Screen", 0, t.py(g.SCREEN_TOP), 0, t.py(g.MARK_H), tt, tb)
            + lin(f"{pfx}Dots", 0, t.py(top_dot_y), 0, t.py(low_dot_y), at, ab))
    body = (f'<path fill="url(#{pfx}Screen)" fill-rule="evenodd" d="{screen_play}"/>\n'
            f'<path fill="url(#{pfx}Dots)" d="{dots}"/>')
    return defs, body


def wordmark_svg(s, tx, baseline, pfx="w", color=g.TEXT, on_light=False, mono=None):
    p = WM.paths(s, tx, baseline)
    if mono:
        return "", f'<path fill="{mono}" d="{p["body"]}{p["accent"]}"/>'
    at, ab = (g.AMBER_ON_LIGHT_TOP, g.AMBER_ON_LIGHT_BOTTOM) if on_light else (g.AMBER_TOP, g.AMBER_BOTTOM)
    ys = [baseline - (cy + d / 2) * s for _, cy, d in WM.accent_dots] + [baseline - (cy - d / 2) * s for _, cy, d in WM.accent_dots]
    defs = lin(f"{pfx}Accent", 0, min(ys), 0, max(ys), at, ab)
    return defs, (f'<path fill="{color}" d="{p["body"]}"/>\n'
                  f'<path fill="url(#{pfx}Accent)" d="{p["accent"]}"/>')


# Lockup proportions (mark units): the wordmark's alef is exactly as tall as the screen and
# sits on the screen's bottom edge; gap between wordmark and mark = 1.25 dot.
LOCK_WS = g.SCREEN_H / 740.0            # 740 = alef height in Plex Arabic Bold font units
LOCK_GAP = g.DOT * 1.25
LOCK_W = WM.advance * LOCK_WS + LOCK_GAP + g.MARK_W
LOCK_TOP = 0.0
LOCK_BOTTOM = g.MARK_H - WM.descent * LOCK_WS   # descent is negative


def lockup_svg(scale, x, y, pfx="l", on_light=False, mono=None, text_color=None):
    """Horizontal lockup, RTL: mark on the right (reading start), wordmark to its left."""
    t = Tx(scale, x + (LOCK_W - g.MARK_W) * scale, y)
    d1, b1 = mark_svg(t, pfx + "M", on_light, mono)
    color = text_color or (g.BG if on_light else g.TEXT)
    d2, b2 = wordmark_svg(LOCK_WS * scale, x, y + g.MARK_H * scale, pfx + "W", color, on_light, mono)
    return d1 + d2, b1 + "\n" + b2


STACK_WS = g.MARK_W * 1.2 / WM.advance
STACK_GAP = g.DOT * 0.9


def stacked_svg(scale, cx, y, pfx="s", on_light=False, text_color=None):
    t = Tx(scale, cx - g.MARK_W / 2 * scale, y)
    d1, b1 = mark_svg(t, pfx + "M", on_light)
    ws = STACK_WS * scale
    base = y + (g.MARK_H + STACK_GAP) * scale + WM.ascent * ws
    color = text_color or (g.BG if on_light else g.TEXT)
    d2, b2 = wordmark_svg(ws, cx - WM.advance * ws / 2, base, pfx + "W", color, on_light)
    return d1 + d2, b1 + "\n" + b2, base - WM.descent * ws


# ======================================================================================
# 1. Logo sources + PNG exports
# ======================================================================================
def build_logos():
    out = {}
    # Mark: 1024 square, bounding box centred, 112 margin (> one dot of clear space at this size)
    t = g.fit_box(112, 112, 800, 800)
    d, b = mark_svg(t)
    out["logo-mark.svg"] = svg_doc(1024, 1024, b, d, "شاشة — العلامة")

    dm, bm = mark_svg(t, mono="currentColor")
    out["logo-mono.svg"] = svg_doc(1024, 1024, f'<g style="color:{g.BG}">{bm}</g>', dm, "شاشة — علامة أحادية اللون")

    # Wordmark: padding = half a dot at wordmark scale
    s = 0.4
    pad = 60
    w = WM.advance * s + 2 * pad
    h = WM.height() * s + 2 * pad
    d, b = wordmark_svg(s, pad, pad + WM.ascent * s)
    out["logo-wordmark.svg"] = svg_doc(w, h, b, d, "شاشة — الشعار النصي")
    d, b = wordmark_svg(s, pad, pad + WM.ascent * s, on_light=True, color=g.BG)
    out["logo-wordmark-on-light.svg"] = svg_doc(w, h, b, d, "شاشة — الشعار النصي (خلفية فاتحة)")

    # Horizontal lockup (mark units * 0.5), padding half a dot
    sc = 0.5
    pad = g.DOT * sc / 2
    w = LOCK_W * sc + 2 * pad
    h = LOCK_BOTTOM * sc + 2 * pad
    d, b = lockup_svg(sc, pad, pad)
    out["logo-lockup.svg"] = svg_doc(w, h, b, d, "شاشة — الشعار الكامل")
    d, b = lockup_svg(sc, pad, pad, on_light=True)
    out["logo-lockup-on-light.svg"] = svg_doc(w, h, b, d, "شاشة — الشعار الكامل (خلفية فاتحة)")
    d, b = lockup_svg(sc, pad, pad, pfx="lm", mono="currentColor")
    out["logo-mono-lockup.svg"] = svg_doc(w, h, f'<g style="color:{g.BG}">{b}</g>', d, "شاشة — شعار أحادي اللون")

    # Stacked lockup
    sc = 0.8
    wsw = WM.advance * STACK_WS * sc
    d, b, bottom = stacked_svg(sc, 60 + wsw / 2, 60)
    out["logo-lockup-stacked.svg"] = svg_doc(wsw + 120, bottom + 60, b, d, "شاشة — الشعار العمودي")

    for name, svg in out.items():
        write(os.path.join(BR, name), svg)
        stem = name[:-4]
        for size in (512, 1024):
            png(svg, os.path.join(EXP, f"{stem}-{size}.png"), width=size)
    return out


# ======================================================================================
# 2. Adaptive launcher icon (108dp canvas, 66dp safe circle)
# ======================================================================================
FG_DIAMETER = 58.0     # enclosing circle of the mark, well inside the 66dp safe zone
MONO_DIAMETER = 52.0   # themed (monochrome) icons read better slightly smaller


def icon_background_svg(prefix="bg"):
    """108x108 background layer (also used for the PNG composites)."""
    t = g.fit_circle(FG_DIAMETER, 54, 54)
    cx, cy = g.screen_center()
    gx, gy = t.px(cx), t.py(cy)
    defs = (lin(f"{prefix}Base", 0, 0, 0, 108, "#17212D", g.BG)
            + f'<radialGradient id="{prefix}Glow" gradientUnits="userSpaceOnUse" cx="{fmt(gx)}" cy="{fmt(gy)}" r="46">'
              f'<stop offset="0" stop-color="{g.TEAL}" stop-opacity="0.04"/>'
              f'<stop offset="0.5" stop-color="{g.TEAL}" stop-opacity="0.15"/>'
              f'<stop offset="1" stop-color="{g.TEAL}" stop-opacity="0"/></radialGradient>')
    body = (f'<path fill="url(#{prefix}Base)" d="M0,0H108V108H0Z"/>'
            f'<path fill="url(#{prefix}Glow)" d="M0,0H108V108H0Z"/>')
    return defs, body, (gx, gy)


def build_launcher_icon():
    t = g.fit_circle(FG_DIAMETER, 54, 54, nd=3)
    p = g.mark_paths(t)
    top_dot_y = t.py(g.dot_centers()[2][1] - g.DOT / 2)
    low_dot_y = t.py(g.dot_centers()[0][1] + g.DOT / 2)
    fg = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/tools/build_brand.py. Do not edit by hand. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- TV screen with the play triangle cut out -->
    <path
        android:fillType="evenOdd"
        android:pathData="{p["screen"]}{p["play"]}">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="54" android:startY="{fmt(t.py(g.SCREEN_TOP), 3)}"
                android:endX="54" android:endY="{fmt(t.py(g.MARK_H), 3)}"
                android:startColor="{g.TEAL_TOP}"
                android:endColor="{g.TEAL_BOTTOM}" />
        </aapt:attr>
    </path>
    <!-- The three dots of the letter ش -->
    <path android:pathData="{"".join(p["dots"])}">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="54" android:startY="{fmt(top_dot_y, 3)}"
                android:endX="54" android:endY="{fmt(low_dot_y, 3)}"
                android:startColor="{g.AMBER_TOP}"
                android:endColor="{g.AMBER_BOTTOM}" />
        </aapt:attr>
    </path>
</vector>
'''
    _, _, (gx, gy) = icon_background_svg()
    bg = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/tools/build_brand.py. Do not edit by hand. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0H108V108H0Z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="54" android:startY="0"
                android:endX="54" android:endY="108"
                android:startColor="#17212D"
                android:endColor="{g.BG}" />
        </aapt:attr>
    </path>
    <!-- soft teal glow behind the screen -->
    <path android:pathData="M0,0H108V108H0Z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="{fmt(gx, 3)}"
                android:centerY="{fmt(gy, 3)}"
                android:gradientRadius="46">
                <item android:offset="0" android:color="#0A2EC4B6" />
                <item android:offset="0.5" android:color="#262EC4B6" />
                <item android:offset="1" android:color="#002EC4B6" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''
    tm = g.fit_circle(MONO_DIAMETER, 54, 54, nd=3)
    pm = g.mark_paths(tm)
    mono = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/tools/build_brand.py. Do not edit by hand. -->
<!-- Themed icon layer (Android 13+): one colour, the system tints it. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="{pm["screen"]}{pm["play"]}" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="{"".join(pm["dots"])}" />
</vector>
'''
    adaptive = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/tools/build_brand.py. Do not edit by hand. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''
    write(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), fg)
    write(os.path.join(RES, "drawable", "ic_launcher_background.xml"), bg)
    write(os.path.join(RES, "drawable", "ic_launcher_monochrome.xml"), mono)
    write(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher.xml"), adaptive)
    write(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher_round.xml"), adaptive)
    write(os.path.join(RES, "values", "ic_launcher_background.xml"), f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/tools/build_brand.py. Brand colours used by launcher/splash resources. -->
<resources>
    <color name="ic_launcher_background">{g.BG}</color>
    <!-- windowSplashScreenBackground: same as the app background, so the splash blends into the UI -->
    <color name="brand_splash_background">{g.BG}</color>
</resources>
''')


def icon_composite_svg(shape="full", size=1024, pad_ratio=0.0):
    """Full adaptive composite (background + foreground) as seen through a mask.

    shape: "full" = the 72dp visible square, "square" = rounded square, "circle".
    """
    bd, bb, _ = icon_background_svg("cb")
    t = g.fit_circle(FG_DIAMETER, 54, 54)
    md, mb = mark_svg(t, "cf")
    inset = 18 + 72 * pad_ratio / 2
    span = 72 - 72 * pad_ratio
    clip = ""
    if shape == "circle":
        clip = f'<clipPath id="clip"><circle cx="54" cy="54" r="{fmt(span / 2)}"/></clipPath>'
    elif shape == "square":
        r = span * 0.225
        clip = f'<clipPath id="clip"><rect x="{fmt(inset)}" y="{fmt(inset)}" width="{fmt(span)}" height="{fmt(span)}" rx="{fmt(r)}"/></clipPath>'
    clip_attr = ' clip-path="url(#clip)"' if clip else ""
    body = f"<g{clip_attr}>{bb}{mb}</g>"
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="{size}" height="{size}">'
            f"<defs>{bd}{md}{clip}</defs>{body}</svg>")


def build_legacy_icons():
    # Legacy launcher icons (only used below API 26; kept as a safety net / for tools that read PNGs)
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dens, px in densities.items():
        png(icon_composite_svg("square", px, pad_ratio=4 / 48), os.path.join(RES, f"mipmap-{dens}", "ic_launcher.png"))
        png(icon_composite_svg("circle", px, pad_ratio=4 / 48), os.path.join(RES, f"mipmap-{dens}", "ic_launcher_round.png"))
    # Store-style icons + a rounded preview for docs
    for size in (512, 1024):
        full = icon_composite_svg("full", size)
        png(full, os.path.join(EXP, f"app-icon-{size}.png"))
        png(icon_composite_svg("square", size), os.path.join(EXP, f"app-icon-rounded-{size}.png"))
    write(os.path.join(BR, "app-icon.svg"), icon_composite_svg("full", 1024))


# ======================================================================================
# 3. Android TV banner (320x180 @ xhdpi, 480x270 @ xxhdpi)
# ======================================================================================
def banner_svg(w=1280, h=720):
    # lockup, centred, 80% of the banner width
    target_w = 1280 * 0.80
    sc = target_w / LOCK_W
    lh = LOCK_BOTTOM * sc
    x = (1280 - target_w) / 2
    y = (720 - lh) / 2 - 4
    gx = x + (LOCK_W - g.MARK_W / 2) * sc
    gy = y + (g.SCREEN_TOP + g.SCREEN_H / 2) * sc
    defs = (lin("bnBase", 0, 0, 1280, 720, "#141C27", g.BG)
            + f'<radialGradient id="bnGlow" gradientUnits="userSpaceOnUse" cx="{fmt(gx)}" cy="{fmt(gy)}" r="430">'
              f'<stop offset="0" stop-color="{g.TEAL}" stop-opacity="0.04"/>'
              f'<stop offset="0.42" stop-color="{g.TEAL}" stop-opacity="0.20"/>'
              f'<stop offset="1" stop-color="{g.TEAL}" stop-opacity="0"/></radialGradient>')
    ld, lb = lockup_svg(sc, x, y, "bn")
    body = (f'<rect width="1280" height="720" fill="url(#bnBase)"/>'
            f'<rect width="1280" height="720" fill="url(#bnGlow)"/>{lb}')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1280 720" width="{w}" height="{h}">'
            f"<title>شاشة</title><defs>{defs}{ld}</defs>{body}</svg>")


def build_banner():
    master = banner_svg()
    write(os.path.join(BR, "banner.svg"), master)
    png(master, os.path.join(EXP, "banner-1280x720.png"), 1280, 720)
    for folder, (w, h) in {"drawable-xhdpi": (320, 180), "drawable-xxhdpi": (480, 270)}.items():
        data = png(master, None, w, h)
        # store as opaque RGB PNG (banners must not be transparent)
        im = Image.open(io.BytesIO(data)).convert("RGB")
        os.makedirs(os.path.join(RES, folder), exist_ok=True)
        im.save(os.path.join(RES, folder, "banner.png"), optimize=True)
    Image.open(io.BytesIO(png(master, None, 320, 180))).convert("RGB").save(os.path.join(EXP, "banner-320x180.png"))


# ======================================================================================
# 4. Splash (Android 12 SplashScreen API): 240dp canvas, content inside the 160dp circle
# ======================================================================================
SPLASH_DIAMETER = 150.0
# Animation timeline (ms). Total must stay <= 900.
DOT_DROP = 300          # each dot
DOT_STAGGER = 120
DOT_RISE = 26.0         # start offset above the final position (viewport units)
PLAY_START = 520
PLAY_DUR = 360
TOTAL = PLAY_START + PLAY_DUR   # 880


def _splash_parts(nd=3):
    t = g.fit_circle(SPLASH_DIAMETER, 120, 120, nd=nd)
    p = g.mark_paths(t)
    return t, p


def _splash_vector(t, p, named=False):
    top_dot_y = t.py(g.dot_centers()[2][1] - g.DOT / 2)
    low_dot_y = t.py(g.dot_centers()[0][1] + g.DOT / 2)
    pcx, pcy = g.play_center()

    def dot_grad():
        return f'''
                <aapt:attr name="android:fillColor">
                    <gradient
                        android:type="linear"
                        android:startX="120" android:startY="{fmt(top_dot_y, 3)}"
                        android:endX="120" android:endY="{fmt(low_dot_y, 3)}"
                        android:startColor="{g.AMBER_TOP}"
                        android:endColor="{g.AMBER_BOTTOM}" />
                </aapt:attr>'''

    dots = ""
    for i, d in enumerate(p["dots"], 1):
        dots += f'''
        <group android:name="dot{i}">
            <path
                android:name="dot{i}_path"
                android:pathData="{d}">{dot_grad()}
            </path>
        </group>'''
    return f'''<vector
            android:width="240dp"
            android:height="240dp"
            android:viewportWidth="240"
            android:viewportHeight="240">
        <path
            android:name="screen"
            android:pathData="{p["screen"]}">
            <aapt:attr name="android:fillColor">
                <gradient
                    android:type="linear"
                    android:startX="120" android:startY="{fmt(t.py(g.SCREEN_TOP), 3)}"
                    android:endX="120" android:endY="{fmt(t.py(g.MARK_H), 3)}"
                    android:startColor="{g.TEAL_TOP}"
                    android:endColor="{g.TEAL_BOTTOM}" />
            </aapt:attr>
        </path>
        <group
            android:name="play"
            android:pivotX="{fmt(t.px(pcx), 3)}"
            android:pivotY="{fmt(t.py(pcy), 3)}">
            <path
                android:name="play_path"
                android:fillColor="{g.BG}"
                android:pathData="{p["play"]}" />
        </group>{dots}
    </vector>'''


def _kf(fraction, value, interp=None):
    i = f' android:interpolator="{interp}"' if interp else ""
    return f'<keyframe android:fraction="{fmt(fraction, 4)}" android:value="{fmt(value, 3)}"{i} />'


def _target(name, holders):
    pvh = ""
    for prop, frames in holders:
        pvh += f'''
                    <propertyValuesHolder android:propertyName="{prop}" android:valueType="floatType">
                        {"""
                        """.join(frames)}
                    </propertyValuesHolder>'''
    return f'''
    <target android:name="{name}">
        <aapt:attr name="android:animation">
            <objectAnimator android:duration="{TOTAL}">{pvh}
            </objectAnimator>
        </aapt:attr>
    </target>'''


FOSI = "@android:interpolator/fast_out_slow_in"
LOSI = "@android:interpolator/linear_out_slow_in"


def splash_timeline():
    """Keyframes shared by the AVD and the GIF preview: {target: [(prop, [(fraction, value, interp)])]}"""
    tl = {}
    for i in range(3):
        start = i * DOT_STAGGER / TOTAL
        land = (i * DOT_STAGGER + DOT_DROP * 0.72) / TOTAL
        end = (i * DOT_STAGGER + DOT_DROP) / TOTAL
        fade = (i * DOT_STAGGER + DOT_DROP * 0.45) / TOTAL
        tl[f"dot{i + 1}"] = [("translateY", [(0, -DOT_RISE, None), (start, -DOT_RISE, None),
                                             (land, 1.6, FOSI), (end, 0, FOSI), (1, 0, None)])]
        tl[f"dot{i + 1}_path"] = [("fillAlpha", [(0, 0, None), (start, 0, None), (fade, 1, LOSI), (1, 1, None)])]
    ps, pe = PLAY_START / TOTAL, 1.0
    tl["play"] = [("scaleX", [(0, 0.55, None), (ps, 0.55, None), (pe, 1, FOSI)]),
                  ("scaleY", [(0, 0.55, None), (ps, 0.55, None), (pe, 1, FOSI)])]
    tl["play_path"] = [("fillAlpha", [(0, 0, None), (ps, 0, None), (ps + (pe - ps) * 0.6, 1, LOSI), (1, 1, None)])]
    return tl


def build_splash():
    t, p = _splash_parts()
    static = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/tools/build_brand.py. Do not edit by hand. -->
<!-- SplashScreen icon (static): 240dp canvas, the mark fits inside the central 160dp circle. -->
{_splash_vector(t, p).replace("<vector", '<vector xmlns:android="http://schemas.android.com/apk/res/android"' + chr(10) + '            xmlns:aapt="http://schemas.android.com/aapt"', 1)}
'''
    targets = "".join(_target(name, [(prop, [_kf(*f) for f in frames]) for prop, frames in holders])
                      for name, holders in splash_timeline().items())
    animated = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/tools/build_brand.py. Do not edit by hand. -->
<!-- SplashScreen icon (animated, {TOTAL} ms): the three dots of ش drop in one by one, then the play
     triangle fades and scales in. The static state is the final frame, so it also looks right
     where the animation does not run. Use windowSplashScreenAnimationDuration = {TOTAL}. -->
<animated-vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt">
    <aapt:attr name="android:drawable">
        {_splash_vector(t, p)}
    </aapt:attr>{targets}
</animated-vector>
'''
    write(os.path.join(RES, "drawable", "splash_logo.xml"), static)
    write(os.path.join(RES, "drawable", "splash_logo_animated.xml"), animated)


def _vector_paths_xml(parts, w, h):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/tools/build_brand.py. Do not edit by hand. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="{fmt(w)}dp"
    android:height="{fmt(h)}dp"
    android:viewportWidth="{fmt(w)}"
    android:viewportHeight="{fmt(h)}">{parts}
</vector>
'''


def _grad_attr(x, y1, y2, c1, c2):
    return f'''
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="{fmt(x, 3)}" android:startY="{fmt(y1, 3)}"
                android:endX="{fmt(x, 3)}" android:endY="{fmt(y2, 3)}"
                android:startColor="{c1}"
                android:endColor="{c2}" />
        </aapt:attr>'''


def _wordmark_xml(s, tx, base):
    wp = WM.paths(s, tx, base, nd=2)
    ys = [base - (cy + d / 2) * s for _, cy, d in WM.accent_dots] + [base - (cy - d / 2) * s for _, cy, d in WM.accent_dots]
    return f'''
    <path
        android:fillColor="{g.TEXT}"
        android:pathData="{wp["body"]}" />
    <path android:pathData="{wp["accent"]}">{_grad_attr(tx, min(ys), max(ys), g.AMBER_TOP, g.AMBER_BOTTOM)}
    </path>'''


def _mark_xml(t):
    p = g.mark_paths(t)
    top_dot_y = t.py(g.dot_centers()[2][1] - g.DOT / 2)
    low_dot_y = t.py(g.dot_centers()[0][1] + g.DOT / 2)
    x = t.px(g.SCREEN_W / 2)
    return f'''
    <path
        android:fillType="evenOdd"
        android:pathData="{p["screen"]}{p["play"]}">{_grad_attr(x, t.py(g.SCREEN_TOP), t.py(g.MARK_H), g.TEAL_TOP, g.TEAL_BOTTOM)}
    </path>
    <path android:pathData="{"".join(p["dots"])}">{_grad_attr(x, top_dot_y, low_dot_y, g.AMBER_TOP, g.AMBER_BOTTOM)}
    </path>'''


def build_brand_vectors():
    # Android 12 splash branding image (200x80dp) — also usable as the in-app wordmark.
    s = 60 / WM.height()
    w = WM.advance * s
    xml = _wordmark_xml(s, (200 - w) / 2, 10 + WM.ascent * s)
    write(os.path.join(RES, "drawable", "splash_wordmark.xml"), _vector_paths_xml(xml, 200, 80))
    # Horizontal lockup for in-app headers / about screen (on dark backgrounds).
    sc = 48 / LOCK_BOTTOM
    lw = LOCK_W * sc
    t = Tx(sc, (lw - g.MARK_W * sc), 0, nd=2)
    xml = _mark_xml(t) + _wordmark_xml(LOCK_WS * sc, 0, g.MARK_H * sc)
    write(os.path.join(RES, "drawable", "splash_lockup.xml"), _vector_paths_xml(xml, lw, 48))


# ======================================================================================
# 5. Fonts (family XML so code can use R.font.plex_arabic)
# ======================================================================================
def build_font_family():
    write(os.path.join(RES, "font", "plex_arabic.xml"), '''<?xml version="1.0" encoding="utf-8"?>
<!-- IBM Plex Sans Arabic (SIL OFL 1.1, see branding/fonts/OFL.txt) -->
<font-family xmlns:android="http://schemas.android.com/apk/res/android">
    <font android:font="@font/plex_arabic_regular" android:fontStyle="normal" android:fontWeight="400" />
    <font android:font="@font/plex_arabic_medium" android:fontStyle="normal" android:fontWeight="500" />
    <font android:font="@font/plex_arabic_semibold" android:fontStyle="normal" android:fontWeight="600" />
    <font android:font="@font/plex_arabic_bold" android:fontStyle="normal" android:fontWeight="700" />
</font-family>
''')


# ======================================================================================
# 6. Previews: splash animation GIF + brand sheet
# ======================================================================================
def _cubic(p1x, p1y, p2x, p2y):
    def bez(t, a, b):
        return 3 * a * (1 - t) ** 2 * t + 3 * b * (1 - t) * t ** 2 + t ** 3

    def f(x):
        lo, hi = 0.0, 1.0
        for _ in range(40):
            mid = (lo + hi) / 2
            if bez(mid, p1x, p2x) < x:
                lo = mid
            else:
                hi = mid
        return bez((lo + hi) / 2, p1y, p2y)
    return f


EASE = {FOSI: _cubic(0.4, 0, 0.2, 1), LOSI: _cubic(0, 0, 0.2, 1), None: lambda x: x}


def _sample(frames, fr):
    for (f0, v0, _), (f1, v1, i1) in zip(frames, frames[1:]):
        if f0 <= fr <= f1:
            if f1 == f0:
                return v1
            return v0 + (v1 - v0) * EASE[i1]((fr - f0) / (f1 - f0))
    return frames[-1][1]


def splash_frame_svg(ms, size=480, background=g.BG):
    t, p = _splash_parts()
    tl = splash_timeline()
    fr = min(1.0, ms / TOTAL)
    val = {name: {prop: _sample(frames, fr) for prop, frames in holders} for name, holders in tl.items()}
    md, _ = mark_svg(t, "sp")
    pcx, pcy = t.px(g.play_center()[0]), t.py(g.play_center()[1])
    sx = val["play"]["scaleX"]
    body = f'<rect width="240" height="240" fill="{background}"/><path fill="url(#spScreen)" d="{p["screen"]}"/>'
    body += (f'<g transform="translate({fmt(pcx)},{fmt(pcy)}) scale({fmt(sx, 4)}) translate({fmt(-pcx)},{fmt(-pcy)})">'
             f'<path fill="{g.BG}" fill-opacity="{fmt(val["play_path"]["fillAlpha"], 3)}" d="{p["play"]}"/></g>')
    for i, d in enumerate(p["dots"], 1):
        body += (f'<g transform="translate(0,{fmt(val[f"dot{i}"]["translateY"], 3)})">'
                 f'<path fill="url(#spDots)" fill-opacity="{fmt(val[f"dot{i}_path"]["fillAlpha"], 3)}" d="{d}"/></g>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 240" width="{size}" height="{size}">'
            f"<defs>{md}</defs>{body}</svg>")


def build_splash_preview():
    frames = []
    step = 20
    for ms in list(range(0, TOTAL + step, step)) + [TOTAL] * 45:   # hold the final frame ~0.9 s
        frames.append(Image.open(io.BytesIO(png(splash_frame_svg(min(ms, TOTAL), 360)))).convert("RGB"))
    frames[0].save(os.path.join(EXP, "splash-animation.gif"), save_all=True, append_images=frames[1:],
                   duration=step, loop=0, optimize=True)
    # key frames strip for quick review
    strip = Image.new("RGB", (6 * 240, 240))
    for i, ms in enumerate([0, 150, 300, 480, 650, TOTAL]):
        strip.paste(Image.open(io.BytesIO(png(splash_frame_svg(ms, 240)))).convert("RGB"), (i * 240, 0))
    strip.save(os.path.join(EXP, "splash-keyframes.png"))


def build_brand_sheet():
    W, H = 1920, 1080
    parts, defs = [], []
    parts.append(f'<rect width="{W}" height="{H}" fill="{g.BG}"/>')
    # Hero lockup
    sc = 900 / LOCK_W
    d, b = lockup_svg(sc, (W - 900) / 2 + 0, 110, "hs")
    defs.append(d)
    parts.append(b)
    # section titles (right aligned, RTL)
    def label(text, x, y, size=26, weight="medium", color=g.MUTED, anchor="end"):
        pd, _ = text_path(text, size, x, y, weight, anchor)
        parts.append(f'<path fill="{color}" d="{pd}"/>')

    y0 = 470
    label("الألوان", W - 120, y0)
    swatches = [("الخلفية", g.BG), ("السطح", g.SURFACE), ("الأساسي", g.TEAL), ("الثانوي", g.AMBER),
                ("الأطفال", "#FFD166"), ("الخطأ", "#FF6B6B"), ("النص", g.TEXT), ("الخافت", g.MUTED)]
    sw, gap = 170, 24
    x = W - 120
    for name, col in swatches:
        x -= sw
        parts.append(f'<rect x="{x}" y="{y0 + 30}" width="{sw}" height="120" rx="22" fill="{col}" stroke="#2A3442" stroke-width="2"/>')
        label(name, x + sw - 16, y0 + 190, 24, "semibold", g.TEXT)
        label(col, x + sw - 16, y0 + 222, 20, "regular", g.MUTED)
        x -= gap
    # typography (Arabic and Latin set as separate runs: no bidi reordering needed)
    y1 = 800
    label("الخط", W - 120, y1)
    _, lw = text_path("الخط", 26, 0, 0, "medium")
    label("IBM Plex Sans Arabic · SIL OFL 1.1", W - 120 - lw - 14, y1, 22, "regular")
    samples = [("bold", "شاشة بلا إعلانات", "Bold 700"), ("semibold", "اشتراكاتك أولاً", "SemiBold 600"),
               ("regular", "حصة يومية 45 دقيقة", "Regular 400")]
    for i, (wname, txt, cap) in enumerate(samples):
        label(txt, W - 120 - i * 440, y1 + 84, 44, wname, g.TEXT)
        label(cap, W - 120 - i * 440, y1 + 124, 20, "regular", g.MUTED)
    # launcher icon in two masks, bottom-left
    import base64
    for i, shape in enumerate(("square", "circle")):
        data = base64.b64encode(png(icon_composite_svg(shape, 150))).decode()
        parts.append(f'<image x="{120 + i * 175}" y="{y1 - 10}" width="150" height="150" href="data:image/png;base64,{data}"/>')
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 {W} {H}" width="{W}" height="{H}">'
           f'<defs>{"".join(defs)}</defs>{"".join(parts)}</svg>')
    png(svg, os.path.join(EXP, "brand-sheet.png"))


def main():
    build_font_family()
    build_logos()
    build_launcher_icon()
    build_legacy_icons()
    build_banner()
    build_splash()
    build_brand_vectors()
    build_splash_preview()
    build_brand_sheet()
    print("brand assets built")


if __name__ == "__main__":
    main()
