"""Builds the annotated pictures of the user manual (docs/manual/images/*.jpg).

Each picture is a copy of a real screenshot (app/src/test/screenshots, remote/src/test/screenshots) with
numbered circles, and sometimes a frame and a short line pointing at what the number explains. Only
digits are drawn in the pictures: every explanation is written in the chapter, under the picture.

Run from the repository root, with Pillow installed (pip install Pillow):

    python3 docs/manual/tools/annotate.py            # every picture
    python3 docs/manual/tools/annotate.py home player  # only these

When a screenshot changes (./gradlew :app:recordRoborazziDebug), check the coordinates below: they are
pixels of the original screenshot (1920x1080 for the TV, 780x1688 for the phone page).
"""

import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
TV = os.path.join(ROOT, "app", "src", "test", "screenshots")
PHONE = os.path.join(ROOT, "remote", "src", "test", "screenshots")
OUT = os.path.join(ROOT, "docs", "manual", "images")
FONT = os.path.join(ROOT, "app", "src", "main", "res", "font", "plex_arabic_bold.ttf")

AMBER = (255, 181, 71)
TEAL = (46, 196, 182)
INK = (11, 15, 20)
SS = 2  # supersampling: shapes are drawn at twice the size, then scaled down smoothly


def c(n, badge, box=None):
    """One callout: number n, its circle's centre, and optionally the frame (x1, y1, x2, y2) it points at."""
    return {"n": n, "badge": badge, "box": box}


# name: source, output width, colour, callouts. "compose" builds one picture from several phone pages.
SPECS = {
    "home": dict(src=f"{TV}/home.png", color=AMBER, callouts=[
        c(1, (1768, 830), (1716, 62, 1820, 1012)),
        c(2, (525, 240), (370, 124, 680, 184)),
        c(3, (222, 250), (95, 102, 350, 205)),
        c(4, (1483, 245), (1290, 278, 1676, 498)),
        c(5, (800, 845), (503, 900, 762, 916)),
        c(6, (668, 395), (510, 431, 616, 477)),
        c(7, (1072, 395), (913, 431, 1015, 477)),
        c(8, (1322, 628), (1386, 602, 1678, 654)),
    ]),
    "rail": dict(src=f"{TV}/rail_adult_profile.png", color=AMBER, callouts=[
        c(1, (1455, 264)),
        c(2, (1560, 371)),
        c(3, (1515, 478)),
        c(4, (1500, 586)),
        c(5, (1560, 695)),
        c(6, (1480, 856)),
        c(7, (1525, 963)),
    ]),
    "player": dict(src=f"{TV}/player_osd_watch_later_resume.png", color=AMBER, callouts=[
        c(1, (962, 140), (1004, 92, 1826, 218)),
        c(2, (508, 132), (110, 94, 466, 170)),
        c(3, (1100, 758), (126, 798, 1794, 858)),
        c(4, (172, 1036), (110, 862, 234, 986)),
        c(5, (311, 1036), (258, 870, 364, 978)),
        c(6, (436, 1036), (382, 870, 490, 978)),
        c(7, (638, 1036), (522, 893, 753, 954)),
        c(8, (917, 1036), (798, 870, 1036, 978)),
        c(9, (1160, 1036), (1057, 870, 1264, 978)),
        c(10, (1382, 1036), (1285, 870, 1479, 978)),
        c(11, (1655, 1036), (1499, 870, 1810, 978)),
    ]),
    "player-360p": dict(src=f"{TV}/player_osd_low_quality.png", color=AMBER, callouts=[
        c(1, (813, 1036), (754, 894, 872, 954)),
        c(2, (1664, 1036), (1517, 870, 1810, 978)),
    ]),
    "kids-home": dict(src=f"{TV}/kids_home.png", color=TEAL, callouts=[
        c(1, (1054, 150), (1094, 80, 1676, 226)),
        c(2, (1444, 330), (1484, 294, 1686, 366)),
        c(3, (1284, 830), (1324, 794, 1686, 866)),
        c(4, (1768, 660), (1714, 60, 1822, 1012)),
    ]),
    "search": dict(src=f"{TV}/search_results.png", color=AMBER, callouts=[
        c(1, (56, 144), (95, 86, 1666, 202)),
        c(2, (770, 270), (808, 228, 1668, 312)),
        c(3, (1628, 731), (1382, 688, 1586, 775)),
        c(4, (740, 470), (511, 503, 669, 547)),
    ]),
    "channel": dict(src=f"{TV}/channel.png", color=AMBER, callouts=[
        c(1, (1254, 420), (1294, 354, 1544, 490)),
        c(2, (533, 522), (403, 370, 663, 476)),
        c(3, (256, 522), (133, 370, 380, 476)),
    ]),
    "profile-edit": dict(src=f"{TV}/profile_edit.png", color=TEAL, callouts=[
        c(1, (58, 196)),
        c(2, (58, 343)),
        c(3, (58, 492)),
        c(4, (58, 640)),
        c(5, (58, 788)),
        c(6, (58, 935)),
    ]),
    "settings": dict(src=f"{TV}/settings.png", color=AMBER, callouts=[
        c(1, (1099, 230), (1138, 62, 1664, 1018)),
        c(2, (1428, 275), (1468, 250, 1628, 300)),
        c(3, (1207, 675), (1180, 318, 1234, 640)),
        c(4, (436, 113), (476, 74, 936, 152)),
        c(5, (590, 495), (628, 441, 1015, 549)),
        c(6, (68, 875), (110, 803, 1062, 947)),
        c(7, (1768, 860), (1712, 914, 1824, 1012)),
    ]),
    "protection": dict(src=f"{TV}/settings_hub_protection.png", color=AMBER, callouts=[
        c(1, (225, 232), (108, 135, 345, 190)),
        c(2, (58, 369), (98, 293, 1074, 445)),
        c(3, (488, 485), (526, 459, 1068, 511)),
        c(4, (390, 555), (428, 529, 1068, 581)),
        c(5, (420, 690), (112, 600, 1060, 1080)),
        c(6, (1119, 506), (1158, 461, 1642, 552)),
    ]),
    "remote-pairing": dict(src=f"{TV}/remote_pairing.png", color=AMBER, callouts=[
        c(1, (1095, 186), (1133, 153, 1279, 217)),
        c(2, (742, 372), (770, 303, 1822, 440)),
        c(3, (412, 110), (110, 146, 714, 750)),
        c(4, (742, 826), (110, 770, 714, 882)),
        c(5, (750, 640), (780, 468, 1812, 824)),
        c(6, (1655, 978), (1505, 851, 1810, 935)),
    ]),
    "error-screen": dict(src=f"{TV}/error_blocked_403.png", color=AMBER, callouts=[
        c(1, (1394, 457), (570, 416, 1354, 500)),
        c(2, (1440, 540), (524, 508, 1398, 572)),
        c(3, (1257, 705), (940, 628, 1212, 792)),
        c(4, (663, 705), (708, 628, 938, 792)),
        c(5, (1342, 900), (1047, 844, 1298, 958)),
        c(6, (588, 900), (631, 848, 1020, 954)),
    ]),
    "diagnostics": dict(src=f"{TV}/diagnostics.png", color=AMBER, callouts=[
        c(1, (981, 620), (1008, 266, 1824, 977)),
        c(2, (1254, 606), (1043, 594, 1206, 652)),
        c(3, (282, 246), (110, 108, 454, 214)),
        c(4, (74, 640), (106, 270, 966, 1004)),
    ]),
    "sponsor-undo": dict(src=f"{TV}/sponsor_skip_undo.png", color=AMBER, callouts=[
        c(1, (1590, 228), (1378, 83, 1802, 174)),
        c(2, (1200, 228), (1033, 86, 1369, 172)),
    ]),
    "stats": dict(src=f"{TV}/stats_adult.png", color=AMBER, callouts=[
        c(1, (662, 160), (108, 118, 627, 204)),
        c(2, (1480, 332), (826, 273, 1810, 1020)),
        c(3, (860, 852), (894, 771, 954, 934)),
        c(4, (268, 357), (308, 296, 762, 418)),
        c(5, (288, 462), (328, 434, 762, 491)),
        c(6, (390, 517), (428, 494, 762, 541)),
        c(7, (300, 640), (138, 614, 762, 984)),
    ]),
    "card-menu": dict(src=f"{TV}/card_menu_parental.png", color=AMBER, callouts=[
        c(1, (1126, 200), (1166, 136, 1362, 260)),
        c(2, (490, 372), (539, 310, 1381, 434)),
        c(3, (608, 280), (580, 340, 636, 908)),
    ]),
    "time-up": dict(src=f"{TV}/time_up.png", color=TEAL, callouts=[
        c(1, (1706, 238), (1605, 95, 1809, 193)),
        c(2, (1345, 687), (615, 640, 1306, 735)),
        c(3, (1160, 836), (804, 780, 1117, 893)),
        c(4, (1212, 937), (750, 910, 1172, 966)),
    ]),
    "phone-screen": dict(src=f"{PHONE}/01-screen-now-playing.png", width=780, color=AMBER, callouts=[
        c(1, (306, 58), (28, 16, 252, 100)),
        c(2, (330, 218), (370, 185, 715, 255)),
        c(3, (146, 146), (64, 183, 228, 257)),
        c(4, (390, 900), (64, 848, 716, 872)),
        c(5, (230, 1128), (64, 962, 716, 1078)),
        c(6, (455, 1148), (493, 1116, 716, 1180)),
        c(7, (240, 1300), (32, 1238, 748, 1545)),
        c(8, (576, 1606), (610, 1560, 730, 1685)),
    ]),
    "phone-tabs": dict(compose=[
        f"{PHONE}/10-parent-login.png",
        f"{PHONE}/06-profiles.png",
        f"{PHONE}/04-search-results.png",
        f"{PHONE}/01-screen-now-playing.png",
    ], color=AMBER, callouts="tabs"),
    "phone-parent": dict(compose=[
        f"{PHONE}/17-parent-subscriptions.png",
        f"{PHONE}/15-parent-allowlist.png",
        f"{PHONE}/14-parent-video.png",
        f"{PHONE}/12-parent-time.png",
    ], color=AMBER, callouts="parent"),
}

# Composites: phone pages side by side, first page on the RIGHT (the page is Arabic, read right to left).
# Each page gets its number in a circle above it, and a frame around the tab it shows.
PHONE_SCALE = 0.5
GAP = 28
MARGIN = 36
TOP = 104


def compose(paths):
    pages = [Image.open(p).convert("RGB") for p in paths]
    pages = [p.resize((int(p.width * PHONE_SCALE), int(p.height * PHONE_SCALE)), Image.LANCZOS) for p in pages]
    w = sum(p.width for p in pages) + GAP * (len(pages) - 1) + 2 * MARGIN
    h = max(p.height for p in pages) + TOP + MARGIN
    canvas = Image.new("RGB", (w, h), INK)
    x = MARGIN
    frames = []
    for p in pages:
        canvas.paste(p, (x, TOP))
        frames.append((x, TOP, x + p.width, TOP + p.height))
        x += p.width + GAP
    return canvas, frames


# Tabs, in the phone page's own pixels (780x1688 screenshots).
BOTTOM_TABS = {  # the page's bottom bar
    "screen": (598, 1556, 738, 1686), "search": (413, 1556, 553, 1686),
    "profile": (226, 1556, 366, 1686), "parent": (40, 1556, 180, 1686),
}
PARENT_TABS = {  # the parent section's own tabs, two rows
    "time": (546, 296, 750, 382), "video": (328, 296, 537, 382), "allowlist": (31, 296, 320, 382),
    "subscriptions": (421, 393, 750, 479), "backup": (31, 393, 412, 479),
}
COMPOSITES = {
    # left to right, as composed: (number, tab)
    "tabs": [(4, BOTTOM_TABS["parent"]), (3, BOTTOM_TABS["profile"]), (2, BOTTOM_TABS["search"]),
             (1, BOTTOM_TABS["screen"])],
    "parent": [(4, PARENT_TABS["subscriptions"]), (3, PARENT_TABS["allowlist"]), (2, PARENT_TABS["video"]),
               (1, PARENT_TABS["time"])],
}


def composite_callouts(kind, frames):
    s = PHONE_SCALE
    out = []
    for (x1, y1, x2, y2), (n, (bx1, by1, bx2, by2)) in zip(frames, COMPOSITES[kind]):
        box = (x1 + bx1 * s, y1 + by1 * s, x1 + bx2 * s, y1 + by2 * s)
        badge = ((x1 + x2) / 2, TOP / 2)
        out.append({"n": n, "badge": badge, "box": box, "line": False})
    return out


def nearest_on_box(p, box):
    x1, y1, x2, y2 = box
    return (min(max(p[0], x1), x2), min(max(p[1], y1), y2))


def inside(p, box):
    x1, y1, x2, y2 = box
    return x1 <= p[0] <= x2 and y1 <= p[1] <= y2


def draw_callouts(base, callouts, color, out_width):
    scale = out_width / base.width
    big = (int(base.width * SS), int(base.height * SS))
    layer = Image.new("RGBA", big, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    k = SS  # source pixels -> layer pixels
    # Sizes are chosen for the output picture, then expressed in source pixels.
    unit = 1 / scale  # one output pixel, in source pixels
    r = 25 * unit
    frame_w = 4 * unit
    line_w = 4 * unit
    under = 3 * unit

    def P(pt):
        return (pt[0] * k, pt[1] * k)

    # Frames and lines first, then the circles over them.
    for co in callouts:
        box = co["box"]
        if not box:
            continue
        x1, y1, x2, y2 = box
        rad = min(18 * unit, (y2 - y1) / 2, (x2 - x1) / 2)
        d.rounded_rectangle([x1 * k, y1 * k, x2 * k, y2 * k], radius=rad * k, outline=INK + (170,),
                            width=int((frame_w + 2 * under) * k))
        d.rounded_rectangle([x1 * k, y1 * k, x2 * k, y2 * k], radius=rad * k, outline=color + (255,),
                            width=int(frame_w * k))
        if co.get("line", True) and not inside(co["badge"], box):
            end = nearest_on_box(co["badge"], box)
            d.line([P(co["badge"]), P(end)], fill=INK + (170,), width=int((line_w + 2 * under) * k))
            d.line([P(co["badge"]), P(end)], fill=color + (255,), width=int(line_w * k))
            er = 5 * unit
            d.ellipse([(end[0] - er) * k, (end[1] - er) * k, (end[0] + er) * k, (end[1] + er) * k], fill=color + (255,))

    for co in callouts:
        cx, cy = co["badge"]
        text = str(co["n"])
        rr = r if len(text) == 1 else r * 1.12
        shadow = 4 * unit
        d.ellipse([(cx - rr - 6 * unit + shadow / 2) * k, (cy - rr - 6 * unit + shadow) * k,
                   (cx + rr + 6 * unit + shadow / 2) * k, (cy + rr + 6 * unit + shadow) * k], fill=(0, 0, 0, 90))
        d.ellipse([(cx - rr - 5 * unit) * k, (cy - rr - 5 * unit) * k, (cx + rr + 5 * unit) * k, (cy + rr + 5 * unit) * k],
                  fill=INK + (235,))
        d.ellipse([(cx - rr - 3 * unit) * k, (cy - rr - 3 * unit) * k, (cx + rr + 3 * unit) * k, (cy + rr + 3 * unit) * k],
                  fill=(255, 255, 255, 255))
        d.ellipse([(cx - rr) * k, (cy - rr) * k, (cx + rr) * k, (cy + rr) * k], fill=color + (255,))
        size = int((30 if len(text) == 1 else 26) * unit * k)
        font = ImageFont.truetype(FONT, size)
        # Centre the digits' box (tabular digits: the advance box horizontally, the ink vertically).
        l, t, rgt, b = font.getbbox(text, anchor="la")
        d.text((cx * k - (rgt - l) / 2 - l, cy * k - (b - t) / 2 - t), text, font=font, fill=INK + (255,), anchor="la")

    base_out = base.convert("RGB").resize((out_width, round(base.height * scale)), Image.LANCZOS)
    layer_out = layer.resize(base_out.size, Image.LANCZOS)
    base_out = base_out.convert("RGBA")
    base_out.alpha_composite(layer_out)
    return base_out.convert("RGB")


def build(name, spec):
    if "compose" in spec:
        base, frames = compose(spec["compose"])
        callouts = composite_callouts(spec["callouts"], frames)
        width = 1600
    else:
        base = Image.open(spec["src"]).convert("RGB")
        callouts = spec["callouts"]
        width = spec.get("width", 1600)
    out = draw_callouts(base, callouts, spec["color"], width)
    path = os.path.join(OUT, f"{name}.jpg")
    out.save(path, "JPEG", quality=85, optimize=True, progressive=True)
    print(f"{path}  {out.size[0]}x{out.size[1]}  {os.path.getsize(path) // 1024} KB")


def main():
    os.makedirs(OUT, exist_ok=True)
    names = sys.argv[1:] or list(SPECS)
    for name in names:
        build(name, SPECS[name])


if __name__ == "__main__":
    main()
