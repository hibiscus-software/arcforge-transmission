"""The wires' textures: the conductor sprites, the EHV glow, the coil items and Burnt Wire.

    python tools/wire_texture_gen.py <addon repo>

Writes (assets/arcforgetransmission/textures/):
    block/wire/{lv,mv,hv,ehv}.png   16x16, one flat tone: the conductor (the EHV superconductor's dark sheath)
    block/wire/ehv_wire_glow.png    the EHV core's glow, one flat tone, drawn full-bright over the wire
    item/{tier}_wire_coil.png      a black plastic spool wound with the tier's conductor
    block/wire/{lv,mv}_insulated.png, item/{lv,mv}_insulated_wire_coil.png   insulated wire: black rubber, flat; its spool
    item/wire_cutters.png          the Wire Cutters, from the addon's pixel art, in Arcforge's palette
    item/burnt_wire.png            a charred, broken length of wire

The conductors: LV copper (Tempered copper), MV aluminium, HV silver alloy, EHV superconductor. Flat tones, no noise:
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
    "ehv": [hx(c) for c in ("#101418", "#1E262C", "#2C363E", "#3C4852", "#4E5C66")],
}
EHV_CORE = [hx(c) for c in ("#2E96B6", "#6AD2EE", "#B4F2FF")]
# The strung EHV wire's glow (added full-bright over its sheath): a dim cyan, so it reads as a cable that glows, not a neon tube.
EHV_GLOW = hx("#0F4A58")
WOOD = [hx(c) for c in ("#3A2616", "#553A22", "#725033", "#8E6A44")]
CHAR = [hx(c) for c in ("#0E0C0B", "#1F1A17", "#2F2824", "#463B34")]
CLEAR = (0, 0, 0, 0)

# The profile across the 16 rows: the ramp index of each (rim, lit band just above the middle, rim).
PROFILE = [0, 1, 1, 2, 2, 3, 4, 4, 3, 3, 2, 2, 1, 1, 1, 0]


# The strung conductors' tones: dark and weathered, as real line wire reads against the sky (a bright tone makes a thin
# wire look like a glowing rod): copper gone brown, dull aluminium, darker silver alloy, the superconductor's black
# sheath.
WIRE = {"lv": hx("#5E3219"), "mv": hx("#575D63"), "hv": hx("#4B4F55"), "ehv": hx("#1A2126")}
# The rubber sheath of insulated LV and MV wire (Arcforge's rubber, vulcanized black), and its ramp on the coil.
RUBBER = hx("#25272A")
RUBBER_RAMP = [hx(c) for c in ("#24272B", "#34383D", "#454A50", "#585E65", "#6D747B")]
INSULATED = ("lv", "mv")


def wire(tier):
    """One flat tone (WireRenderer draws a solid square tube and lights its sides by their normals; a flat sprite is what
    keeps a thin wire from shimmering at a distance)."""
    return Image.new("RGBA", (16, 16), WIRE[tier])


def glow():
    """The EHV core's glow: one flat tone (drawn full-bright over the whole 2 px wire, additively, so the dark sheath shows
    through as a cyan glow; a core narrower than the wire would be finer than a pixel)."""
    return Image.new("RGBA", (16, 16), EHV_GLOW)


# The spool's black plastic (dark to light).
PLASTIC = [hx(c) for c in ("#0E0F10", "#1C1E20", "#2B2E31", "#3D4145", "#53585D")]


def coil(tier, insulated=False, ramp=None, tip=None, stripe=None):
    """A spool of the tier's conductor seen a little from above: two black plastic flanges (discs wider than the winding,
    the top one with its hub hole) and the wire wound between them in turns, lit left of the middle. Insulated wire is
    wound in its black rubber, the bare conductor showing at the loose end's tip. (ramp, tip, stripe: another winding,
    its tip's tone, and a tracer colour every third turn, for steel strand and underground cable.)"""
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    ramp = ramp or (RUBBER_RAMP if insulated else RAMPS[tier])
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
                if tier == "ehv" and y % 2 == 0 and abs(d) < 0.7 and stripe is None and not insulated and ramp is RAMPS["ehv"]:
                    c = EHV_CORE[1]
                if stripe is not None and y % 3 == 0 and abs(d) < 0.9:
                    c = stripe
                px[x, y] = c
    disc(top, True)
    # The loose end: off the winding's right side and out past the flanges (insulated: its tip stripped bare).
    for i, (x, y) in enumerate(((13, 8), (14, 8), (15, 7))):
        px[x, y] = (tip or RAMPS[tier][3]) if (insulated or tip) and i == 2 else ramp[3] if i else ramp[2]
    return outline(img)


# Galvanized steel strand (shield wire, guys) and the orange tracer of buried cable.
STRAND = [hx(c) for c in ("#4A5056", "#6A7178", "#8A9198", "#A8AEB4", "#C4C9CE")]
STRAND_TONE = hx("#7E858C")
TRACER = hx("#E0782A")


def fuse_link():
    """A Fuse Link: a short brown fibre tube with a steel cap each end and the button head on top, upright."""
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    brown = [hx(c) for c in ("#3E2414", "#5E3820", "#7E4E2C", "#9C6638")]
    for y in range(4, 12):
        for x, t in zip(range(6, 10), (3, 2, 1, 0)):
            px[x, y] = brown[t]
    for y in (2, 3, 12, 13):
        for x, t in zip(range(5, 11), (4, 4, 3, 2, 1, 0)):
            px[x, y] = STRAND[t]
    for x in (7, 8):
        px[x, 1] = STRAND[3 if x == 7 else 2]
    return outline(img)


def clamp_meter():
    """A Clamp Meter: the yellow body with its dark display and dial, and the steel clamp jaws open at the top."""
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    yellow = [hx(c) for c in ("#7A5A08", "#B8880E", "#E0B020", "#F5D060")]
    for y in range(6, 15):
        for x in range(4, 12):
            px[x, y] = yellow[3] if x == 4 or y == 6 else yellow[0] if x == 11 or y == 14 else yellow[2] if x < 9 else yellow[1]
    for y in range(7, 10):
        for x in range(5, 11):
            px[x, y] = hx("#1E2A24") if (x + y) % 5 else hx("#5FD4C4")
    for x, y in ((7, 11), (8, 11), (7, 12), (8, 12)):
        px[x, y] = STRAND[1] if (x, y) == (8, 12) else STRAND[3]
    # The jaws: a ring open on the right, above the body.
    for x, y in ((5, 5), (5, 4), (5, 3), (6, 2), (7, 1), (8, 1), (9, 2), (10, 3), (6, 5), (10, 5)):
        px[x, y] = STRAND[3] if x < 8 else STRAND[1]
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


# The Wire Cutters, from the addon's pixel art (one letter a pixel), in Arcforge's palette: the steel tools' outline and
# steel-ingot ramp for the jaws and pivot, the FE red accent for the dipped grips, the Input blue for their bands.
CUTTERS = [
    "...oo...........",
    "..o23o..........",
    ".o5o43o.........",
    ".o33o21oooo.....",
    ".o1344411Rroo...",
    "..oo444ooRRbbo..",
    "....o1o..oorrBo.",
    "....o1o....oRnn.",
    "....oHRo....oRbo",
    "....o1Ro.....oHo",
    ".....o1ro.....o.",
    ".....onrRo......",
    "......oBBHo.....",
    ".......oo1Ho....",
    ".........oo.....",
    "................",
]
CUTTER_TONES = {
    "o": hx("#16181B"),
    # Steel-ingot ramp, dark to light (#3C3F45 to #C4C9CF).
    "1": hx("#3C3F45"), "2": hx("#5B6067"), "3": hx("#7E848B"), "4": hx("#A1A7AE"), "5": hx("#C4C9CF"),
    # FE red: highlight, body, shade.
    "H": hx("#FF8577"), "R": hx("#E5483C"), "r": hx("#8E231C"),
    # Input blue: light, mid, dark.
    "B": hx("#4A8FE0"), "b": hx("#3A76C0"), "n": hx("#22497E"),
}


def cutters():
    """The Wire Cutters: red-dipped handles with blue grip bands, the steel jaws and pivot up-left, outlined like
    Arcforge's steel tools (a clear pixel touching the sprite with no outline gets one)."""
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    for y, row in enumerate(CUTTERS):
        for x, ch in enumerate(row):
            if ch in CUTTER_TONES:
                px[x, y] = CUTTER_TONES[ch]
    edge = CUTTER_TONES["o"]
    body = {(x, y) for x in range(16) for y in range(16) if px[x, y][3] and px[x, y] != edge}
    for x in range(16):
        for y in range(16):
            if px[x, y][3] == 0 and any((x + dx, y + dy) in body for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                px[x, y] = edge
    return img


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
    for tier in INSULATED:
        save(Image.new("RGBA", (16, 16), RUBBER), os.path.join(tex, "block/wire", tier + "_insulated.png"))
        save(coil(tier, True), os.path.join(tex, "item", tier + "_insulated_wire_coil.png"))
        save(coil(tier, True, stripe=TRACER), os.path.join(tex, "item", tier + "_underground_cable.png"))
    save(Image.new("RGBA", (16, 16), STRAND_TONE), os.path.join(tex, "block/wire/steel.png"))
    save(coil("lv", ramp=STRAND, tip=STRAND[4]), os.path.join(tex, "item/shield_wire_coil.png"))
    save(coil("lv", ramp=STRAND[:2] + STRAND[1:4], tip=STRAND[3], stripe=STRAND[0]), os.path.join(tex, "item/guy_wire.png"))
    save(fuse_link(), os.path.join(tex, "item/fuse_link.png"))
    save(clamp_meter(), os.path.join(tex, "item/clamp_meter.png"))
    save(glow(), os.path.join(tex, "block/wire/ehv_wire_glow.png"))
    save(cutters(), os.path.join(tex, "item/wire_cutters.png"))
    save(burnt(), os.path.join(tex, "item/burnt_wire.png"))
    print("wrote the wire textures")


if __name__ == "__main__":
    main()
