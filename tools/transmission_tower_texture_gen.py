# Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
# licensed under the terms of the MIT license which can be found in the
# root directory of this project.

"""Paints the transmission towers' model sheet (textures/block/transmission_tower/towers.png, 256x512), their breaking
particles and the four tower item sprites, for Arcforge: Transmission (namespace arcforgetransmission).

    python tools/transmission_tower_texture_gen.py <addon repo>

The four towers (LV Distribution Pole, MV Sub-Transmission Pole, HV H-Frame Tower, EHV Lattice Tower) are large models
(docs/TEXTURE_STYLE.md, "Large model texturing" and "Transmission towers"): no light is painted across a part, every
plate or member shades itself from edge strips laid at 1:1, and the grain fields are big and non-repeating, so each
part shows its own window of them (TowerMesh picks it from where the part is). Round parts take the stepped
ramps across them. Everything here is deterministic (fixed seeds), so the sheet is the same on every run.

The layout is TowerMesh's (keep the two in step):
    SKIN_LV     (0, 0, 16, 512)    LV pole skin: 8 facets x 2 px round, one 8 px ramp twice round, soft grain up it
    SKIN_MV     (16, 0, 30, 512)   MV / HV pole skin: 10 facets of 3 px, each with its own 3 px ramp and soft grain
    GALV        (48, 0, 128, 128)  galvanized steel spangle field
    WOOD        (48, 128, 128, 48) treated timber field, grain along x
    CONCRETE    (176, 0, 64, 32)   concrete field
    edge rows   (48, 176..181)     1 px rows, 128 long: galv lit, galv dark, wood lit, wood dark, concrete lit, dark
    edge cols   (48..53, 192)      1 px columns, 128 tall, in the same order
    ramps       (64, 256 + 16k)    periods 2..14 side by side (x = (p-1)p/2 - 1), 16 rows tall, every row alike:
                                   galv, porcelain, glass, can paint, copper
    disc slants (176, 32) / (184, 32)  8x8: porcelain / glass disc tops, cap (top row) to rim (bottom row)
    modules     sign (176, 40, 10, 12), end grain (192, 40, 12, 12), pole tag (208, 40, 3, 4), lid (212, 40, 12, 12)
    swatches    4x4 flat tones from (64, 224), 32 a row (SWATCHES, in order)
"""
import os
import random
import sys

from PIL import Image

W, H = 256, 512


def hx(s):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255)


# --- palettes ---------------------------------------------------------------------------------------------------
# Treated wood: the mod's treated ramp (#120C07 -> #725033), creosote-dark.
WOOD = [hx(c) for c in ("#120C07", "#17100A", "#20160D", "#2C1C11", "#3A2616", "#47301C", "#553A22", "#634429", "#725033")]
# Galvanized steel: a light, slightly cool grey, brighter than the machine steel (median ~150 against ~67).
GALV = [hx(c) for c in ("#4E555C", "#6B737B", "#7D858D", "#89919A", "#8F979F", "#959DA5", "#9BA3AA", "#A6AEB5", "#B6BDC3", "#C9CFD4")]
# Glazed brown porcelain.
PORC = [hx(c) for c in ("#2E170C", "#4A2615", "#63341D", "#7C4426", "#95552F", "#B06A3D", "#CC8D5E")]
# Glazed light grey with a teal-green glass tint.
GLASS = [hx(c) for c in ("#3F5B58", "#587A76", "#729590", "#8EB0AA", "#A8C6C0", "#C2DAD5", "#E0EEEB")]
# Transformer can paint (utility light grey, a touch warmer than the galvanizing).
CAN = [hx(c) for c in ("#4C524E", "#5F6661", "#737A75", "#878E88", "#9AA19B", "#ADB3AD", "#C3C8C2")]
COPPER = [hx(c) for c in ("#5C2E17", "#8A4A26", "#B5663A", "#D9895A")]
CONC = [hx(c) for c in ("#7E8080", "#8C8E8E", "#9EA0A0", "#A8AAAA", "#B2B4B4", "#BEC0C0")]
ALU = [hx(c) for c in ("#3A3F44", "#9AA1A6", "#C8CDD0")]
HAZ_YELLOW, HAZ_BLACK = hx("#E0B020"), hx("#1A1A1A")
RED, RED_DARK = hx("#E5483C"), hx("#8E231C")
MARKER = hx("#5FD4C4")

# Flat swatches, in order (TowerMesh's swatch indices match this list; "marker" is unused for now).
SWATCHES = (
    [("galv", i, c) for i, c in enumerate(GALV)]
    + [("wood", i, c) for i, c in enumerate(WOOD)]
    + [("porc", i, c) for i, c in enumerate(PORC)]
    + [("glass", i, c) for i, c in enumerate(GLASS)]
    + [("can", i, c) for i, c in enumerate(CAN)]
    + [("copper", i, c) for i, c in enumerate(COPPER)]
    + [("conc", i, c) for i, c in enumerate(CONC)]
    + [("alu", i, c) for i, c in enumerate(ALU)]
    + [("hazard", 0, HAZ_YELLOW), ("black", 0, HAZ_BLACK), ("red", 0, RED), ("marker", 0, MARKER)]
)

img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
px = img.load()


def put(x, y, c):
    px[x, y] = c


def rect(x0, y0, w, h, c):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            put(x, y, c)


# --- grain fields -----------------------------------------------------------------------------------------------

def streak_field(x0, y0, w, h, tones, base, seed, along_x=True, min_run=6, max_run=34, bias=(-1, -1, 0, 0, 0, 1)):
    """Brushed / wood grain: every line along the grain is runs of 6-34 px, each one step off the base tone or on it.
    No isolated pixel: a run is never shorter than min_run, and lines next to each other start their runs apart."""
    rnd = random.Random(seed)
    lines, length = (h, w) if along_x else (w, h)
    grid = [[base] * length for _ in range(lines)]
    for line in range(lines):
        pos = -rnd.randrange(0, max_run)
        while pos < length:
            run = rnd.randint(min_run, max_run)
            off = rnd.choice(bias)
            for k in range(max(0, pos), min(length, pos + run)):
                grid[line][k] = base + off
            pos += run
    for line in range(lines):
        for k in range(length):
            t = max(0, min(len(tones) - 1, grid[line][k]))
            if along_x:
                put(x0 + k, y0 + line, tones[t])
            else:
                put(x0 + line, y0 + k, tones[t])
    return grid


def spangle_field(x0, y0, w, h, tones, idx, seed, cell=5):
    """Galvanizing spangle: irregular crystal cells 3-7 px across (jittered Voronoi), each a flat neighbouring tone,
    split along a random line into two facets a step apart, like the flowers in hot-dip zinc. Faint: tones idx only."""
    rnd = random.Random(seed)
    pts = []
    for gy in range(-1, h // cell + 2):
        for gx in range(-1, w // cell + 2):
            cx = gx * cell + rnd.uniform(0, cell)
            cy = gy * cell + rnd.uniform(0, cell)
            tone = rnd.choice(idx)
            ang = rnd.uniform(0, 3.14159)
            pts.append((cx, cy, tone, ang))
    import math
    for y in range(h):
        for x in range(w):
            best, bd = None, 1e9
            for p in pts:
                d = (p[0] - x) ** 2 + (p[1] - y) ** 2
                if d < bd:
                    bd, best = d, p
            cx, cy, tone, ang = best
            side = (x - cx) * math.cos(ang) + (y - cy) * math.sin(ang)
            t = tone + (1 if side > 0.4 else 0)
            put(x0 + x, y0 + y, tones[min(t, len(tones) - 1)])
    # Clean up isolated pixels: a pixel unlike all four neighbours takes the most common of them.
    for y in range(1, h - 1):
        for x in range(1, w - 1):
            c = px[x0 + x, y0 + y]
            ns = [px[x0 + x + 1, y0 + y], px[x0 + x - 1, y0 + y], px[x0 + x, y0 + y + 1], px[x0 + x, y0 + y - 1]]
            if all(n != c for n in ns):
                put(x0 + x, y0 + y, max(set(ns), key=ns.count))


def blotch_field(x0, y0, w, h, tones, idx, seed):
    """Concrete: three close light greys in flat 2-4 px blotches, a few 1-2 px darker pits (asphalt's grammar)."""
    rnd = random.Random(seed)
    for y in range(h):
        for x in range(w):
            put(x0 + x, y0 + y, tones[idx[1]])
    for _ in range(w * h // 6):
        bw, bh = rnd.randint(2, 4), rnd.randint(2, 3)
        bx, by = rnd.randrange(0, w), rnd.randrange(0, h)
        c = tones[rnd.choice((idx[0], idx[2], idx[2]))]
        for yy in range(bh):
            for xx in range(bw):
                if (xx in (0, bw - 1)) and (yy in (0, bh - 1)) and bw > 2:
                    continue
                put(x0 + (bx + xx) % w, y0 + (by + yy) % h, c)
    for _ in range(w * h // 70):
        bx, by = rnd.randrange(0, w - 1), rnd.randrange(0, h)
        put(x0 + bx, y0 + by, tones[idx[0] - 1])
        if rnd.random() < 0.5:
            put(x0 + bx + 1, y0 + by, tones[idx[0] - 1])


# --- pole skins -------------------------------------------------------------------------------------------------

# The poles' skin: a finer treated-wood ramp than WOOD (close steps, like a vanilla log's side), dark to light.
POLE = [hx(c) for c in ("#2B1E13", "#332417", "#3B2A1B", "#43301F", "#4B3624", "#543D29", "#5C432D", "#654A32", "#6E5237", "#77593C")]


def pole_skin(x0, w, ramp, seed, facet=None):
    """A pole's unrolled skin, 512 tall, painted like a vanilla log's side: a smooth ramp across (one tone per column,
    close steps of POLE) and the grain running up it as long, soft runs one step off the column's tone (16-56 px, never
    an isolated pixel); no cracks or bands, so nothing busy shows at a distance."""
    rnd = random.Random(seed)
    for c in range(w):
        base = ramp[c if facet is None else c % facet]
        pos = -rnd.randrange(0, 40)
        while pos < H:
            run = rnd.randint(16, 56)
            off = rnd.choice((-1, 0, 0, 0, 1))
            for y in range(max(0, pos), min(H, pos + run)):
                put(x0 + c, y, POLE[max(0, min(len(POLE) - 1, base + off))])
            pos += run


# LV: 8 facets of 2 px, one ramp 8 px wide twice round (period 8: lightest just off centre, darker to the edges).
LV_RAMP = [4, 5, 6, 7, 7, 6, 5, 4]
pole_skin(0, 16, LV_RAMP + LV_RAMP, 1101)
# MV / HV: 10 facets of 3 px, each carrying a gentle ramp across it.
pole_skin(16, 30, [5, 7, 6], 1202, facet=3)

# --- fields -----------------------------------------------------------------------------------------------------
spangle_field(48, 0, 128, 128, GALV, (4, 5, 6), 2201)
streak_field(48, 128, 128, 48, WOOD, 5, 2302, along_x=True, min_run=8, max_run=40, bias=(-1, -1, 0, 0, 0, 1))
# A few knots in the timber: dark 3x2 ovals with a lighter ring, kept off the field's edges.
rnd = random.Random(2303)
for _ in range(9):
    kx, ky = rnd.randrange(52, 170), rnd.randrange(131, 172)
    for dx, dy in ((0, 0), (1, 0), (2, 0), (0, 1), (1, 1), (2, 1)):
        put(kx + dx, ky + dy, WOOD[3] if (dx, dy) not in ((1, 0), (1, 1)) else WOOD[2])
    put(kx - 1, ky, WOOD[6])
    put(kx + 3, ky + 1, WOOD[6])
blotch_field(176, 0, 64, 32, CONC, (2, 3, 4), 2404)

# --- edge strips ------------------------------------------------------------------------------------------------
# Rows (128 long) and columns (128 tall), grained: runs of two neighbouring tones.
EDGES = [  # (lit tones, dark tones)
    ((GALV[8], GALV[9]), (GALV[2], GALV[1])),
    ((WOOD[6], WOOD[7]), (WOOD[2], WOOD[1])),
    ((CONC[4], CONC[5]), (CONC[1], CONC[0])),
]
for k, (lit, dark) in enumerate(EDGES):
    for j, pair in enumerate((lit, dark)):
        rnd = random.Random(3000 + k * 10 + j)
        row = []
        while len(row) < 128:
            row += [pair[rnd.random() < 0.3]] * rnd.randint(4, 14)
        for i in range(128):
            put(48 + i, 176 + 2 * k + j, row[i])
        col = []
        while len(col) < 128:
            col += [pair[rnd.random() < 0.3]] * rnd.randint(4, 14)
        for i in range(128):
            put(48 + 2 * k + j, 192 + i, col[i])


# --- ramps ------------------------------------------------------------------------------------------------------

def ramp(period, tones):
    """Tone indices across a round part `period` px wide: lightest just left of centre, darkest at the edges, in
    1-2 px steps (5-7 tones on the wider ones)."""
    n = len(tones)
    centre = period * 0.42
    out = []
    for k in range(period):
        d = abs(k + 0.5 - centre) / max(1.0, period / 2.0)
        out.append(max(0, min(n - 1, round((n - 1) * (1.0 - d * 0.9)))))
    return out


def ramp_x(period):
    return (period - 1) * period // 2 - 1


for row, tones in enumerate((GALV[1:9], PORC[:6], GLASS[:6], CAN, COPPER)):
    for p in range(2, 15):
        r = ramp(p, tones)
        # The glaze catches one glint on the widest ramps.
        for k in range(p):
            c = tones[r[k]]
            if tones in (PORC[:6], GLASS[:6]) and p >= 6 and k == round(p * 0.42) - 1:
                c = (PORC if tones == PORC[:6] else GLASS)[6]
            for yy in range(16):
                put(64 + ramp_x(p) + k, 256 + 16 * row + yy, c)

# Disc tops: 8 rows from the cap (top) to the rim (bottom), every column alike.
for x0, tones in ((176, PORC), (184, GLASS)):
    rows = [tones[6], tones[5], tones[5], tones[4], tones[4], tones[3], tones[2], tones[1]]
    for y in range(8):
        for x in range(8):
            put(x0 + x, 32 + y, rows[y])

# --- modules ----------------------------------------------------------------------------------------------------
# Danger sign, 10x12: black rim and header (a row of yellow letter-strokes), hazard yellow below with a red bolt
# outlined in its dark red.
sx, sy = 176, 40
rect(sx, sy, 10, 12, HAZ_YELLOW)
for x in range(10):
    put(sx + x, sy, HAZ_BLACK)
    put(sx + x, sy + 11, HAZ_BLACK)
for y in range(12):
    put(sx, sy + y, HAZ_BLACK)
    put(sx + 9, sy + y, HAZ_BLACK)
rect(sx + 1, sy + 1, 8, 3, HAZ_BLACK)
for x in (2, 3, 5, 6):
    put(sx + x, sy + 2, HAZ_YELLOW)
# The bolt: down from the top right in two strokes with a jog, its lower right edge in the dark red.
bolt = [(6, 4), (7, 4), (5, 5), (6, 5), (4, 6), (5, 6), (3, 7), (4, 7), (5, 7), (6, 7), (7, 7), (5, 8), (6, 8), (4, 9), (5, 9), (3, 10), (4, 10)]
for x, y in bolt:
    put(sx + x, sy + y, RED)
for x, y in ((7, 7), (6, 8), (5, 9), (4, 10)):
    put(sx + x, sy + y, RED_DARK)

# End grain for the pole tops: rings round a dark pith, one radial check.
ex, ey = 192, 40
for y in range(12):
    for x in range(12):
        d = ((x - 5.5) ** 2 + (y - 5.5) ** 2) ** 0.5
        ring = int(d * 1.15)
        put(ex + x, ey + y, WOOD[(6, 5, 6, 4, 5, 6, 4, 3)[min(ring, 7)]])
put(ex + 5, ey + 5, WOOD[2])
put(ex + 6, ey + 6, WOOD[2])
for k in range(1, 5):
    put(ex + 6 + k, ey + 5, WOOD[1])

# Pole tag, 3x4: aluminium with two stamped digits.
tx, ty = 208, 40
rect(tx, ty, 3, 4, ALU[2])
for x in range(3):
    put(tx + x, ty + 3, ALU[1])
put(tx + 2, ty, ALU[1])
put(tx + 2, ty + 1, ALU[1])
put(tx + 1, ty + 1, ALU[0])
put(tx + 1, ty + 2, ALU[0])

# Transformer lid, 12x12: the can's paint in flat rings (rim, lid, raised centre).
lx, ly = 212, 40
for y in range(12):
    for x in range(12):
        d = ((x - 5.5) ** 2 + (y - 5.5) ** 2) ** 0.5
        put(lx + x, ly + y, CAN[5] if d < 2.2 else CAN[4] if d < 4.6 else CAN[3])

# --- swatches ---------------------------------------------------------------------------------------------------
for i, (_, _, c) in enumerate(SWATCHES):
    rect(64 + 4 * (i % 32), 224 + 4 * (i // 32), 4, 4, c)


# --- particles and item sprites ----------------------------------------------------------------------------------
# Breaking particles: a 16x16 crop of a pole's skin and of the spangle.
PARTICLE_WOOD = img.crop((16, 64, 32, 80))
PARTICLE_STEEL = img.crop((60, 20, 76, 36))

OUTLINE = hx("#0C0D0F")


def sprite(shapes, exterior=False):
    """A 16x16 item sprite from flat shapes (lists of (x, y, colour)), outlined in #0C0D0F wherever a clear pixel
    touches a filled one (items: 4-6 flat tones, straight clean edges)."""
    out = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    p = out.load()
    for x, y, c in shapes:
        if 0 <= x < 16 and 0 <= y < 16:
            p[x, y] = c
    filled = {(x, y) for x in range(16) for y in range(16) if p[x, y][3] > 0}
    # Clear pixels reachable from outside the sprite's bounds (for an outline round the outside only).
    outside = set()
    if exterior:
        frontier = [(x, y) for x in range(-1, 17) for y in (-1, 16)] + [(x, y) for x in (-1, 16) for y in range(16)]
        outside = set(frontier)
        while frontier:
            x, y = frontier.pop()
            for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if -1 <= nx <= 16 and -1 <= ny <= 16 and (nx, ny) not in outside and (nx, ny) not in filled:
                    # Diagonal-only gaps between members still count as inside, so the outline hugs the silhouette.
                    if 0 <= nx < 16 and 0 <= ny < 16 and sum((nx + dx, ny + dy) in filled for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))) >= 2:
                        continue
                    outside.add((nx, ny))
                    frontier.append((nx, ny))
    for x in range(16):
        for y in range(16):
            if (x, y) in filled or (exterior and (x, y) not in outside):
                continue
            if any((x + dx, y + dy) in filled for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                p[x, y] = OUTLINE
    return out


def box(x0, y0, x1, y1, lit, dark, face=None):
    """A flat box x0..x1, y0..y1 inclusive: lit top row and left column, dark bottom row and right column."""
    px_ = []
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            c = face if face is not None else lit
            if x == x1 or y == y1:
                c = dark
            elif x == x0 or y == y0:
                c = lit
            px_.append((x, y, c))
    return px_


def vbar(x, y0, y1, lit, dark):
    """A two-wide upright (a pole): lit left column, dark right."""
    return [(x, y, lit) for y in range(y0, y1 + 1)] + [(x + 1, y, dark) for y in range(y0, y1 + 1)]


def line(x0, y0, x1, y1, c):
    pts, n = [], max(abs(x1 - x0), abs(y1 - y0))
    for k in range(n + 1):
        pts.append((round(x0 + (x1 - x0) * k / n), round(y0 + (y1 - y0) * k / n), c))
    return pts


WL, WM, WD = WOOD[7], WOOD[5], WOOD[3]
PL, PM, PD = PORC[5], PORC[4], PORC[2]
GL, GM, GD = GALV[9], GALV[6], GALV[2]
TL, TM, TD = GLASS[5], GLASS[3], GLASS[1]


def pin(x, y):
    """A 2x2 porcelain pin insulator with its top left at (x, y)."""
    return [(x, y, PL), (x + 1, y, PM), (x, y + 1, PM), (x + 1, y + 1, PD)]


ITEMS = {}
# LV Distribution Pole: the pole's top, one crossarm with three pins, and the neutral's pin on its standoff below.
ITEMS["lv_distribution_pole"] = sprite(
    vbar(7, 2, 15, WL, WD) + box(0, 5, 15, 6, WL, WD) + pin(1, 3) + pin(4, 3) + pin(12, 3)
    + pin(3, 9) + [(5, 11, GM), (6, 11, GD)])
# MV Sub-Transmission Pole: the pole, the short upper arm and the wide lower arm with their posts, the shield peak.
ITEMS["mv_sub_transmission_pole"] = sprite(
    vbar(7, 2, 15, WL, WD) + box(2, 4, 9, 5, WL, WD) + box(0, 9, 15, 10, WL, WD)
    + [(x, y, c) for x in (2, 3) for y, c in ((1, PL), (2, PM), (3, PD))]
    + [(x, y, c) for x in (0, 1, 14, 15) for y, c in ((6, PL), (7, PM), (8, PD))]
    + [(7, 0, GL), (8, 0, GM), (7, 1, GM), (8, 1, GD)])
# HV H-Frame Tower: two poles, the crossbeam, three strings, the X-brace and two shield peaks.
ITEMS["hv_h_frame_tower"] = sprite(
    vbar(2, 2, 15, WL, WD) + vbar(12, 2, 15, WL, WD) + box(0, 3, 15, 4, WL, WD)
    + [(1, y, c) for y, c in ((5, TL), (6, TM), (7, TL), (8, TD))] + [(x, y, c) for x in (7, 8) for y, c in ((5, TL), (6, TM), (7, TL), (8, TD))]
    + [(14, y, c) for y, c in ((5, TL), (6, TM), (7, TL), (8, TD))]
    + line(4, 10, 11, 15, GM) + line(11, 10, 4, 15, GL) + [(2, 0, GL), (2, 1, GM), (13, 0, GL), (13, 1, GD)])
# EHV Lattice Tower: battered legs to a waist, the body, three arm levels with strings, X-bracing, the shield peak. Its
# members are 1 px, so only the outside of the whole is outlined (like iron bars), leaving the lattice open.
ehv = []
ehv += line(2, 15, 6, 7, GL) + line(13, 15, 9, 7, GM) + [(6, y, GL) for y in range(1, 8)] + [(9, y, GM) for y in range(1, 8)]
ehv += line(3, 14, 11, 9, GD) + line(12, 14, 4, 9, GD) + [(x, 12, GM) for x in range(4, 12)]
ehv += [(x, 2, GL) for x in range(3, 13)] + [(x, 4, GL) for x in range(1, 15)] + [(x, 7, GM) for x in range(2, 14)]
ehv += line(6, 1, 9, 4, GD) + line(9, 1, 6, 4, GD) + [(7, 0, GL), (8, 0, GM)]
ehv += [(3, 3, TL), (12, 3, TM), (1, 5, TL), (1, 6, TM), (14, 5, TL), (14, 6, TM), (2, 8, TL), (2, 9, TM), (13, 8, TL), (13, 9, TM)]
ITEMS["ehv_lattice_tower"] = sprite(ehv, exterior=True)


def bushing_tones(glass):
    """A bushing's lit and mid tones: glass for HV and EHV, porcelain below."""
    return (TL, TM) if glass else (PL, PM)


def substation_sprite(primary, high_glass, low_glass):
    """A Substation Transformer: the galvanized tank (lit left, shade right) on its pad, radiator fins each side, the
    conservator drum on the lid, and the bushings in their tiers' insulators: three taller (primary, `primary` px) behind
    three short."""
    shapes = box(1, 14, 14, 15, CONC[3], CONC[1], CONC[2])
    shapes += box(4, 7, 11, 13, GL, GD, GM)
    for x in (1, 3, 12, 14):
        shapes += [(x, y, GM if x in (3, 12) else GD) for y in range(8, 13)]
    shapes += [(x, 6, GM) for x in range(5, 11)] + [(5, 6, GL), (10, 6, GD)]
    hl, hm = bushing_tones(high_glass)
    ll, lm = bushing_tones(low_glass)
    for x in (5, 8, 11):
        for k in range(primary):
            shapes.append((x - 1, 5 - k, hl if k % 2 else hm))
    for x in (5, 8, 11):
        shapes += [(x, 6, lm), (x, 5, ll)]
    return sprite(shapes)


def unit_sprite(size, glass):
    """A Unit Transformer: the tank (wider and taller with the tier), its cable box at the front (left) and three
    bushings in its tier's insulator."""
    top = 9 - size
    shapes = box(4 - size // 2, top, 12 + (size + 1) // 2, 15, GL, GD, GM) + box(1, 11, 3, 14, GL, GD, GM)
    lit, mid = bushing_tones(glass)
    for x in (5, 8, 11):
        for k in range(size + 2):
            shapes.append((x, top - 1 - k, lit if k % 2 else mid))
    return sprite(shapes)


ITEMS["mv_substation_transformer"] = substation_sprite(2, False, False)
ITEMS["hv_substation_transformer"] = substation_sprite(3, True, False)
ITEMS["ehv_substation_transformer"] = substation_sprite(4, True, True)
for tier, size in (("lv", 0), ("mv", 1), ("hv", 2), ("ehv", 3)):
    ITEMS[tier + "_unit_transformer"] = unit_sprite(size, size >= 2)


def fuse_sprite(mv):
    """A Fuse Cutout: its bracket off to the left, the porcelain body with sheds, the contacts and the brown fuse tube."""
    shapes = box(1, 6, 4, 7, GL, GD, GM)
    for y in range(4, 12):
        shed = y % 2 == 0
        for x in range(5 if shed else 6, 10 if shed else 9):
            shapes.append((x, y, PL if x < 7 else PM if x < 8 else PD))
    shapes += box(6, 2, 12, 3, GL, GD, GM) + box(6, 12, 12, 13, GL, GD, GM)
    shapes += [(11, y, WM if y % 2 else WL) for y in range(3, 12)]
    if mv:
        shapes += [(4, 5, PM), (10, 9, PM)]
    return sprite(shapes)


def breaker_sprite(size, glass):
    """A Recloser or Circuit Breaker: the tank on its feet, the cabinet with its red flag, and its two rows of bushings
    (glass for HV and EHV), bigger with the tier."""
    top = 9 - size
    shapes = box(2, top, 11, 14, GL, GD, GM) + box(12, top + 1, 14, 12, GL, GD, GM) + [(13, top + 3, RED)]
    shapes += [(3, 15, GD), (10, 15, GD)]
    lit, mid = bushing_tones(glass)
    for x in (3, 6, 9):
        for k in range(size + 2):
            shapes.append((x, top - 1 - k, lit if k % 2 else mid))
            shapes.append((x + 1, top - 1 - k, mid if k % 2 else lit))
    return sprite(shapes)


def disconnect_sprite(size, glass):
    """A Disconnect Switch: the base, and per phase two posts with the blade across their tops (taller with the tier)."""
    shapes = box(0, 13, 15, 14, GL, GD, GM)
    lit, mid = bushing_tones(glass)
    h = 4 + size
    for x in (1, 6, 11):
        for k in range(h):
            shapes += [(x, 12 - k, lit if k % 2 else mid), (x + 3, 12 - k, lit if k % 2 else mid)]
        shapes += [(xx, 12 - h, GL) for xx in range(x, x + 4)]
    return sprite(shapes)


def dead_end_sprite(mv):
    """A Dead-End Pole: the pole's top, a double crossarm, and a strain string off each end of it (both ways)."""
    shapes = vbar(7, 2 if mv else 3, 15, WL, WD) + box(1, 5, 14, 6, WL, WD) + box(1, 8, 14, 9, WL, WD)
    for x in (0, 15):
        shapes += [(x, 4, PL), (x, 7, PM), (x, 10, PD)]
    shapes += [(3, 7, PM), (12, 7, PM)]
    if mv:
        shapes += [(7, 0, GL), (8, 0, GM), (7, 1, GM), (8, 1, GD)]
    return sprite(shapes)


def service_sprite():
    """A Service Meter: the mast and its weatherhead, the spools, and the meter box with its glass dome."""
    shapes = vbar(9, 1, 15, GL, GD) + box(7, 0, 12, 1, GL, GD, GM)
    shapes += [(6, 3, PL), (8, 3, PM), (12, 3, PM)]
    shapes += box(2, 7, 8, 13, GL, GD, GM) + [(4, 9, TL), (5, 9, TM), (4, 10, TM), (5, 10, TL)]
    return sprite(shapes)


ITEMS["lv_fuse_cutout"] = fuse_sprite(False)
ITEMS["mv_fuse_cutout"] = fuse_sprite(True)
for tier, size, name in (("lv", 0, "recloser"), ("mv", 1, "recloser"), ("hv", 2, "circuit_breaker"), ("ehv", 3, "circuit_breaker")):
    ITEMS[tier + "_" + name] = breaker_sprite(size, size >= 2)
    ITEMS[tier + "_disconnect_switch"] = disconnect_sprite(size, size >= 2)
ITEMS["lv_dead_end_pole"] = dead_end_sprite(False)
ITEMS["mv_dead_end_pole"] = dead_end_sprite(True)
ITEMS["service_meter"] = service_sprite()


def main():
    repo = sys.argv[1] if len(sys.argv) > 1 else "."
    out = os.path.join(repo, "src/main/resources/assets/arcforgetransmission/textures/block/transmission_tower/towers.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out)
    PARTICLE_WOOD.save(os.path.join(os.path.dirname(out), "particle_wood.png"))
    PARTICLE_STEEL.save(os.path.join(os.path.dirname(out), "particle_steel.png"))
    items = os.path.join(repo, "src/main/resources/assets/arcforgetransmission/textures/item")
    os.makedirs(items, exist_ok=True)
    for name, im in ITEMS.items():
        im.save(os.path.join(items, name + ".png"))
    print("wrote", out, "swatches:", len(SWATCHES), "items:", len(ITEMS))


if __name__ == "__main__":
    main()
