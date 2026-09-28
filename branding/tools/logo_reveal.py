#!/usr/bin/env python3
"""Renders the Shasha logo reveal (motion logo sting) to MP4 + a poster frame.

    pip install cairosvg fonttools uharfbuzz pillow imageio-ffmpeg
    python3 branding/tools/logo_reveal.py

Output: branding/exports/logo-reveal-1080p60.mp4, branding/exports/logo-reveal-poster.png
Storyboard (3.6 s): screen appears -> the three dots of ش drop in -> play appears ->
the mark slides to its lockup position while the wordmark is revealed right-to-left (RTL)
-> the accent dots of the wordmark pop -> hold.
"""
import os
import subprocess
import sys
import tempfile

import cairosvg

sys.path.insert(0, os.path.dirname(__file__))
import brandgeo as g  # noqa: E402
from brandgeo import Tx, fmt  # noqa: E402
from build_brand import EASE, EXP, FOSI, LOCK_BOTTOM, LOCK_GAP, LOCK_W, LOCK_WS, LOSI, WM, lin  # noqa: E402

W, H, FPS = 1920, 1080, 60
DURATION = 3.6
SC = 1100 / LOCK_W                      # final lockup is 1100 px wide
LX = (W - LOCK_W * SC) / 2
LY = (H - LOCK_BOTTOM * SC) / 2
MARK_FINAL_X = LX + (LOCK_W - g.MARK_W) * SC
MARK_START_X = (W - g.MARK_W * SC) / 2


def ease(name, x):
    return EASE[name](min(1.0, max(0.0, x)))


def phase(t, start, dur):
    return min(1.0, max(0.0, (t - start) / dur))


def back_out(x, s=1.4):
    x = min(1.0, max(0.0, x)) - 1
    return 1 + x * x * ((s + 1) * x + s)


def frame_svg(t):
    slide = ease(FOSI, phase(t, 1.35, 0.7))
    mx = MARK_START_X + (MARK_FINAL_X - MARK_START_X) * slide
    tm = Tx(SC, mx, LY)
    p = g.mark_paths(tm)
    scx, scy = tm.px(g.SCREEN_W / 2), tm.py(g.SCREEN_TOP + g.SCREEN_H / 2)

    defs = [lin("bg", 0, 0, W, H, "#131B26", g.BG),
            lin("scr", 0, tm.py(g.SCREEN_TOP), 0, tm.py(g.MARK_H), g.TEAL_TOP, g.TEAL_BOTTOM),
            lin("dots", 0, tm.py(g.dot_centers()[2][1] - g.DOT / 2), 0, tm.py(g.dot_centers()[0][1] + g.DOT / 2),
                g.AMBER_TOP, g.AMBER_BOTTOM)]
    glow_a = ease(LOSI, phase(t, 0.0, 0.8))
    defs.append(f'<radialGradient id="glow" gradientUnits="userSpaceOnUse" cx="{fmt(scx)}" cy="{fmt(scy)}" r="560">'
                f'<stop offset="0" stop-color="{g.TEAL}" stop-opacity="{fmt(0.05 * glow_a, 3)}"/>'
                f'<stop offset="0.4" stop-color="{g.TEAL}" stop-opacity="{fmt(0.18 * glow_a, 3)}"/>'
                f'<stop offset="1" stop-color="{g.TEAL}" stop-opacity="0"/></radialGradient>')
    out = [f'<rect width="{W}" height="{H}" fill="url(#bg)"/>', f'<rect width="{W}" height="{H}" fill="url(#glow)"/>']

    # screen: scale in with a soft overshoot
    k = phase(t, 0.1, 0.55)
    s_scr = 0.82 + 0.18 * back_out(k, 1.2)
    a_scr = ease(LOSI, phase(t, 0.1, 0.3))
    out.append(f'<g transform="translate({fmt(scx)},{fmt(scy)}) scale({fmt(s_scr, 4)}) translate({fmt(-scx)},{fmt(-scy)})" '
               f'opacity="{fmt(a_scr, 3)}"><path fill="url(#scr)" d="{p["screen"]}"/></g>')
    # play triangle
    k = phase(t, 0.95, 0.4)
    pcx, pcy = tm.px(g.play_center()[0]), tm.py(g.play_center()[1])
    s_play = 0.55 + 0.45 * ease(FOSI, k)
    out.append(f'<g transform="translate({fmt(pcx)},{fmt(pcy)}) scale({fmt(s_play, 4)}) translate({fmt(-pcx)},{fmt(-pcy)})" '
               f'opacity="{fmt(ease(LOSI, k / 0.6), 3)}"><path fill="{g.BG}" d="{p["play"]}"/></g>')
    # dots of ش drop in one by one
    for i, d in enumerate(p["dots"]):
        k = phase(t, 0.45 + i * 0.12, 0.36)
        rise = -70 * SC / 0.46
        if k < 0.72:
            dy = rise + (6 - rise) * ease(FOSI, k / 0.72)
        else:
            dy = 6 * (1 - ease(FOSI, (k - 0.72) / 0.28))
        a = ease(LOSI, k / 0.45)
        out.append(f'<g transform="translate(0,{fmt(dy)})" opacity="{fmt(a, 3)}"><path fill="url(#dots)" d="{d}"/></g>')

    # wordmark: glued to the mark's left side, written on right-to-left (RTL reading order)
    ws = LOCK_WS * SC
    base = LY + g.MARK_H * SC
    wx = mx - LOCK_GAP * SC - WM.advance * ws
    wp = WM.paths(ws, wx, base)
    reveal = ease(FOSI, phase(t, 1.45, 0.8))
    right = wx + WM.advance * ws + 20
    left = right - (WM.advance * ws + 40) * reveal
    defs.append(f'<clipPath id="wipe"><rect x="{fmt(left)}" y="0" width="{fmt(right - left + 2)}" height="{H}"/></clipPath>')
    out.append(f'<g clip-path="url(#wipe)" opacity="{fmt(ease(LOSI, phase(t, 1.45, 0.35)), 3)}">'
               f'<path fill="{g.TEXT}" d="{wp["body"]}"/></g>')
    ys = [base - (cy + d / 2) * ws for _, cy, d in WM.accent_dots] + [base - (cy - d / 2) * ws for _, cy, d in WM.accent_dots]
    defs.append(lin("acc", 0, min(ys), 0, max(ys), g.AMBER_TOP, g.AMBER_BOTTOM))
    for i, (cx, cy, d) in enumerate(WM.accent_dots):
        k = phase(t, 1.75 + i * 0.08, 0.35)
        if k <= 0:
            continue
        X, Y, r = wx + cx * ws, base - cy * ws, d * ws / 2 * back_out(k, 2.0)
        out.append(f'<circle cx="{fmt(X)}" cy="{fmt(Y)}" r="{fmt(max(r, 0.01))}" fill="url(#acc)"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">'
            f'<defs>{"".join(defs)}</defs>{"".join(out)}</svg>')


def main():
    import imageio_ffmpeg
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    os.makedirs(EXP, exist_ok=True)
    n = int(DURATION * FPS)
    with tempfile.TemporaryDirectory() as tmp:
        for i in range(n):
            cairosvg.svg2png(bytestring=frame_svg(i / FPS).encode(), write_to=os.path.join(tmp, f"f{i:04d}.png"))
        out = os.path.join(EXP, "logo-reveal-1080p60.mp4")
        subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-framerate", str(FPS), "-i", os.path.join(tmp, "f%04d.png"),
                        "-vf", "noise=alls=3:allf=t+u",   # fine grain dither: hides 8-bit banding in the glow
                        "-c:v", "libx264", "-preset", "slow", "-tune", "grain", "-crf", "18", "-pix_fmt", "yuv420p",
                        "-movflags", "+faststart", out], check=True)
    cairosvg.svg2png(bytestring=frame_svg(DURATION).encode(), write_to=os.path.join(EXP, "logo-reveal-poster.png"))
    print("wrote", out)


if __name__ == "__main__":
    main()
