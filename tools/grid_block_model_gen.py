"""The grid's placeable blocks: the four Pin Insulators, the Pole-Mount Transformer and the Wall Bushing.

    python tools/grid_block_model_gen.py <addon repo>

Writes:
    assets/arcforgetransmission/models/block/pin_insulator/<tier>.json and textures/block/pin_insulator/<tier>.png
    assets/arcforgetransmission/models/block/pole_transformer.json (particles only) and textures/item/pole_transformer.png
    data/arcforgetransmission/attach_groups/{pin_insulator_<tier>,pole_transformer}.json
    assets/arcforgetransmission/models/block/wall_bushing{,_blank}.json and textures/block/wall_bushing.png
    assets/arcforgetransmission/models/block/surge_arrester.json and textures/block/surge_arrester.png

A Pin Insulator is one insulator, like the ones on the towers (the same glazed porcelain, glass and galvanizing ramps as
tools/transmission_tower_texture_gen.py): a wire is one conductor, and a Pin Insulator its one end, mounted on any face of the
block it serves (Immersive Engineering style). The model points up (mounted on the block below); the blockstates turn it
like an end rod. Per tier:
    LV  a porcelain pin insulator: two skirts and a crown with the tie groove, on a galvanized pin
    MV  a porcelain line post: a flange, a core with three sheds and a line clamp
    HV  three glass cap-and-pin discs on a clevis
    EHV  four larger glass discs
The Pole-Mount Transformer (LV) is drawn by its block entity from TowerMesh.poleTransformer() (the can the LV pole used to
carry, hung on the same brackets, with three primaries A B C on the lid and the split-phase secondary L1 N L2 on its
front); here are only its particles, item sprite and attach points.

Element models with every face its own rect on a 64x64 sheet at 1 texel per px (never stretched). Round parts carry a
ramp across each face (lightest just left of centre, a glint on the glazes); their tops fall from the middle to the rim.
Hidden faces (under a wider part, on top of a narrower one) aren't emitted, so nothing z-fights. Deterministic: no
randomness, so the build is byte-identical every run.
"""
import json
import math
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

TIERS = ("lv", "mv", "hv", "ehv")


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


def pin_insulator(tier):
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


def surge_arrester():
    """A Surge Arrester: a galvanized base, a porcelain column with four sheds, and the steel line terminal on top; it
    points up (mounted on the block below), turned like the pin insulators."""
    levels = [("foot", 1, 2, 1.5, "galv")]
    y = 2.0
    for i in range(4):
        levels += [(f"core_{i}", y, y + 1.5, 1.6, "porcelain"), (f"shed_{i}", y + 1.5, y + 2.5, 2.8, "porcelain")]
        y += 2.5
    levels += [("neck", y, y + 0.5, 1.6, "porcelain"), ("cap", y + 0.5, y + 1.5, 1.8, "galv"), ("stud", y + 1.5, y + 2.5, 0.6, "galv")]
    parts = [part("base", (5, 0, 5), (11, 1, 11), "galv", cull={"down": "down"})]
    return parts + stack(levels)


def pole_transformer_terminals(mv=False):
    """The transformer is drawn from TowerMesh.poleTransformer() (the can the LV pole used to carry); its attach points
    are read off the same constants: the primaries' tops on the lid, the secondaries' tips on the front. The MV
    Distribution Transformer (TowerMesh.poleTransformerMv()) is the same can with taller MV primaries (lid + 5.6)."""
    can_z, can_y1 = 15.4, 16.0
    primary_top = can_y1 - 0.8 + (5.6 if mv else 3.2)
    t = {}
    for label, x in (("A", 5.2), ("B", 8.0), ("C", 10.8)):
        t[label] = [x, round(primary_top, 4), can_z]
    for label, x, z in (("L1", 5.0, 7.9), ("N", 8.0, 8.9), ("L2", 11.0, 7.9)):
        t[label] = [x, 6.0, z]
    return t


def transformer_item(mv=False):
    """The item sprite: the can (vertical bands of its paint), its lid, three brown primaries and the secondaries (the MV
    Distribution Transformer: taller primaries, a shed more)."""
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
        if mv:
            px[x, 0] = GALV[6]
            px[x - 1, 1] = PORC[3]
            px[x + 1, 1] = PORC[2]
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


# Arcforge's machine steel ramp (docs/TEXTURE_STYLE.md, "Machine steel ramp").
STEEL = dict(deepest=hx("#16181B"), dark=hx("#2B2F34"), shade=hx("#383C42"), face_shade=hx("#40454C"), face=hx("#474C53"),
             mid=hx("#575D65"), lit=hx("#727982"), rivet=hx("#959DA6"))


def station_fence_texture():
    """Station Fence: galvanized chain-link, a diamond mesh one texel thick on a clear ground, with a top rail (the
    vanilla fence models crop their posts and rails out of it)."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(16):
        for x in range(16):
            if (x + y) % 4 == 0 or (x - y) % 4 == 0:
                px[x, y] = STEEL["rivet"] if (x + y) % 8 == 0 else STEEL["lit"]
    for x in range(16):
        px[x, 0] = STEEL["rivet"]
        px[x, 1] = STEEL["mid"]
    for y in range(16):
        px[7, y] = STEEL["rivet"] if y % 2 else STEEL["lit"]
        px[8, y] = STEEL["mid"]
    return img


# The Station Fence's rails (px, bottom and top), each vanilla's rail section (2 px wide, 3 tall) and textured the same:
# its sides show the chain-link at the rail's own height (texture rows 16 - top to 16 - bottom). Three of them, evenly
# spaced: 2 px under each (the ground too), the top rail where vanilla's is.
FENCE_RAILS = [(12, 15), (7, 10), (2, 5)]


def fence_rail(y0, y1, z0, z1, u0, ends):
    """One rail from z0 to z1 (px) along the side, its sides cropped from the chain-link at its height from texture column
    u0 (1 texel a px); `ends` the faces (north, south) it shows an end on. Where it sits on another rail or one sits on it
    the touching faces are left out (they'd show through the mesh, one on the other)."""
    length = z1 - z0
    faces = {"west": {"uv": [u0, 16 - y1, u0 + length, 16 - y0], "texture": "#texture"},
             "east": {"uv": [u0, 16 - y1, u0 + length, 16 - y0], "texture": "#texture"}}
    if not any(top == y0 for _, top in FENCE_RAILS):
        faces["down"] = {"uv": [7, u0, 9, u0 + length], "texture": "#texture"}
    if not any(bottom == y1 for bottom, _ in FENCE_RAILS):
        faces["up"] = {"uv": [7, u0, 9, u0 + length], "texture": "#texture"}
    for end in ends:
        faces[end] = {"uv": [7, 16 - y1, 9, 16 - y0], "texture": "#texture"}
        if end == "north" and z0 == 0:
            faces[end]["cullface"] = "north"
    return {"from": [7, y0, z0], "to": [9, y1, z1], "faces": faces}


def station_fence_side():
    """A side of the Station Fence (vanilla's fence_side with FENCE_RAILS): the rails from the north edge to the post."""
    return {"textures": {"particle": f"{NS}:block/station_fence", "texture": f"{NS}:block/station_fence"}, "render_type": "minecraft:cutout",
            "elements": [fence_rail(y0, y1, 0, 9, 0, ["north"]) for y0, y1 in FENCE_RAILS]}


def station_fence_inventory():
    """The Station Fence as an item: vanilla's fence_inventory (two posts, the rails through and past them) with FENCE_RAILS."""
    posts = []
    for z0, z1 in ((0, 4), (12, 16)):
        posts.append({"from": [6, 0, z0], "to": [10, 16, z1], "faces": {
            "down": {"uv": [6, z0, 10, z1], "texture": "#texture", "cullface": "down"}, "up": {"uv": [6, z0, 10, z1], "texture": "#texture"},
            "north": {"uv": [6, 0, 10, 16], "texture": "#texture"}, "south": {"uv": [6, 0, 10, 16], "texture": "#texture"},
            "west": {"uv": [z0, 0, z1, 16], "texture": "#texture"}, "east": {"uv": [z0, 0, z1, 16], "texture": "#texture"}}})
    rails = []
    for y0, y1 in FENCE_RAILS:
        # Through the posts, and a stub out past each (vanilla's Topbarleft / Topbarright).
        rails += [fence_rail(y0, y1, 0, 16, 0, []), fence_rail(y0, y1, -2, 0, 14, ["north"]), fence_rail(y0, y1, 16, 18, 0, ["south"])]
    return {"parent": "minecraft:block/block", "display": load_vanilla_display(), "render_type": "minecraft:cutout",
            "textures": {"particle": f"{NS}:block/station_fence", "texture": f"{NS}:block/station_fence"}, "elements": posts + rails}


def load_vanilla_display():
    """vanilla fence_inventory's display transforms."""
    return {"gui": {"rotation": [30, 135, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
            "fixed": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
            "on_shelf": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}


# Station Fence Barbed Wire: a slim galvanized outrigger on the fence's post, leaning 45 degrees outward (away from the
# yard), carrying three strands of barbed wire. Drawn facing north (its outward side north, -z); the blockstate turns it.
# Each strand is 1 px square at BARBED_STRANDS px up the outrigger and as far out (z = 8 - h), with a 1 px barb every 3 px
# (BARBED_BARBS, along the block), out of one side and the other in turn. Along its run (east and west of a north-facing block) a
# strand runs straight to the block's edge; where the fence turns, it wraps the corner: round the yard side (a corner
# fence whose neighbour lies inward) with a 45 degree chamfer, round the outer side with a square jog.
BARBED_STRANDS = [2.0, 4.0, 6.0]
BARBED_BARBS = [2, 5, 11, 14]
BARBED_ARM = 9.0
# Pieces that may meet another (a run and a corner of the same strand at a T in the fence) are lifted this much, so no
# two faces ever lie in one plane.
BARBED_LIFT = 0.02


def barbed_wire_texture():
    """The barbed wire's sheet: row 0 a strand's lit faces (two plies twisting, a highlight every 4 px), row 1 its
    underside; rows 3 and 4 the barbs; columns 0 to 2 below row 6 the outrigger arm (lit, shade, dark faces); the collar
    on the post a 3 x 3 cap at (10, 10)."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    twist = [STEEL["rivet"], STEEL["lit"], STEEL["mid"], STEEL["lit"]]
    under = [STEEL["lit"], STEEL["mid"], STEEL["dark"], STEEL["mid"]]
    for x in range(16):
        px[x, 0] = twist[x % 4]
        px[x, 1] = under[x % 4]
        px[x, 3] = STEEL["lit"]
        px[x, 4] = STEEL["mid"]
    for y in range(6, 16):
        px[0, y] = STEEL["lit"]
        px[1, y] = STEEL["mid"]
        px[2, y] = STEEL["dark"]
    for y in range(10, 13):
        for x in range(10, 13):
            px[x, y] = STEEL["lit"] if x == 10 or y == 10 else STEEL["mid"] if x < 12 and y < 12 else STEEL["dark"]
    return img


def bw_tex():
    return f"{NS}:block/station_fence_barbed_wire"


def bw_box(x0, y0, z0, x1, y1, z1, faces, rotation=None):
    e = {"from": [x0, y0, z0], "to": [x1, y1, z1], "faces": faces}
    if rotation:
        e["rotation"] = rotation
    return e


def strand_faces(u0, u1, ends=()):
    """A strand's faces: its sides and top from row 0 (u0 to u1 along it, so the twist carries on), its underside from
    row 1, and the ends named."""
    lit, low = {"uv": [u0, 0, u1, 1], "texture": "#wire"}, {"uv": [u0, 1, u1, 2], "texture": "#wire"}
    return lit, low, {e: {"uv": [0, 0, 1, 1], "texture": "#wire"} for e in ends}


def strand_x(x0, x1, h, z, ends=(), lift=0.0):
    lit, low, end_faces = strand_faces(x0, x1, ends)
    faces = {"north": dict(lit), "south": dict(lit), "up": dict(lit), "down": dict(low)}
    faces.update(end_faces)
    return bw_box(x0, h - 0.5 + lift, z - 0.5, x1, h + 0.5 + lift, z + 0.5, faces)


def strand_z(z0, z1, h, x, ends=(), lift=0.0):
    lit, low, end_faces = strand_faces(z0, z1, ends)
    faces = {"west": dict(lit), "east": dict(lit), "up": {"uv": [z0, 0, z1, 1], "texture": "#wire", "rotation": 90},
             "down": {"uv": [z0, 1, z1, 2], "texture": "#wire", "rotation": 90}}
    faces.update(end_faces)
    return bw_box(x - 0.5, h - 0.5 + lift, z0, x + 0.5, h + 0.5 + lift, z1, faces)


def barbs_along(axis, lo, hi, h, at, strand, lift=0.0):
    """The barbs on a straight piece of strand between lo and hi (px along its axis): at BARBED_BARBS, a 1 px cube out of
    one side of it or the other in turn (level, across the strand; the next strand starts the other way), so seen from
    the front the strands stay thin lines and the barbs show as nubs from the side."""
    out = []
    for i, p in enumerate(BARBED_BARBS):
        if p - 0.5 < lo + 0.5 or p + 0.5 > hi - 0.5:
            continue
        side = 1 if (i + strand) % 2 == 0 else -1
        c0, c1 = (at + 0.5, at + 1.5) if side > 0 else (at - 1.5, at - 0.5)
        faces = {"up": {"uv": [0, 3, 1, 4], "texture": "#wire"}, "down": {"uv": [0, 4, 1, 5], "texture": "#wire"}}
        if axis == "x":
            faces.update({"west": {"uv": [0, 3, 1, 4], "texture": "#wire"}, "east": {"uv": [0, 4, 1, 5], "texture": "#wire"},
                          "south" if side > 0 else "north": {"uv": [0, 3, 1, 4], "texture": "#wire"}})
            out.append(bw_box(p - 0.5, h - 0.5 + lift, c0, p + 0.5, h + 0.5 + lift, c1, faces))
        else:
            faces.update({"north": {"uv": [0, 3, 1, 4], "texture": "#wire"}, "south": {"uv": [0, 4, 1, 5], "texture": "#wire"},
                          "east" if side > 0 else "west": {"uv": [0, 3, 1, 4], "texture": "#wire"}})
            out.append(bw_box(c0, h - 0.5 + lift, p - 0.5, c1, h + 0.5 + lift, p + 0.5, faces))
    return out


def barbed_post():
    """On the post: a collar on the fence post's top, the outrigger arm leaning 45 degrees out (north) from it, and where
    each strand crosses the arm a 1 px hub every run and corner starts from."""
    collar = {f: {"uv": [10, 10, 13, 13], "texture": "#wire"} for f in ("north", "south", "west", "east", "up")}
    arm = {"north": {"uv": [0, 6, 1, 15], "texture": "#wire"}, "south": {"uv": [2, 6, 3, 15], "texture": "#wire"},
           "west": {"uv": [1, 6, 2, 15], "texture": "#wire"}, "east": {"uv": [1, 6, 2, 15], "texture": "#wire"},
           "up": {"uv": [0, 6, 1, 7], "texture": "#wire"}}
    elements = [bw_box(6.5, 0, 6.5, 9.5, 1, 9.5, collar),
                bw_box(7.5, 0.5, 7.5, 8.5, 0.5 + BARBED_ARM, 8.5, arm, {"angle": -45, "axis": "x", "origin": [8, 0.5, 8]})]
    for h in BARBED_STRANDS:
        z = 8 - h
        elements.append(bw_box(7.5, h - 0.5, z - 0.5, 8.5, h + 0.5, z + 0.5, {f: {"uv": [0, 0, 1, 1], "texture": "#wire"} for f in
                                                                             ("north", "south", "west", "east", "up", "down")}))
    return elements


def barbed_side(kind):
    """The strands out of the hubs one way, facing north: "left" / "right" along the run (west / east), "in_run" /
    "out_run" straight on (south / north), "in_left" / "in_right" round a corner on the yard side (a chamfer, then south
    at the neighbour's own offset), "out_left" / "out_right" round one on the outer side (a square jog, then north)."""
    elements = []
    for s, h in enumerate(BARBED_STRANDS):
        z = 8 - h
        if kind == "right":
            elements += [strand_x(8.5, 16, h, z)] + barbs_along("x", 8.5, 16, h, z, s)
        elif kind == "left":
            elements += [strand_x(0, 7.5, h, z)] + barbs_along("x", 0, 7.5, h, z, s)
        elif kind == "in_run":
            elements += [strand_z(z + 0.5, 16, h, 8)] + barbs_along("z", z + 0.5, 16, h, 8, s)
        elif kind == "out_run":
            elements += [strand_z(0, z - 0.5, h, 8)] + barbs_along("z", 0, z - 0.5, h, 8, s)
        elif kind in ("in_left", "in_right"):
            sign = 1 if kind == "in_right" else -1
            # From the hub's corner, 45 degrees out to the neighbour's offset, then south to the edge.
            length = (h - 1) * math.sqrt(2)
            mx, mz = 8 + sign * (0.5 + (h - 1) / 2), 8.5 - h + (h - 1) / 2
            lit, low, _ = strand_faces(0, length)
            elements.append(bw_box(mx - length / 2, h - 0.5 + BARBED_LIFT, mz - 0.5, mx + length / 2, h + 0.5 + BARBED_LIFT, mz + 0.5,
                                   {"north": dict(lit), "south": dict(lit), "up": dict(lit), "down": dict(low)},
                                   {"angle": -45 * sign, "axis": "y", "origin": [mx, h, mz]}))
            x = 8 + sign * h
            elements += [strand_z(7.5, 16, h, x, ("north",), BARBED_LIFT)] + barbs_along("z", 7.5, 16, h, x, s, BARBED_LIFT)
        elif kind in ("out_left", "out_right"):
            sign = 1 if kind == "out_right" else -1
            # Along the run to the neighbour's offset, a corner, then north to the edge.
            x = 8 + sign * h
            near, far = (8.5, x - 0.5) if sign > 0 else (x + 0.5, 7.5)
            elements.append(strand_x(near, far, h, z, (), BARBED_LIFT))
            corner = {f: {"uv": [0, 0, 1, 1], "texture": "#wire"} for f in ("south", "west", "east", "up", "down", "north")}
            elements.append(bw_box(x - 0.5, h - 0.5 + BARBED_LIFT, z - 0.5, x + 0.5, h + 0.5 + BARBED_LIFT, z + 0.5, corner))
            elements += [strand_z(0, z - 0.5, h, x, (), BARBED_LIFT)] + barbs_along("z", 0, z - 0.5, h, x, s, BARBED_LIFT)
    return elements


def barbed_lone():
    """A block with nothing beside it: a short end of each strand either side of its hub."""
    elements = []
    for h in BARBED_STRANDS:
        z = 8 - h
        elements += [strand_x(6, 7.5, h, z, ("west",)), strand_x(8.5, 10, h, z, ("east",))]
    return elements


BARBED_SIDES = ["left", "right", "in_run", "out_run", "in_left", "in_right", "out_left", "out_right"]
HORIZONTAL = ["north", "east", "south", "west"]


def barbed_model(elements):
    return {"textures": {"particle": bw_tex(), "wire": bw_tex()}, "render_type": "minecraft:cutout", "elements": elements}


def barbed_blockstate():
    """Multipart: the post turned to its facing; each side's strands by what lies that way (none, run, left, right; see
    StationFenceBarbedWireBlock), as the side it is for the facing (out, right, in, left), turned with it."""
    parts = []
    for f, facing in enumerate(HORIZONTAL):
        y = f * 90
        parts.append({"when": {"facing": facing}, "apply": {"model": f"{NS}:block/station_fence_barbed_wire_post", "y": y}})
        parts.append({"when": {"facing": facing, **{d: "none" for d in HORIZONTAL}},
                      "apply": {"model": f"{NS}:block/station_fence_barbed_wire_lone", "y": y}})
        for r, rel in enumerate(["out", "right", "in", "left"]):
            side = HORIZONTAL[(f + r) % 4]
            if rel in ("left", "right"):
                parts.append({"when": {"facing": facing, side: "run"}, "apply": {"model": f"{NS}:block/station_fence_barbed_wire_{rel}", "y": y}})
            else:
                for value in ("run", "left", "right"):
                    parts.append({"when": {"facing": facing, side: value},
                                  "apply": {"model": f"{NS}:block/station_fence_barbed_wire_{rel}_{value}", "y": y}})
    return {"multipart": parts}


def barbed_inventory():
    """As an item: the post and a strand out either side."""
    model = barbed_model(barbed_post() + barbed_side("left") + barbed_side("right"))
    model["parent"] = "minecraft:block/block"
    model["display"] = {"gui": {"rotation": [30, 225, 0], "translation": [0, 3, 0], "scale": [0.8, 0.8, 0.8]},
                        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.5, 0.5, 0.5]},
                        "fixed": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.8, 0.8, 0.8]},
                        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
                        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]}}
    return model


def write_barbed_wire(assets):
    save(barbed_wire_texture(), os.path.join(assets, "textures/block/station_fence_barbed_wire.png"))
    write_json(os.path.join(assets, "models/block/station_fence_barbed_wire_post.json"), barbed_model(barbed_post()))
    write_json(os.path.join(assets, "models/block/station_fence_barbed_wire_lone.json"), barbed_model(barbed_lone()))
    for kind in BARBED_SIDES:
        write_json(os.path.join(assets, f"models/block/station_fence_barbed_wire_{kind}.json"), barbed_model(barbed_side(kind)))
    write_json(os.path.join(assets, "models/block/station_fence_barbed_wire_inventory.json"), barbed_inventory())
    write_json(os.path.join(assets, "blockstates/station_fence_barbed_wire.json"), barbed_blockstate())
    write_json(os.path.join(assets, "items/station_fence_barbed_wire.json"),
               {"model": {"type": "minecraft:model", "model": f"{NS}:block/station_fence_barbed_wire_inventory"}})


def ground_grid_texture():
    """Ground Grid: bare copper conductors in a square mesh with clamped crossings, on the ground's dark between."""
    copper = [hx(c) for c in ("#5C2E17", "#8A4A26", "#B5663A", "#D9895A")]
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for i in range(16):
        for k in (3, 11):
            px[i, k] = copper[2] if i % 3 else copper[3]
            px[k, i] = copper[1] if i % 3 else copper[2]
    for x, y in ((3, 3), (11, 3), (3, 11), (11, 11)):
        px[x, y] = STEEL["rivet"]
    return img


def wall_bushing_texture():
    """The Wall Bushing's faces: a single-block machine casing (split face, 1 px bevels, four rivets) with a round porcelain
    bushing set in a recess in the middle, its bore dark: where a wire passes through the wall."""
    img = Image.new("RGBA", (16, 16), STEEL["face"])
    px = img.load()
    for y in range(16):
        for x in range(16):
            c = STEEL["face"] if x < 10 else STEEL["face_shade"]
            if x == 0 or y == 0:
                c = STEEL["lit"]
            elif x == 1 or y == 1:
                c = STEEL["mid"]
            if x == 15 or y == 15:
                c = STEEL["deepest"]
            elif x == 14 or y == 14:
                c = STEEL["dark"] if x > 0 and y > 0 else c
            px[x, y] = c
    for x, y in ((0, 15), (15, 0), (1, 14), (14, 1)):
        px[x, y] = STEEL["shade"]
    for x, y, sx, sy in ((2, 2, 1, 1), (13, 2, -1, 1), (2, 13, 1, -1), (13, 13, -1, -1)):
        px[x, y] = STEEL["rivet"]
        px[x + sx, y + sy] = STEEL["dark"]
    # The recess round the bushing (4..11): lips inverted, then the porcelain in rings, lit top-left, with its bore.
    for i in range(4, 12):
        px[i, 4] = STEEL["deepest"]
        px[4, i] = STEEL["deepest"]
        px[i, 11] = STEEL["lit"]
        px[11, i] = STEEL["lit"]
    px[11, 4] = STEEL["shade"]
    px[4, 11] = STEEL["shade"]
    for y in range(5, 11):
        for x in range(5, 11):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            lit = (x - 7.5) + (y - 7.5) < 0
            if d > 3.2:
                c = STEEL["dark"]
            elif d > 2.2:
                c = PORC[4] if lit else PORC[2]
            elif d > 1.2:
                c = PORC[5] if lit else PORC[3]
            else:
                c = STEEL["deepest"]
            px[x, y] = c
    return img


def main():
    repo = sys.argv[1] if len(sys.argv) > 1 else "."
    assets = os.path.join(repo, "src/main/resources/assets", NS)
    data = os.path.join(repo, "src/main/resources/data", NS, "attach_groups")
    for tier in TIERS:
        parts, point = pin_insulator(tier)
        placed = pack(parts)
        save(sheet(placed), os.path.join(assets, "textures/block/pin_insulator", tier + ".png"))
        write_json(os.path.join(assets, "models/block/pin_insulator", tier + ".json"),
                   model_json(f"{NS}:block/pin_insulator/{tier}", parts, placed, DISPLAY))
        write_json(os.path.join(data, f"pin_insulator_{tier}.json"),
                   attach(f"pin_insulator_{tier}", [{"name": "insulator", "circuit": "three_phase",
                                                 "terminals": [{"label": "A", "pos": point}]}],
                          "px in the block's up-pointing frame (mounted on the block below); origin the block's minimum corner"))
    save(transformer_item(), os.path.join(assets, "textures/item/pole_transformer.png"))
    save(transformer_item(mv=True), os.path.join(assets, "textures/item/mv_distribution_transformer.png"))
    for name, mv in (("pole_transformer", False), ("mv_distribution_transformer", True)):
        terminals = pole_transformer_terminals(mv)
        write_json(os.path.join(assets, f"models/block/{name}.json"),
                   {"textures": {"particle": f"{NS}:block/transmission_tower/particle_steel"}})
        write_json(os.path.join(data, f"{name}.json"),
                   attach(name,
                          [{"name": "primary", "circuit": "three_phase",
                            "terminals": [{"label": l, "pos": terminals[l]} for l in ("A", "B", "C")]},
                           {"name": "secondary", "circuit": "single_phase",
                            "terminals": [{"label": l, "pos": terminals[l]} for l in ("L1", "N", "L2")]}],
                          "px in the block's north-facing frame (front north, hung from the block to the south); origin the "
                          "block's minimum corner"))
    parts = surge_arrester()
    placed = pack(parts)
    save(sheet(placed), os.path.join(assets, "textures/block/surge_arrester.png"))
    write_json(os.path.join(assets, "models/block/surge_arrester.json"), model_json(f"{NS}:block/surge_arrester", parts, placed, DISPLAY))
    save(wall_bushing_texture(), os.path.join(assets, "textures/block/wall_bushing.png"))
    save(station_fence_texture(), os.path.join(assets, "textures/block/station_fence.png"))
    save(ground_grid_texture(), os.path.join(assets, "textures/block/ground_grid.png"))
    write_barbed_wire(assets)
    # The fence's post on vanilla's fence model, its sides and item with FENCE_RAILS; the grid on the carpet's (its texture
    # key "wool").
    write_json(os.path.join(assets, "models/block/station_fence_post.json"),
               {"parent": "minecraft:block/fence_post", "textures": {"texture": f"{NS}:block/station_fence"}, "render_type": "minecraft:cutout"})
    write_json(os.path.join(assets, "models/block/station_fence_side.json"), station_fence_side())
    write_json(os.path.join(assets, "models/block/station_fence_inventory.json"), station_fence_inventory())
    write_json(os.path.join(assets, "models/block/ground_grid.json"),
               {"parent": "minecraft:block/carpet", "textures": {"wool": f"{NS}:block/ground_grid"}, "render_type": "minecraft:cutout"})
    write_json(os.path.join(assets, "models/block/wall_bushing.json"), {"parent": "minecraft:block/cube_all", "textures": {"all": f"{NS}:block/wall_bushing"}})
    # A Wall Bushing under a blank Conduit Facade (WallBushingModel): Arcforge's blank facade texture on a full block.
    write_json(os.path.join(assets, "models/block/wall_bushing_blank.json"),
               {"parent": "minecraft:block/cube_all", "textures": {"all": "arcforge:block/conduit/facade_blank"}})
    print("wrote the pin insulators, the pole-mount transformer's data, the surge arrester and the wall bushing")


if __name__ == "__main__":
    main()
