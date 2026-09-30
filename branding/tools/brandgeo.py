"""Shasha brand geometry: one source of truth for every logo, icon and splash asset.

All shapes are defined in "mark units" (the TV screen is 720 units wide) and emitted as
SVG path data, which is also valid Android VectorDrawable pathData (M/H/V/L/A/C/Z only).
"""
import math

# ---- Palette (see branding/README.md) -------------------------------------------------
BG = "#0B0F14"          # background
SURFACE = "#151B24"
TEXT = "#EAF0F6"
MUTED = "#8A97A8"
TEAL = "#2EC4B6"        # primary
TEAL_TOP = "#48DCCD"    # screen gradient (top)
TEAL_BOTTOM = "#1FAE9F" # screen gradient (bottom)
AMBER = "#FFB547"       # secondary
AMBER_TOP = "#FFC56B"
AMBER_BOTTOM = "#FFA92E"
# Darker tones for light backgrounds (keep contrast on #FFFFFF / #F4F6F8)
TEAL_ON_LIGHT_TOP = "#2EC4B6"
TEAL_ON_LIGHT_BOTTOM = "#16998C"
AMBER_ON_LIGHT_TOP = "#FFB547"
AMBER_ON_LIGHT_BOTTOM = "#F39414"

# ---- Mark geometry (mark units) -------------------------------------------------------
SCREEN_W = 720.0
SCREEN_H = 500.0
SCREEN_R = 150.0
DOT = 108.0                 # dot diameter
DOT_GAP = 20.0              # gap between neighbouring dots
DOT_PITCH = DOT + DOT_GAP   # centre-to-centre distance (equilateral cluster, like the dots of ش)
DOT_ROW = DOT_PITCH * math.sqrt(3) / 2
DOTS_TO_SCREEN = 48.0       # gap between the lower dots and the screen
PLAY_H = 240.0
PLAY_W = PLAY_H * 0.92
PLAY_R = 34.0
PLAY_FLAT_X = -0.37         # flat side at cx - 0.37*w (visual centring of a rounded triangle)

SCREEN_TOP = DOT + DOT_ROW + DOTS_TO_SCREEN
MARK_W = SCREEN_W
MARK_H = SCREEN_TOP + SCREEN_H

# Smallest circle enclosing the whole mark (used to fit icon safe zones).
# Top of the upper dot and the two lower screen corners are the extreme points.
def _enclosing_circle():
    ax = SCREEN_W / 2 - SCREEN_R          # horizontal offset of the lower corner arc centres
    ay = SCREEN_TOP + SCREEN_H - SCREEN_R  # y of the lower corner arc centres
    # centre (W/2, c): c = sqrt(ax^2 + (ay - c)^2) + R  -> solve for c
    # (c - R)^2 = ax^2 + (ay - c)^2  =>  c = (ax^2 + ay^2 - R^2) / (2*ay - 2*R)
    c = (ax * ax + ay * ay - SCREEN_R ** 2) / (2 * ay - 2 * SCREEN_R)
    return SCREEN_W / 2, c, c

CIRCLE_CX, CIRCLE_CY, CIRCLE_R = _enclosing_circle()


def fmt(v, nd=2):
    s = f"{v:.{nd}f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


class Tx:
    """Affine map from mark units to output units: (x, y) -> (tx + x*s, ty + y*s)."""

    def __init__(self, s=1.0, tx=0.0, ty=0.0, nd=2):
        self.s, self.tx, self.ty, self.nd = s, tx, ty, nd

    def x(self, v):
        return fmt(self.tx + v * self.s, self.nd)

    def y(self, v):
        return fmt(self.ty + v * self.s, self.nd)

    def l(self, v):
        return fmt(v * self.s, self.nd)

    def px(self, v):
        return self.tx + v * self.s

    def py(self, v):
        return self.ty + v * self.s


def rrect(t, x, y, w, h, r):
    X, Y, L = t.x, t.y, t.l
    return (f"M{X(x + r)},{Y(y)}H{X(x + w - r)}A{L(r)},{L(r)} 0 0 1 {X(x + w)},{Y(y + r)}"
            f"V{Y(y + h - r)}A{L(r)},{L(r)} 0 0 1 {X(x + w - r)},{Y(y + h)}H{X(x + r)}"
            f"A{L(r)},{L(r)} 0 0 1 {X(x)},{Y(y + h - r)}V{Y(y + r)}A{L(r)},{L(r)} 0 0 1 {X(x + r)},{Y(y)}Z")


def circle(t, cx, cy, d):
    r = d / 2
    X, Y, L = t.x, t.y, t.l
    return (f"M{X(cx + r)},{Y(cy)}A{L(r)},{L(r)} 0 1 1 {X(cx - r)},{Y(cy)}"
            f"A{L(r)},{L(r)} 0 1 1 {X(cx + r)},{Y(cy)}Z")


def rounded_polygon(t, pts, r):
    """Polygon (clockwise on screen) with circular fillets of radius r at every vertex."""
    n = len(pts)
    corners = []
    for i in range(n):
        p0, p1, p2 = pts[i - 1], pts[i], pts[(i + 1) % n]
        v1 = (p0[0] - p1[0], p0[1] - p1[1])
        v2 = (p2[0] - p1[0], p2[1] - p1[1])
        l1, l2 = math.hypot(*v1), math.hypot(*v2)
        u1 = (v1[0] / l1, v1[1] / l1)
        u2 = (v2[0] / l2, v2[1] / l2)
        ang = math.acos(max(-1.0, min(1.0, u1[0] * u2[0] + u1[1] * u2[1])))
        k = r / math.tan(ang / 2)
        a = (p1[0] + u1[0] * k, p1[1] + u1[1] * k)
        b = (p1[0] + u2[0] * k, p1[1] + u2[1] * k)
        sweep = 0 if (u1[0] * u2[1] - u1[1] * u2[0]) > 0 else 1
        corners.append((a, b, sweep))
    X, Y, L = t.x, t.y, t.l
    d = f"M{X(corners[0][0][0])},{Y(corners[0][0][1])}"
    for i, (a, b, sweep) in enumerate(corners):
        if i:
            d += f"L{X(a[0])},{Y(a[1])}"
        d += f"A{L(r)},{L(r)} 0 0 {sweep} {X(b[0])},{Y(b[1])}"
    return d + "Z"


def screen_center():
    return SCREEN_W / 2, SCREEN_TOP + SCREEN_H / 2


def dot_centers():
    """Lower-right, lower-left, top (the order they drop in on the splash; RTL reading order)."""
    cx = SCREEN_W / 2
    yb = SCREEN_TOP - DOTS_TO_SCREEN - DOT / 2
    yt = yb - DOT_ROW
    return [(cx + DOT_PITCH / 2, yb), (cx - DOT_PITCH / 2, yb), (cx, yt)]


def play_points():
    cx, cy = screen_center()
    x0 = cx + PLAY_FLAT_X * PLAY_W
    return [(x0, cy - PLAY_H / 2), (x0 + PLAY_W, cy), (x0, cy + PLAY_H / 2)]


def play_center():
    pts = play_points()
    return sum(p[0] for p in pts) / 3, sum(p[1] for p in pts) / 3


def mark_paths(t):
    """Path data for the parts of the mark under transform t."""
    screen = rrect(t, 0, SCREEN_TOP, SCREEN_W, SCREEN_H, SCREEN_R)
    play = rounded_polygon(t, play_points(), PLAY_R)
    dots = [circle(t, x, y, DOT) for x, y in dot_centers()]
    return {"screen": screen, "play": play, "dots": dots}


def fit_circle(diameter, cx, cy, nd=2):
    """Transform that fits the mark's enclosing circle into a circle of `diameter` centred at (cx, cy)."""
    s = diameter / (2 * CIRCLE_R)
    return Tx(s, cx - CIRCLE_CX * s, cy - CIRCLE_CY * s, nd)


def fit_box(x, y, w, h, nd=2):
    """Transform that fits the mark's bounding box into the box, centred."""
    s = min(w / MARK_W, h / MARK_H)
    return Tx(s, x + (w - MARK_W * s) / 2, y + (h - MARK_H * s) / 2, nd)
