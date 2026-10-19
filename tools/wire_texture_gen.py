"""The wires' textures: the conductor sprites, the EV glow, the coil items and Burnt Wire.

    python tools/wire_texture_gen.py <addon repo>

Writes (assets/arcforgetransmission/textures/):
    block/wire/{lv,mv,hv,ev}.png   16x16: u along the wire, v across it; the 16 rows are the conductor's round profile
                                   (rim dark, lit just above the middle: light from the top), with the strands' lay as a
                                   one-step diagonal every 4 px along it (the EV superconductor: a dark sheath with its
                                   cyan core in rows 6 to 9)
    block/wire/ev_wire_glow.png    the EV core alone (rows 6 to 9), drawn full-bright over it; the rest clear
    item/{tier}_wire_coil.png      a wooden spool wound with the tier's conductor
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
WOOD = [hx(c) for c in ("#3A2616", "#553A22", "#725033", "#8E6A44")]
CHAR = [hx(c) for c in ("#0E0C0B", "#1F1A17", "#2F2824", "#463B34")]
CLEAR = (0, 0, 0, 0)

# The profile across the 16 rows: the ramp index of each (rim, lit band just above the middle, rim).
PROFILE = [0, 1, 1, 2, 2, 3, 4, 4, 3, 3, 2, 2, 1, 1, 1, 0]


def wire(tier):
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    ramp = RAMPS[tier]
    for v in range(16):
        for u in range(16):
            t = PROFILE[v]
            if tier != "ev" and (u + v) % 4 == 0 and 1 < t < 4:
                # The strands' lay: one step darker on a diagonal.
                t -= 1
            c = ramp[t]
            if tier == "ev" and 6 <= v <= 9:
                c = EV_CORE[2] if v in (7, 8) and u % 8 != 7 else EV_CORE[1] if v in (7, 8) else EV_CORE[0]
            px[u, v] = c
    return img


def glow():
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    for v in range(6, 10):
        for u in range(16):
            px[u, v] = EV_CORE[2] if v in (7, 8) and u % 8 != 7 else EV_CORE[1] if v in (7, 8) else EV_CORE[0]
    return img


def coil(tier):
    """A spool: two wooden flanges (left and right) and the conductor wound between them in rows."""
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    ramp = RAMPS[tier]
    for y in range(2, 14):
        for x in (2, 3, 12, 13):
            edge = x in (2, 13) or y in (2, 13)
            px[x, y] = WOOD[0] if edge else WOOD[2 if x == 3 else 1]
    for y in range(4, 12):
        for x in range(4, 12):
            # Turns: a lit row then a darker one; lit toward the top.
            t = 4 if y == 4 else 3 if y % 2 == 0 else 1
            if y >= 9:
                t = max(0, t - 1)
            c = ramp[t]
            if tier == "ev" and y % 2 == 0 and 5 <= x <= 10:
                c = EV_CORE[1]
            px[x, y] = c
    # The loose end.
    for i, (x, y) in enumerate(((12, 5), (13, 4), (14, 4))):
        px[x, y] = ramp[3] if i else ramp[2]
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
    save(glow(), os.path.join(tex, "block/wire/ev_wire_glow.png"))
    save(burnt(), os.path.join(tex, "item/burnt_wire.png"))
    print("wrote the wire textures")


if __name__ == "__main__":
    main()
