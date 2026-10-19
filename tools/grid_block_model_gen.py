"""The grid's placeable blocks: the four Inductors, the Pole-Mount Transformer, and the creative Source and Sink.

    python tools/grid_block_model_gen.py <addon repo>

Writes:
    assets/arcforgetransmission/models/block/inductor/<tier>.json and textures/block/inductor/<tier>.png
    assets/arcforgetransmission/models/block/pole_transformer.json (particles only) and textures/item/pole_transformer.png
    assets/arcforgetransmission/models/block/creative_{source,sink}/<tier>.json and their 16x16 textures
    data/arcforgetransmission/attach_groups/{inductor_<tier>,pole_transformer}.json

An Inductor is one insulator, like the ones on the towers (the same glazed porcelain, glass and galvanizing ramps as
tools/transmission_tower_texture_gen.py): a wire is one conductor, and an Inductor its one end, mounted on any face of the
block it serves (Immersive Engineering style). The model points up (mounted on the block below); the blockstates turn it
like an end rod. Per tier:
    LV  a porcelain pin insulator: two skirts and a crown with the tie groove, on a galvanized pin
    MV  a porcelain line post: a flange, a core with three sheds and a line clamp
    HV  three glass cap-and-pin discs on a clevis
    EV  four larger glass discs
The Pole-Mount Transformer (LV) is drawn by its block entity from TowerMesh.poleTransformer() (the can the LV pole used to
carry, hung on the same brackets, with three primaries A B C on the lid and the split-phase secondary L1 N L2 on its
front); here are only its particles, item sprite and attach points. The creative blocks are plain cubes on Arcforge's Creative (pink) ramp with an FE port (orange out, blue in) and one
pip per tier.

Element models with every face its own rect on a 64x64 sheet at 1 texel per px (never stretched). Round parts carry a
ramp across each face (lightest just left of centre, a glint on the glazes); their tops fall from the middle to the rim.
Hidden faces (under a wider part, on top of a narrower one) aren't emitted, so nothing z-fights. Deterministic: no
randomness, so the build is byte-identical every run.
"""
import json
import os
import sys

from PIL import Image

NS = "arcforgetransmission"
SHEET = 64


def hx(s):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255)


# The towers' ramps (tools/transmission_tower_texture_gen.py).
GALV = [hx(c) for c in ("#4E555C", "#6B737B", "#7D858D", "#89919A", "#8F979F", "#959DA5", "#9BA3AA", "#A6AEB5", "#B6BDC3", "#C9CFD4")]
PORC = [hx(c) for c in ("#2E170C", "#4A2615", "#63341D", "#7C4426", "#95552F", "#B06A3D", "#CC8D5E")]
GLASS = [hx(c) for c in ("#3F5B58", "#587A76", "#729590", "#8EB0AA", "#A8C6C0", "#C2DAD5", "#E0EEEB")]
CAN = [hx(c) for c in ("#4C524E", "#5F6661", "#737A75", "#878E88", "#9AA19B", "#ADB3AD", "#C3C8C2")]
RAMPS = {"galv": GALV[1:9], "porcelain": PORC[:6], "glass": GLASS[:6], "can": CAN}
GLINT = {"porcelain": PORC[6], "glass": GLASS[6]}
# Arcforge's Creative ramp and port colours.
CREATIVE = dict(deep=hx("#3A0A2E"), dark=hx("#882068"), face_shade=hx("#B42E8C"), face=hx("#D442AA"), mid=hx("#EC70C8"), lit=hx("#FFB8EC"))
STEEL_LIT, STEEL_DEEP = hx("#727982"), hx("#16181B")
PORT = {"out": [hx(c) for c in ("#F5B574", "#E8913A", "#9E5A1C")], "in": [hx(c) for c in ("#7FB3F0", "#4A8FE0", "#22497E")]}

TIERS = ("lv", "mv", "hv", "ev")


# --- parts ---------------------------------------------------------------------------------------------------------

def part(name, frm, to, material, faces=None, cull=None):
    faces = faces or ["north", "south", "east", "west", "up", "down"]
    return dict(name=name, frm=[round(v, 4) for v in frm], to=[round(v, 4) for v in to], material=material, faces=list(faces),
                cull=cull or {})


def stack(levels, cx=8.0, cz=8.0, first_bottom=False):
    """Upright round parts one on another: (name, y0, y1, half, material). A part's top is left out where the part on it
    is as wide or wider, its bottom where the part under it is (those faces are inside). The first part's bottom only
    shows if first_bottom (it stands on something otherwise)."""
    out = []
    for i, (name, y0, y1, half, material) in enumerate(levels):
        faces = ["north", "south", "east", "west"]
        below = levels[i - 1] if i > 0 and abs(levels[i - 1][2] - y0) < 1e-6 else None
        above = levels[i + 1] if i + 1 < len(levels) and abs(levels[i + 1][1] - y1) < 1e-6 else None
        if not (above and above[3] >= half - 1e-6):
            faces.append("up")
        if (below and below[3] < half - 1e-6) or (i == 0 and first_bottom):
            faces.append("down")
        out.append(part(name, (cx - half, y0, cz - half), (cx + half, y1, cz + half), material, faces))
    return out


def discs(y, count, rim, cap=1.2, pitch=3.2):
    """Cap-and-pin glass discs upward from y: each a pin, the glass rim, the glazed top stepping in, and the steel cap."""
    levels = []
    for i in range(count):
        levels += [(f"pin_{i}", y, y + 0.6, 0.6, "galv"),
                   (f"rim_{i}", y + 0.6, y + 1.6, rim, "glass"),
                   (f"glaze_{i}", y + 1.6, y + 2.4, round(rim * 0.6, 4), "glass"),
                   (f"cap_{i}", y + 2.4, y + pitch, cap, "galv")]
        y = round(y + pitch, 4)
    return levels, y


def inductor(tier):
    """Returns (parts, attach point px)."""
    if tier == "lv":
        levels = [("pin", 1, 2.5, 1.0, "galv"),
                  ("skirt", 2.5, 5.0, 3.5, "porcelain"),
                  ("waist", 5.0, 5.5, 2.5, "porcelain"),
                  ("skirt_upper", 5.5, 7.5, 3.0, "porcelain"),
                  ("neck", 7.5, 8.0, 2.0, "porcelain"),
                  ("crown", 8.0, 10.0, 2.5, "porcelain"),
                  ("groove", 10.0, 10.5, 1.5, "porcelain"),
                  ("tie", 10.5, 11.5, 2.0, "porcelain")]
        base = 3.0
    elif tier == "mv":
        levels = [("flange", 1, 2, 3.0, "galv"), ("core_0", 2, 3, 2.0, "porcelain")]
        y = 3.0
        for i in range(3):
            levels += [(f"shed_{i}", y, y + 1.0, 3.75, "porcelain"), (f"core_{i + 1}", y + 1.0, y + 3.0, 2.0, "porcelain")]
            y += 3.0
        levels += [("stud", y, y + 0.5, 1.5, "galv"), ("clamp", y + 0.5, y + 1.5, 2.0, "galv")]
        base = 3.5
    elif tier == "hv":
        levels, y = discs(2.0, 3, 4.0)
        levels = [("clevis", 1, 2, 1.0, "galv")] + levels + [("clamp", y, y + 1.0, 1.6, "galv")]
        base = 3.5
    else:
        levels, y = discs(2.0, 4, 5.0, cap=1.4)
        levels = [("clevis", 1, 2, 1.25, "galv")] + levels + [("clamp", y, y + 1.0, 2.0, "galv")]
        base = 4.0
    parts = [part("base", (8 - base, 0, 8 - base), (8 + base, 1, 8 + base), "galv", cull={"down": "down"})]
    parts += stack(levels)
    top = parts[-1]["to"][1]
    return parts, [8.0, top, 8.0]


def pole_transformer_terminals():
    """The transformer is drawn from TowerMesh.poleTransformer() (the can the LV pole used to carry); its attach points
    are read off the same constants: the primaries' tops on the lid, the secondaries' tips on the front."""
    can_z, can_y1 = 10.5, 16.0
    primary_top = can_y1 - 0.8 + 3.2
    t = {}
    for label, x in (("A", 5.2), ("B", 8.0), ("C", 10.8)):
        t[label] = [x, round(primary_top, 4), can_z]
    for label, x, z in (("L1", 5.0, 3.0), ("N", 8.0, 4.0), ("L2", 11.0, 3.0)):
        t[label] = [x, 6.0, z]
    return t


def transformer_item():
    """The item sprite: the can (vertical bands of its paint), its lid, three brown primaries and the secondaries."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    bands = [CAN[1], CAN[3], CAN[4], CAN[5], CAN[6], CAN[5], CAN[4], CAN[3], CAN[3], CAN[2], CAN[1]]
    for y in range(5, 15):
        for i, x in enumerate(range(3, 14)):
            c = bands[i]
            if y == 14:
                c = CAN[0]
            px[x, y] = c
    for x in range(2, 15):
        px[x, 4] = CAN[6] if x < 9 else CAN[4]
        px[x, 5] = CAN[2]
    for x in (4, 8, 12):
        px[x, 1] = PORC[5]
        px[x, 2] = PORC[4]
        px[x - 1, 2] = PORC[3]
        px[x + 1, 2] = PORC[2]
        px[x, 3] = PORC[3]
    for x, c in ((5, PORC[4]), (8, GALV[7]), (11, PORC[4])):
        px[x, 9] = c
        px[x, 10] = PORC[1] if c != GALV[7] else GALV[3]
    for x in range(3, 14):
        px[x, 15] = (0, 0, 0, 0)
    return img


# --- UV packing ---------------------------------------------------------------------------------------------------

def face_size(p, face):
    (x0, y0, z0), (x1, y1, z1) = p["frm"], p["to"]
    dx, dy, dz = x1 - x0, y1 - y0, z1 - z0
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy), "up": (dx, dz), "down": (dx, dz)}[face]


def pack(parts):
    """Shelf-packs every face's rect (rounded up to whole texels) onto the sheet, biggest first. Faces of the same
    material, kind (side, top, bottom) and size share a rect (each is painted the same)."""
    rects, shared = [], {}
    for i, p in enumerate(parts):
        for face in p["faces"]:
            w, h = face_size(p, face)
            cw, ch = max(1, int(-(-w // 1))), max(1, int(-(-h // 1)))
            kind = "top" if face == "up" else "bottom" if face == "down" else "side"
            key = (p["material"], kind, round(w, 4), round(h, 4))
            shared.setdefault(key, []).append((i, face))
            if len(shared[key]) == 1:
                rects.append((key, cw, ch, w, h))
    rects.sort(key=lambda r: (-r[2], -r[1], r[0]))
    x = y = shelf = 0
    placed = {}
    for key, cw, ch, w, h in rects:
        if x + cw > SHEET:
            x, y, shelf = 0, y + shelf, 0
        if y + ch > SHEET:
            raise SystemExit("sheet full")
        for owner in shared[key]:
            placed[owner] = (x, y, w, h, cw, ch, key)
        x += cw
        shelf = max(shelf, ch)
    return placed


# --- painting -----------------------------------------------------------------------------------------------------

def ramp(period, n):
    centre = period * 0.42
    return [max(0, min(n - 1, round((n - 1) * (1.0 - abs(k + 0.5 - centre) / max(1.0, period / 2.0) * 0.9)))) for k in range(period)]


def paint_side(px, x0, y0, cw, ch, material):
    """A round part's side: the ramp across it, a glint on a glaze, the bottom row a step darker on taller faces."""
    tones = RAMPS[material]
    r = ramp(cw, len(tones))
    for x in range(cw):
        for y in range(ch):
            t = r[x]
            if ch >= 3 and y == ch - 1:
                t = max(0, t - 1)
            c = tones[t]
            if material in GLINT and cw >= 5 and x == round(cw * 0.42) - 1 and y < ch - 1:
                c = GLINT[material]
            px[x0 + x, y0 + y] = c


def paint_top(px, x0, y0, cw, ch, material):
    """A round part's top: falling from the middle (lit) to the rim, lit a little more toward the top left."""
    tones = RAMPS[material]
    n = len(tones)
    for y in range(ch):
        for x in range(cw):
            dx, dy = (x + 0.5) / cw - 0.42, (y + 0.5) / ch - 0.42
            d = min(1.0, max(abs(dx), abs(dy)) * 2.0)
            px[x0 + x, y0 + y] = tones[max(0, min(n - 1, round((n - 1) * (1.0 - 0.75 * d))))]


def paint_bottom(px, x0, y0, cw, ch, material):
    tones = RAMPS[material]
    for y in range(ch):
        for x in range(cw):
            px[x0 + x, y0 + y] = tones[1 if (x == 0 or y == 0 or x == cw - 1 or y == ch - 1) else 0]


def sheet(placed):
    img = Image.new("RGBA", (SHEET, SHEET), (0, 0, 0, 0))
    px = img.load()
    done = set()
    for (x, y, w, h, cw, ch, key) in placed.values():
        if key in done:
            continue
        done.add(key)
        material, kind = key[0], key[1]
        {"side": paint_side, "top": paint_top, "bottom": paint_bottom}[kind](px, x, y, cw, ch, material)
    return img


def model_json(texture, parts, placed, display):
    out = {"credit": "tools/grid_block_model_gen.py", "parent": "block/block", "ambientocclusion": False,
           "textures": {"sheet": texture, "particle": texture}, "elements": []}
    for i, p in enumerate(parts):
        faces = {}
        for face in p["faces"]:
            x, y, w, h = placed[(i, face)][:4]
            # UVs in 16ths of the sheet (64 px -> /4); the rect's top left is the face's top left seen from outside.
            f = {"uv": [round(x / 4, 4), round(y / 4, 4), round((x + w) / 4, 4), round((y + h) / 4, 4)], "texture": "#sheet"}
            if face in p["cull"]:
                f["cullface"] = p["cull"][face]
            faces[face] = f
        out["elements"].append({"name": p["name"], "from": p["frm"], "to": p["to"], "faces": faces})
    out["display"] = display
    return out


DISPLAY = {"gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
           "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
           "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
           "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
           "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]}}


# --- creative cubes -----------------------------------------------------------------------------------------------

def creative_texture(kind, tier_index):
    """Arcforge's machine casing on the Creative ramp, a centred 8x8 FE port, and one pip per tier in the top left."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    r = CREATIVE
    for y in range(16):
        for x in range(16):
            c = r["face"] if x < 10 else r["face_shade"]
            if y == 0 or x == 0:
                c = r["lit"]
            elif y == 1 or x == 1:
                c = r["mid"]
            if y == 15 or x == 15:
                c = r["deep"]
            elif y == 14 or x == 14:
                c = r["dark"]
            if (x, y) in ((0, 15), (15, 0), (1, 14), (14, 1)):
                c = r["face_shade"]
            px[x, y] = c
    colours = PORT[kind]
    for y in range(8):
        for x in range(8):
            edge = min(x, y, 7 - x, 7 - y)
            if edge == 0:
                c = STEEL_LIT if (x == 0 or y == 0) else STEEL_DEEP
            elif edge == 1:
                c = colours[2] if (x == 6 or y == 6) else colours[0] if (x == 1 or y == 1) else colours[1]
            elif edge == 2:
                c = colours[1]
            else:
                c = STEEL_DEEP
            if 3 <= x <= 4 and 3 <= y <= 4:
                c = colours[0] if (x, y) == (3, 3) else colours[1]
            px[4 + x, 4 + y] = c
    for i in range(tier_index + 1):
        px[3 + 2 * i, 2] = r["lit"]
        px[3 + 2 * i, 3] = r["deep"]
    return img


# --- output -------------------------------------------------------------------------------------------------------

def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=False)


def attach(model, groups, units):
    return {"model": model, "units": units, "groups": groups, "reserved": []}


def main():
    repo = sys.argv[1] if len(sys.argv) > 1 else "."
    assets = os.path.join(repo, "src/main/resources/assets", NS)
    data = os.path.join(repo, "src/main/resources/data", NS, "attach_groups")
    for tier in TIERS:
        parts, point = inductor(tier)
        placed = pack(parts)
        save(sheet(placed), os.path.join(assets, "textures/block/inductor", tier + ".png"))
        write_json(os.path.join(assets, "models/block/inductor", tier + ".json"),
                   model_json(f"{NS}:block/inductor/{tier}", parts, placed, DISPLAY))
        write_json(os.path.join(data, f"inductor_{tier}.json"),
                   attach(f"inductor_{tier}", [{"name": "insulator", "circuit": "three_phase",
                                                 "terminals": [{"label": "A", "pos": point}]}],
                          "px in the block's up-pointing frame (mounted on the block below); origin the block's minimum corner"))
    terminals = pole_transformer_terminals()
    save(transformer_item(), os.path.join(assets, "textures/item/pole_transformer.png"))
    write_json(os.path.join(assets, "models/block/pole_transformer.json"),
               {"textures": {"particle": f"{NS}:block/transmission_tower/particle_steel"}})
    write_json(os.path.join(data, "pole_transformer.json"),
               attach("pole_transformer",
                      [{"name": "primary", "circuit": "three_phase",
                        "terminals": [{"label": l, "pos": terminals[l]} for l in ("A", "B", "C")]},
                       {"name": "secondary", "circuit": "single_phase",
                        "terminals": [{"label": l, "pos": terminals[l]} for l in ("L1", "N", "L2")]}],
                      "px in the block's north-facing frame (front north, hung from the block to the south); origin the "
                      "block's minimum corner"))
    for kind in ("source", "sink"):
        for i, tier in enumerate(TIERS):
            save(creative_texture("out" if kind == "source" else "in", i), os.path.join(assets, f"textures/block/creative_{kind}", tier + ".png"))
            write_json(os.path.join(assets, f"models/block/creative_{kind}", tier + ".json"),
                       {"parent": "minecraft:block/cube_all", "textures": {"all": f"{NS}:block/creative_{kind}/{tier}"}})
    print("wrote the inductors, the pole-mount transformer's data and the creative blocks")


if __name__ == "__main__":
    main()
