"""The wires' textures: the conductor sprites, the EV glow, the coil items and Burnt Wire.

    python tools/wire_texture_gen.py <addon repo>

Writes (assets/arcforgetransmission/textures/):
    block/wire/{lv,mv,hv,ev}.png   16x16, one flat tone: the conductor (the EV superconductor's dark sheath)
    block/wire/ev_wire_glow.png    the EV core's glow, one flat tone, drawn full-bright over the wire
    item/{tier}_wire_coil.png      a black plastic spool wound with the tier's conductor
    item/wire_cutters.png          red-handled cable cutters
    item/burnt_wire.png            a charred, broken length of wire

The conductors: LV copper (Tempered copper), MV aluminium, HV silver alloy, EV superconductor. Flat tones, no noise:
everything is a pure function of position, so the build is byte-identical every run.
"""
import os
import sys

from PIL import Image

NS = "arcforgetransmission"


def hx(s):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255)


# Each ramp dark to light (5 tones).
RAMPS = {
    "lv": [hx(c) for c in ("#3C1C0C", "#8A4822", "#AE6632", "#C98244", "#E0A062")],
    "mv": [hx(c) for c in ("#4A5056", "#7E868D", "#A3AAB0", "#C3C8CC", "#DDE1E4")],
    "hv": [hx(c) for c in ("#55575C", "#8C8F96", "#B3B6BC", "#D3D5DA", "#EEEFF2")],
    "ev": [hx(c) for c in ("#101418", "#1E262C", "#2C363E", "#3C4852", "#4E5C66")],
}
EV_CORE = [hx(c) for c in ("#2E96B6", "#6AD2EE", "#B4F2FF")]
# The strung EV wire's glow (added full-bright over its sheath): a dim cyan, so it reads as a cable that glows, not a neon tube.
EV_GLOW = hx("#0F4A58")
WOOD = [hx(c) for c in ("#3A2616", "#553A22", "#725033", "#8E6A44")]
CHAR = [hx(c) for c in ("#0E0C0B", "#1F1A17", "#2F2824", "#463B34")]
CLEAR = (0, 0, 0, 0)

# The profile across the 16 rows: the ramp index of each (rim, lit band just above the middle, rim).
PROFILE = [0, 1, 1, 2, 2, 3, 4, 4, 3, 3, 2, 2, 1, 1, 1, 0]


# The strung conductors' tones: dark and weathered, as real line wire reads against the sky (a bright tone makes a thin
# wire look like a glowing rod): copper gone brown, dull aluminium, darker silver alloy, the superconductor's black
# sheath.
WIRE = {"lv": hx("#5E3219"), "mv": hx("#575D63"), "hv": hx("#4B4F55"), "ev": hx("#1A2126")}


def wire(tier):
    """One flat tone (WireRenderer draws a solid square tube and lights its sides by their normals; a flat sprite is what
    keeps a thin wire from shimmering at a distance)."""
    return Image.new("RGBA", (16, 16), WIRE[tier])


def glow():
    """The EV core's glow: one flat tone (drawn full-bright over the whole 2 px wire, additively, so the dark sheath shows
    through as a cyan glow; a core narrower than the wire would be finer than a pixel)."""
    return Image.new("RGBA", (16, 16), EV_GLOW)


# The spool's black plastic (dark to light).
PLASTIC = [hx(c) for c in ("#0E0F10", "#1C1E20", "#2B2E31", "#3D4145", "#53585D")]


def coil(tier):
    """A spool of the tier's conductor seen a little from above: two black plastic flanges (discs wider than the winding,
    the top one with its hub hole) and the wire wound between them in turns, lit left of the middle."""
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    ramp = RAMPS[tier]
    cx, rx, ry = 7.5, 7.0, 2.0
    top, bottom, body_rx = 3.5, 12.5, 4.8

    def inside(x, y, cy, sx, sy):
        return ((x + 0.5 - cx) / sx) ** 2 + ((y + 0.5 - cy) / sy) ** 2 <= 0.92

    def disc(cy, upper):
        for y in range(16):
            for x in range(16):
                edge = inside(x, y, cy + 1.0, rx, ry) and not inside(x, y, cy, rx, ry)
                if edge:
                    px[x, y] = PLASTIC[0]
                elif inside(x, y, cy, rx, ry):
                    rim = not inside(x, y, cy, rx - 1.2, ry - 0.7)
                    px[x, y] = PLASTIC[2] if rim else PLASTIC[4] if x + 0.5 < cx - 1 else PLASTIC[3]
                    if upper and inside(x, y, cy, 1.6, 0.7):
                        px[x, y] = PLASTIC[0]

    disc(bottom, False)
    for y in range(16):
        for x in range(16):
            if abs(x + 0.5 - cx) <= body_rx and top + 0.5 <= y + 0.5 <= bottom + 0.4:
                d = (x + 0.5 - (cx - 1.2)) / body_rx
                t = 4 if abs(d) < 0.3 else 3 if abs(d) < 0.7 else 2 if d < 1.2 else 1
                if x + 0.5 > cx + body_rx - 1:
                    t = 1
                # Turns: every other row a step darker.
                if y % 2 == 1:
                    t = max(0, t - 1)
                c = ramp[t]
                if tier == "ev" and y % 2 == 0 and abs(d) < 0.7:
                    c = EV_CORE[1]
                px[x, y] = c
    disc(top, True)
    # The loose end: off the winding's right side and out past the flanges.
    for i, (x, y) in enumerate(((13, 8), (14, 8), (15, 7))):
        px[x, y] = ramp[3] if i else ramp[2]
    return outline(img)


def outline(img):
    """The item outline (#0C0D0F) wherever a clear pixel touches a filled one."""
    px = img.load()
    filled = {(x, y) for x in range(16) for y in range(16) if px[x, y][3] > 0}
    for x in range(16):
        for y in range(16):
            if (x, y) not in filled and any((x + dx, y + dy) in filled for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                px[x, y] = OUTLINE
    return img


OUTLINE = hx("#0C0D0F")
RED = [hx(c) for c in ("#5E1612", "#9E2A20", "#D23A2C", "#F06A58")]
STEEL = [hx(c) for c in ("#16181B", "#2A2E33", "#43484F", "#646B74", "#8C949D")]


# The head, neck and pivot (L lit, S mid and D dark steel, K the black neck, P the bolt); the handles are drawn after.
CUTTER_HEAD = [
    ".............L..",
    "...........LLSD.",
    "..........LS.SD.",
    ".........LS..D..",
    ".........SSSD...",
    "........KSD.....",
    ".......KPK......",
    "......KKK.......",
]


def cutters():
    """Cable cutters laid like a vanilla tool (head up-right, handles down-left): the polished steel head, its hooked
    jaws round the open cutting eye, the blackened neck with the pivot bolt, and the two red-dipped handles splaying
    apart from it in a V (as a cutter's do), each 2 px thick, lit on its upper edge, its rounded end a step darker."""
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    tones = {"D": STEEL[2], "S": STEEL[3], "L": STEEL[4], "K": STEEL[1], "P": STEEL[3]}
    for y, row in enumerate(CUTTER_HEAD):
        for x, ch in enumerate(row):
            if ch in tones:
                px[x, y] = tones[ch]
    # The handles' centre lines, from the neck: one down the diagonal, one steeper, so they open apart.
    for (x0, y0), (x1, y1) in (((6, 8), (1, 11)), ((7, 8), (4, 14))):
        n = max(abs(x1 - x0), abs(y1 - y0))
        for k in range(n + 1):
            x, y = round(x0 + (x1 - x0) * k / n), round(y0 + (y1 - y0) * k / n)
            end = k == n
            px[x, y] = RED[1] if end else RED[3]
            px[x + 1, y] = RED[1] if end else RED[2]
    return outline(img)


def burnt():
    """A charred, kinked length of wire with a bare, blackened copper end."""
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    path = [(2, 12), (3, 11), (4, 11), (5, 10), (6, 10), (7, 9), (8, 9), (9, 8), (9, 7), (10, 6), (11, 5), (12, 5), (13, 4)]
    for i, (x, y) in enumerate(path):
        px[x, y] = CHAR[2 + (i % 2)]
        px[x, y + 1] = CHAR[1]
        if i % 3 == 0:
            px[x, y - 1] = CHAR[3]
    px[13, 3] = RAMPS["lv"][1]
    px[14, 3] = RAMPS["lv"][0]
    px[1, 13] = CHAR[0]
    return img


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=False)


def main():
    repo = sys.argv[1] if len(sys.argv) > 1 else "."
    tex = os.path.join(repo, "src/main/resources/assets", NS, "textures")
    for tier in RAMPS:
        save(wire(tier), os.path.join(tex, "block/wire", tier + ".png"))
        save(coil(tier), os.path.join(tex, "item", tier + "_wire_coil.png"))
    save(glow(), os.path.join(tex, "block/wire/ev_wire_glow.png"))
    save(cutters(), os.path.join(tex, "item/wire_cutters.png"))
    save(burnt(), os.path.join(tex, "item/burnt_wire.png"))
    print("wrote the wire textures")


if __name__ == "__main__":
    main()
