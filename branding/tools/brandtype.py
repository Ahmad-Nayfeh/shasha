"""Arabic wordmark "شاشة" set in IBM Plex Sans Arabic Bold and converted to outlines.

Shaping is done with HarfBuzz (correct Arabic joining), outlines come from fontTools, so the
exports never depend on fonts installed on the machine. The letter dots are redrawn as circles
laid out like the dots of the brand mark, and the dots of the first ش are returned separately
so they can carry the accent colour.
"""
import math
import os

import uharfbuzz as hb
from fontTools.pens.recordingPen import DecomposingRecordingPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

from brandgeo import DOT, DOT_GAP, fmt

FONT_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "res", "font")
WORD = "شاشة"
DOT_SCALE = 1.04                    # circles look smaller than the font's squarish dots
GAP_RATIO = DOT_GAP / DOT           # same dot spacing as the mark


def _contours(glyphset, name):
    pen = DecomposingRecordingPen(glyphset)
    glyphset[name].draw(pen)
    out, cur = [], []
    for op, args in pen.value:
        cur.append((op, args))
        if op in ("closePath", "endPath"):
            out.append(cur)
            cur = []
    return out


def _bbox(contour):
    pts = [p for _, args in contour for p in args if p is not None]
    return (min(p[0] for p in pts), min(p[1] for p in pts), max(p[0] for p in pts), max(p[1] for p in pts))


class Wordmark:
    """Outlines in font units (y up, baseline at 0, x from 0 to `advance`)."""

    def __init__(self, text=WORD, weight="bold"):
        path = os.path.join(FONT_DIR, f"plex_arabic_{weight}.ttf")
        face = hb.Face(hb.Blob.from_file_path(path))
        font = hb.Font(face)
        buf = hb.Buffer()
        buf.add_str(text)
        buf.guess_segment_properties()
        hb.shape(font, buf, {})
        tt = TTFont(path)
        gs = tt.getGlyphSet()
        order = tt.getGlyphOrder()

        self.body = []            # list of (contour, dx, dy)
        self.accent_dots = []     # (cx, cy, d) of the first letter (cluster 0)
        self.other_dots = []
        x = 0
        for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
            ox, oy = x + pos.x_offset, pos.y_offset
            dots = []
            for c in _contours(gs, order[info.codepoint]):
                x0, y0, x1, y1 = _bbox(c)
                if (x1 - x0) < 220 and (y1 - y0) < 220 and y0 > 300:
                    dots.append(((x0 + x1) / 2 + ox, (y0 + y1) / 2 + oy, x1 - x0))
                else:
                    self.body.append((c, ox, oy))
            circles = self._respace(dots)
            (self.accent_dots if info.cluster == 0 else self.other_dots).extend(circles)
            x += pos.x_advance
        self.advance = x
        ys = [p[1] + oy for c, _, oy in self.body for _, args in c for p in args if p is not None]
        self.descent = min(ys)
        self.ascent = max(max(ys), max(cy + d / 2 for _, cy, d in self.accent_dots + self.other_dots))

    @staticmethod
    def _respace(dots):
        """Redraw a group of font dots as circles with the mark's proportions, same centroid."""
        if not dots:
            return []
        d = max(w for _, _, w in dots) * DOT_SCALE
        pitch = d * (1 + GAP_RATIO)
        gx = sum(x for x, _, _ in dots) / len(dots)
        if len(dots) == 3:
            # one above, two below (ش): equilateral cluster centred on the original centroid
            row = pitch * math.sqrt(3) / 2
            ybot = min(y for _, y, _ in dots)
            ytop = max(y for _, y, _ in dots)
            cy = (2 * ybot + ytop) / 3                # original centroid
            yb = cy - row / 3
            return [(gx + pitch / 2, yb, d), (gx - pitch / 2, yb, d), (gx, yb + row, d)]
        if len(dots) == 2:
            cy = sum(y for _, y, _ in dots) / 2
            return [(gx + pitch / 2, cy, d), (gx - pitch / 2, cy, d)]
        return [(x, y, d) for x, y, _ in dots]

    # ---- output ---------------------------------------------------------------------
    def paths(self, s, tx, ty, nd=2):
        """SVG path data. Font units -> output: X = tx + x*s, Y = ty - y*s (ty = baseline)."""
        pen = SVGPathPen(None, ntos=lambda v: fmt(v, nd))
        tp = TransformPen(pen, (s, 0, 0, -s, tx, ty))
        for c, ox, oy in self.body:
            for op, args in c:
                getattr(tp, op)(*[None if p is None else (p[0] + ox, p[1] + oy) for p in args])
        body = pen.getCommands()

        def circles(items):
            out = ""
            for cx, cy, d in items:
                X, Y, r = tx + cx * s, ty - cy * s, d * s / 2
                out += (f"M{fmt(X + r, nd)},{fmt(Y, nd)}A{fmt(r, nd)},{fmt(r, nd)} 0 1 1 {fmt(X - r, nd)},{fmt(Y, nd)}"
                        f"A{fmt(r, nd)},{fmt(r, nd)} 0 1 1 {fmt(X + r, nd)},{fmt(Y, nd)}Z")
            return out

        return {"body": body + circles(self.other_dots), "accent": circles(self.accent_dots)}

    def height(self):
        return self.ascent - self.descent


_FONTS = {}


def _is_arabic(ch):
    o = ord(ch)
    return 0x0600 <= o <= 0x06FF or 0x0750 <= o <= 0x077F or 0xFB50 <= o <= 0xFDFF or 0xFE70 <= o <= 0xFEFF


def _runs(text):
    """Minimal bidi for an RTL paragraph: [(run_text, is_rtl)] in visual order, right to left."""
    kinds = []
    for ch in text:
        kinds.append("R" if _is_arabic(ch) else "L" if ch.isalnum() else "N")
    # neutrals between two runs of the same direction take that direction, otherwise RTL
    for i, k in enumerate(kinds):
        if k == "N":
            left = next((kinds[j] for j in range(i - 1, -1, -1) if kinds[j] != "N"), "R")
            right = next((kinds[j] for j in range(i + 1, len(kinds)) if kinds[j] != "N"), "R")
            kinds[i] = left if left == right else "R"
    runs = []
    for ch, k in zip(text, kinds):
        if runs and runs[-1][1] == (k == "R"):
            runs[-1][0] += ch
        else:
            runs.append([ch, k == "R"])
    return [(t, r) for t, r in runs]


def _shape_run(font, text, rtl):
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    buf.direction = "rtl" if rtl else "ltr"
    hb.shape(font, buf, {})
    return buf


def text_path(text, size, x, y, weight="regular", anchor="start", nd=2):
    """Any text (Arabic or Latin) as SVG path data, shaped with HarfBuzz.

    (x, y) is the baseline point; anchor: "start" = left edge, "middle", "end" = right edge.
    Returns (path_data, width).
    """
    path = os.path.join(FONT_DIR, f"plex_arabic_{weight}.ttf")
    if path not in _FONTS:
        tt = TTFont(path)
        _FONTS[path] = (hb.Font(hb.Face(hb.Blob.from_file_path(path))), tt.getGlyphSet(), tt.getGlyphOrder(),
                        tt["head"].unitsPerEm)
    font, gs, order, upm = _FONTS[path]
    s = size / upm
    rtl_par = any(_is_arabic(ch) for ch in text)
    runs = _runs(text) if rtl_par else [(text, False)]
    shaped = [_shape_run(font, t, r) for t, r in runs]
    width = sum(p.x_advance for b in shaped for p in b.glyph_positions) * s
    x0 = {"start": x, "middle": x - width / 2, "end": x - width}[anchor]
    pen = SVGPathPen(gs, ntos=lambda v: fmt(v, nd))
    # RTL paragraph: logical run order goes right-to-left; HarfBuzz output is already visual inside a run
    ordered = list(reversed(shaped)) if rtl_par else shaped
    adv = 0
    for buf in ordered:
        for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
            tp = TransformPen(pen, (s, 0, 0, -s, x0 + (adv + pos.x_offset) * s, y - pos.y_offset * s))
            gs[order[info.codepoint]].draw(tp)
            adv += pos.x_advance
    return pen.getCommands(), width
