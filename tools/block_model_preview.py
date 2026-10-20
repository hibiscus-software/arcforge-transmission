"""Renders JSON block models (multipart blockstates of plain element boxes) to PNGs for review, as ModelPreview does for
the towers: orthographic, textured from the real textures (nearest texel, cutout), lit like the game's block faces, on a
ground of block squares.

    python tools/block_model_preview.py <addon repo> <out dir> <scene>[,<scene>...]

A scene is a few placed blocks of one block (its blockstate's multipart picks each block's models from its properties):
  fence_run        a straight run of four Station Fences with a corner at its end
  barbed_run       the same fence run with Station Fence Barbed Wire on top of it
  barbed_close     a close look: two fences and a corner, with their barbed wire
  busway_<tier>    a busway run for lv / mv / hv / ehv: feed-in, straights (one with its feature), supports, elbow, tee,
                   riser with vertical elbows, end caps, tap-offs and a reducer
  busway_sizes_<tier>, busway_close_<tier>    the three sizes side by side; two blocks and a tap-off close up
  tray_standard, tray_wide, tray_heavy (and _close)    cable tray runs with their mounts and cables laid in them
  factory          every busway and tray scene above
Each scene is drawn from the front, the side and 45 degrees. Not a generator: it writes nothing under src.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

NS = "arcforgetransmission"
VIEW, SUPER = 520, 2


def load_json(root, ref, kind):
    ns, path = ref.split(":") if ":" in ref else ("minecraft", ref)
    if ns == "minecraft":
        return load_vanilla(path, kind)
    with open(os.path.join(root, "src/main/resources/assets", ns, kind, path + ".json"), encoding="utf-8") as f:
        return json.load(f)


VANILLA = {}


def load_vanilla(path, kind):
    """Vanilla models from the decompiled game jar Gradle leaves in build/neoForm."""
    import zipfile
    key = (kind, path)
    if key not in VANILLA:
        jar = os.path.join(ROOT, "build/neoForm/neoFormJoined26.3-1/raw.jar")
        with zipfile.ZipFile(jar) as z:
            VANILLA[key] = json.loads(z.read(f"assets/minecraft/{kind}/{path}.json"))
    return VANILLA[key]


def resolve_model(root, ref):
    """A model's elements and textures, its parents' merged in."""
    model = load_json(root, ref, "models")
    textures, elements = {}, None
    chain = [model]
    while "parent" in chain[-1]:
        chain.append(load_json(root, chain[-1]["parent"].replace("block/", "block/", 1), "models"))
    for m in reversed(chain):
        textures.update(m.get("textures", {}))
        if "elements" in m:
            elements = m["elements"]
    return elements or [], textures


TEXTURES = {}
ARRAYS = {}


def texture(root, textures, key):
    ref = key
    while ref.startswith("#"):
        ref = textures[ref[1:]]
    ns, path = ref.split(":") if ":" in ref else ("minecraft", ref)
    full = os.path.join(root, "src/main/resources/assets", ns, "textures", path + ".png")
    if full not in TEXTURES:
        TEXTURES[full] = Image.open(full).convert("RGBA")
    return TEXTURES[full]


FACE_CORNERS = {
    # Each face's corners (as box axes: 0 min, 1 max), in the order its UV maps: top-left, top-right, bottom-right,
    # bottom-left, seen from outside.
    "north": [(1, 1, 0), (0, 1, 0), (0, 0, 0), (1, 0, 0)],
    "south": [(0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1)],
    "west": [(0, 1, 0), (0, 1, 1), (0, 0, 1), (0, 0, 0)],
    "east": [(1, 1, 1), (1, 1, 0), (1, 0, 0), (1, 0, 1)],
    "up": [(0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)],
    "down": [(0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0)],
}
NORMALS = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}


def element_quads(root, elements, textures, rot_y, offset):
    """Each face of each element as a quad: four corners (blocks, world), four UVs (0-1), its texture and normal."""
    quads = []
    for e in elements:
        f, t = e["from"], e["to"]
        rot = e.get("rotation")
        for name, face in e["faces"].items():
            corners = []
            for cx, cy, cz in FACE_CORNERS[name]:
                p = [f[0] if cx == 0 else t[0], f[1] if cy == 0 else t[1], f[2] if cz == 0 else t[2]]
                if rot:
                    p = rotate_element(p, rot)
                corners.append(p)
            u0, v0, u1, v1 = face.get("uv", default_uv(name, f, t))
            uvs = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
            for _ in range(face.get("rotation", 0) // 90):
                uvs = uvs[1:] + uvs[:1]
            normal = NORMALS[name]
            if rot:
                normal = rotate_element([8 + normal[0], 8 + normal[1], 8 + normal[2]], rot)
                normal = [normal[0] - rotate_element([8, 8, 8], rot)[0], normal[1] - rotate_element([8, 8, 8], rot)[1],
                          normal[2] - rotate_element([8, 8, 8], rot)[2]]
            world = [turn_y(p, rot_y) for p in corners]
            n = turn_y([8 + normal[0], 8 + normal[1], 8 + normal[2]], rot_y)
            n = [n[0] - 8, n[1] - 8, n[2] - 8]
            quads.append(([[offset[0] + p[0] / 16, offset[1] + p[1] / 16, offset[2] + p[2] / 16] for p in world],
                          [(u / 16, v / 16) for u, v in uvs], texture(root, textures, face["texture"]), n))
    return quads


def default_uv(name, f, t):
    if name in ("north", "south"):
        return [f[0], 16 - t[1], t[0], 16 - f[1]]
    if name in ("west", "east"):
        return [f[2], 16 - t[1], t[2], 16 - f[1]]
    return [f[0], f[2], t[0], t[2]]


def rotate_element(p, rot):
    axis, angle, origin = rot["axis"], math.radians(rot["angle"]), rot["origin"]
    x, y, z = p[0] - origin[0], p[1] - origin[1], p[2] - origin[2]
    c, s = math.cos(angle), math.sin(angle)
    if axis == "x":
        y, z = y * c - z * s, y * s + z * c
    elif axis == "y":
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return [x + origin[0], y + origin[1], z + origin[2]]


def turn_y(p, deg):
    """A blockstate's y rotation: clockwise seen from above, about the block's centre."""
    a = math.radians(deg)
    x, z = p[0] - 8, p[2] - 8
    c, s = math.cos(a), math.sin(a)
    return [8 + x * c - z * s, p[1], 8 + x * s + z * c]


def matches(when, props):
    if "OR" in when:
        return any(matches(w, props) for w in when["OR"])
    if "AND" in when:
        return all(matches(w, props) for w in when["AND"])
    # A busway run shows undamaged unless the scene says otherwise.
    return all(props.get(k, "false" if k == "damaged" else None) in str(v).split("|") for k, v in when.items())


def block_quads(root, block, props, pos):
    state = load_json(root, f"{NS}:{block}", "blockstates")
    quads = []
    for part in state["multipart"]:
        if "when" in part and not matches(part["when"], props):
            continue
        applies = part["apply"] if isinstance(part["apply"], list) else [part["apply"]]
        a = applies[0]
        elements, textures = resolve_model(root, a["model"])
        quads += element_quads(root, elements, textures, a.get("y", 0), pos)
    return quads


def fence_props(here, all_pos, extra=None):
    x, y, z = here
    props = {d: str((x + dx, y, z + dz) in all_pos).lower() for d, (dx, dz) in
             {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}.items()}
    props.update(extra or {})
    return props


def scene(root, name):
    if name in FACTORY_SCENES:
        return factory_scene(root, name)
    run = [(0, 0, 0), (1, 0, 0), (2, 0, 0), (3, 0, 0), (3, 0, 1), (3, 0, 2)]
    if name == "barbed_close":
        # A close look: two fences in a run with their barbed wire, and a corner.
        run, name = [(0, 0, 0), (1, 0, 0), (1, 0, 1)], "barbed_run"
    quads = []
    for p in run:
        quads += block_quads(root, "station_fence", fence_props(p, set(run)), p)
    if name == "barbed_run":
        # The yard lies south-west of the fence: outward is north along the straight run and east down its leg (the corner
        # block faces north, so its leg turns round the yard side).
        corner = max(x for x, _, _ in run)
        tops = {(x, 1, z): ("east" if x == corner and z > 0 else "north") for x, _, z in run}
        for p, facing in tops.items():
            quads += block_quads(root, "station_fence_barbed_wire", barbed_props(p, tops), p)
    return quads


DIRS = {"north": (0, -1), "east": (1, 0), "south": (0, 1), "west": (-1, 0)}
ORDER = ["north", "east", "south", "west"]


def barbed_props(here, facings):
    """StationFenceBarbedWireBlock's sides: none; run (along its run, or a neighbour facing its way or the opposite); left
    or right (on the in or out side, the neighbour facing that way of this one)."""
    facing = facings[here]
    props = {"facing": facing}
    for d, (dx, dz) in DIRS.items():
        other = facings.get((here[0] + dx, here[1], here[2] + dz))
        if other is None:
            props[d] = "none"
        elif (ORDER.index(d) - ORDER.index(facing)) % 2 == 1 or (ORDER.index(other) - ORDER.index(facing)) % 2 == 0:
            props[d] = "run"
        else:
            props[d] = "right" if (ORDER.index(other) - ORDER.index(facing)) % 4 == 1 else "left"
    return props


# --- factory scenes (tools/factory_block_model_gen.py's busways and cable trays) ---------------------------------------
# Each scene places blocks and sets their blockstate properties the way the Java connection logic does (busways join
# their own tier and size, a feed-in facing them and a reducer's matching end; a vertical run's vaxis is across the
# horizontal arm it bends to; trays join any tray of the same kind), adds a few flat-coloured context blocks (a
# switchgear section, stone over ceiling-hung runs, concrete plinths under floor stands, walls behind wall brackets) and,
# in the trays, the cables drawn as 8-sided tubes from the cable jacket textures with the geometry the Java renderer uses.

FACTORY_TIERS = ("lv", "mv", "hv", "ehv")
FACTORY_SCENE_LIST = ([f"busway_{t}" for t in FACTORY_TIERS] + [f"busway_sizes_{t}" for t in FACTORY_TIERS]
                      + [f"busway_close_{t}" for t in FACTORY_TIERS]
                      + ["tray_standard", "tray_wide", "tray_heavy", "tray_standard_close", "tray_wide_close", "tray_heavy_close"])
FACTORY_SCENES = set(FACTORY_SCENE_LIST)
FACTORY_VIEW = 720
CONTEXT = {"stone": (0x7E, 0x7E, 0x7C), "switchgear": (0x9A, 0x9F, 0x9C), "plinth": (0xA8, 0xA6, 0x9C), "wall": (0x8A, 0x86, 0x80)}
FDIRS = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}
FOPP = {"north": "south", "south": "north", "east": "west", "west": "east", "up": "down", "down": "up"}


def fgen():
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import factory_block_model_gen
    return factory_block_model_gen


def cube_quads(pos, colour, box=((0, 0, 0), (1, 1, 1))):
    """A plain flat-coloured block (or part of one, box in blocks) for context."""
    quads = []
    x, y, z = pos
    (x0, y0, z0), (x1, y1, z1) = box
    for name, corners in FACE_CORNERS.items():
        pts = [[x + (x1 if cx else x0), y + (y1 if cy else y0), z + (z1 if cz else z0)] for cx, cy, cz in corners]
        quads.append((pts, [(0, 0)] * 4, None, list(NORMALS[name]), colour))
    return quads


def context_quads(pos, kind, items):
    """Context drawn thin where a whole block would hide the work: a ceiling as the bottom 2 px of the stone above, a
    wall as the 2 px of it facing the block it carries."""
    if kind == "stone":
        return cube_quads(pos, CONTEXT[kind], ((0, 0, 0), (1, 0.125, 1)))
    if kind == "wall":
        for d, (dx, dy, dz) in FDIRS.items():
            if (pos[0] - dx, pos[1] - dy, pos[2] - dz) in items and dy == 0:
                lo = [0.0, 0.0, 0.0]
                hi = [1.0, 1.0, 1.0]
                axis = 0 if dx else 2
                if (dx or dz) < 0:
                    lo[axis] = 0.875
                else:
                    hi[axis] = 0.125
                return cube_quads(pos, CONTEXT[kind], (tuple(lo), tuple(hi)))
    return cube_quads(pos, CONTEXT[kind])


def busway_props(items, pos):
    """A busway block's connections, vaxis, feature and support as the Java sets them."""
    it = items[pos]
    props = {}
    for d, (dx, dy, dz) in FDIRS.items():
        other = items.get((pos[0] + dx, pos[1] + dy, pos[2] + dz))
        ok = False
        if other and other["tier"] == it["tier"]:
            if other["kind"] == "busway":
                ok = other["size"] == it["size"]
            elif other["kind"] == "feed_in":
                ok = other["facing"] == FOPP[d]
            elif other["kind"] == "reducer":
                big, small = other["sizes"]
                ok = (other["facing"] == FOPP[d] and it["size"] == small) or (other["facing"] == d and it["size"] == big)
        props[d] = str(ok).lower()
    props["feature"] = str(it.get("feature", False)).lower()
    props["support"] = it.get("support", "none")
    props["vaxis"] = it.get("vaxis", "z")
    return props


def busway_vaxis(items, props):
    """A vertical run's vaxis: across the horizontal arm it bends to, carried up and down the run."""
    for _ in range(4):
        for pos, it in items.items():
            if it["kind"] != "busway":
                continue
            p = props[pos]
            horiz = [d for d in ("north", "south", "east", "west") if p[d] == "true"]
            vert = [d for d in ("up", "down") if p[d] == "true"]
            if vert and horiz:
                p["vaxis"] = "z" if horiz[0] in ("east", "west") else "x"
            elif vert:
                for d in vert:
                    dx, dy, dz = FDIRS[d]
                    q = props.get((pos[0] + dx, pos[1] + dy, pos[2] + dz))
                    if q:
                        p["vaxis"] = q["vaxis"]


def busway_block_id(it):
    t = it["tier"]
    if it["kind"] == "busway":
        return f"{t}_busway_{it['size']}"
    if it["kind"] == "reducer":
        return f"{t}_busway_reducer_{it['sizes'][0]}_{it['sizes'][1]}"
    return f"{t}_busway_{it['kind']}"


def busway_layout(tier, name):
    B = lambda **kw: dict(tier=tier, kind="busway", size="4w", **kw)
    items, context = {}, {}
    if name.startswith("busway_sizes"):
        for k, size in enumerate(("2w", "4w", "8w")):
            for x in range(3):
                items[(x, 0, 3 * k)] = dict(tier=tier, kind="busway", size=size, feature=(x == 1))
        return items, context
    if name.startswith("busway_close"):
        items[(0, 0, 0)] = B(support="floor" if tier in ("hv", "ehv") else "none")
        items[(1, 0, 0)] = B(feature=True)
        items[(1, 0, 1)] = dict(tier=tier, kind="tap_off", facing="north", closed=True, tripped=False)
        return items, context
    gis = tier in ("hv", "ehv")
    context[(0, 0, 0)] = "switchgear"
    items[(0, 1, 0)] = dict(tier=tier, kind="feed_in", facing="east")
    for x in range(1, 5):
        sup = "ceiling" if x <= 2 else "floor"
        items[(x, 1, 0)] = B(support=sup, feature=(x == 2))
        if sup == "ceiling":
            context[(x, 2, 0)] = "stone"
        else:
            context[(x, 0, 0)] = "plinth"
    items[(5, 1, 0)] = B()
    items[(5, 1, 1)] = B(support="floor")
    context[(5, 0, 1)] = "plinth"
    items[(5, 1, 2)] = B()
    items[(6, 1, 2)] = B()
    items[(6, 2, 2)] = B(feature=True)
    items[(6, 3, 2)] = B()
    items[(7, 3, 2)] = B()
    items[(5, 1, 3)] = B(feature=True)
    items[(5, 1, 4)] = dict(tier=tier, kind="reducer", sizes=("4w", "2w"), facing="south")
    items[(5, 1, 5)] = dict(tier=tier, kind="busway", size="2w")
    items[(2, 1, 1)] = dict(tier=tier, kind="tap_off", facing="north", closed=True, tripped=False)
    items[(4, 1, 1)] = dict(tier=tier, kind="tap_off", facing="north", closed=False, tripped=True)
    return items, context


def busway_scene_quads(root, name):
    tier = name.split("_")[-1]
    items, context = busway_layout(tier, name)
    props = {pos: busway_props(items, pos) for pos, it in items.items() if it["kind"] == "busway"}
    busway_vaxis(items, props)
    quads = []
    for pos, it in items.items():
        if it["kind"] == "busway":
            p = props[pos]
        elif it["kind"] == "tap_off":
            p = {"facing": it["facing"], "closed": str(it["closed"]).lower(), "tripped": str(it["tripped"]).lower()}
        else:
            p = {"facing": it["facing"]}
        quads += block_quads(root, busway_block_id(it), p, pos)
    for pos, kind in context.items():
        quads += context_quads(pos, kind, items)
    return quads


# Trays ------------------------------------------------------------------------------------------------------------

TRAY_BLOCK = {"standard": "cable_tray", "wide": "wide_cable_tray", "heavy": "heavy_duty_cable_tray"}


def tray_layout(name):
    items, context = {}, {}
    if name.endswith("_close"):
        # A straight on a ceiling hanger and an elbow on a wall bracket.
        items[(0, 0, 0)] = dict(mount="ceiling")
        context[(0, 1, 0)] = "stone"
        items[(1, 0, 0)] = dict(mount="south")
        context[(1, 0, 1)] = "wall"
        items[(2, 0, 0)] = dict(mount="none")
        items[(2, 0, 1)] = dict(mount="none")
        return items, context, [("close", None)]
    for x in range(3):
        items[(x, 1, 0)] = dict(mount="none")
    items[(1, 1, 0)]["mount"] = "ceiling"
    context[(1, 2, 0)] = "stone"
    items[(0, 1, 0)]["mount"] = "floor"
    context[(0, 0, 0)] = "plinth"
    items[(3, 1, 0)] = dict(mount="none")
    items[(3, 1, 1)] = dict(mount="east")
    context[(4, 1, 1)] = "wall"
    items[(3, 1, 2)] = dict(mount="none")
    items[(2, 1, 2)] = dict(mount="none")
    items[(4, 1, 2)] = dict(mount="none")
    items[(4, 2, 2)] = dict(mount="east")
    context[(5, 2, 2)] = "wall"
    items[(4, 3, 2)] = dict(mount="none")
    items[(5, 3, 2)] = dict(mount="none")
    items[(3, 1, 3)] = dict(mount="none")
    items[(3, 1, 4)] = dict(mount="none")
    items[(2, 1, 4)] = dict(mount="none")
    items[(4, 1, 4)] = dict(mount="none")
    paths = [("a", [((0, 1, 0), "east"), ((1, 1, 0), "east"), ((2, 1, 0), "east"), ((3, 1, 0), "south"), ((3, 1, 1), "south"),
                    ((3, 1, 2), "south"), ((3, 1, 3), "south"), ((3, 1, 4), "east"), ((4, 1, 4), "east")]),
             ("b", [((3, 1, 2), "east*"), ((4, 1, 2), "up"), ((4, 2, 2), "up"), ((4, 3, 2), "east"), ((5, 3, 2), "east")])]
    return items, context, paths


def tray_props(items, pos):
    p = {}
    for d, (dx, dy, dz) in FDIRS.items():
        p[d] = str((pos[0] + dx, pos[1] + dy, pos[2] + dz) in items).lower()
    p["mount"] = items[pos]["mount"]
    return p


def cable_set(kind, path):
    """The cables laid on a path: (tier, lateral centre off the tray's middle, bottom above the rung tops) per cable,
    filled slot by slot from the north / first rail. LV and MV 2 px across (MV told apart by its red stripe); HV / EHV
    trefoils of 1.5 / 2 px cores."""
    g = fgen()
    t = g.TRAY[TRAY_BLOCK[kind]]
    l0 = t["l0"] - 8
    out = []
    if kind == "standard":
        # Six 2 px slots: three LV and three MV.
        for i, tier in enumerate(("lv", "lv", "lv", "mv", "mv", "mv") if path != "b" else ("lv", "mv")):
            out.append((tier, l0 + 2.0 * i + 1.0, 0.05))
    elif kind == "wide":
        layers = 2 if path != "b" else 1
        tiers = ("lv", "mv", "lv", "mv", "mv", "lv")
        top = 0.05
        for layer in range(layers):
            for i, tier in enumerate(tiers if layer == 0 else tiers[::-1]):
                out.append((tier, l0 + 2.5 * i + 1.25, top))
            top += 2.5 + 0.05
    else:
        if path != "b":
            out.append(("hv", l0 + 2.0, 0.05))
            out.append(("ehv", l0 + 4.0 + 3.0, 0.05))
        else:
            out.append(("hv", l0 + 2.0, 0.05))
    return out


def cable_cores(tier, lat, z0):
    """(lateral, height of the centre above the rung tops, radius) of each core of one cable."""
    if tier in ("lv", "mv"):
        r = 1.0
        return [(lat, z0 + r, r)]
    rc = 0.75 if tier == "hv" else 1.0
    return [(lat - rc, z0 + rc, rc), (lat + rc, z0 + rc, rc), (lat, z0 + rc + 2 * rc * math.sin(math.radians(60)), rc)]


def vnorm(v):
    n = math.sqrt(sum(c * c for c in v))
    return [c / n for c in v]


def vsub(a, b):
    return [a[i] - b[i] for i in range(3)]


def vadd(a, b, s=1.0):
    return [a[i] + s * b[i] for i in range(3)]


def vdot(a, b):
    return sum(a[i] * b[i] for i in range(3))


def vcross(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def tray_turn_point(g, kind, block_props, heading_in, heading_out, pos):
    """The inner corner a turn's cables bend about (world px)."""
    t = g.TRAY[TRAY_BLOCK[kind]]
    base = [pos[0] * 16, pos[1] * 16, pos[2] * 16]
    if heading_in in ("up", "down") or heading_out in ("up", "down"):
        # A vertical elbow: built in its horizontal arm's frame.
        h = heading_out if heading_out not in ("up", "down") else FOPP[heading_in]
        v = heading_out if heading_out in ("up", "down") else FOPP[heading_in]
        fr = g.Frame(h)
        b, H = g.TB, t["H"]
        local = (b + H, 8, b + H) if v == "up" else (16 - b, 8, b)
        p = fr.point(local)
        return vadd(base, p)
    # A horizontal turn: the corner between the side it came in by and the side it leaves by.
    sides = {FOPP[heading_in], heading_out}
    x = t["l1"] if "east" in sides else t["l0"]
    z = t["l1"] if "south" in sides else t["l0"]
    return vadd(base, [x, 8, z])


def cable_paths(g, kind, items, path):
    """Each core's centreline as a list of (point, floor normal) in world px."""
    t = g.TRAY[TRAY_BLOCK[kind]]
    rung_top = (g.TB if t["lips"] == "out" else g.TB + 0.5) + t["rt"]
    steps = path[1]
    lines = []
    for tier, lat, z0 in cable_set(kind, path[0]):
        for clat, ch, r in cable_cores(tier, lat, z0):
            first_pos, first_head = steps[0]
            head = first_head.rstrip("*")
            u = FDIRS[head]
            base = [first_pos[0] * 16, first_pos[1] * 16, first_pos[2] * 16]
            # Start on the face it enters by (or the middle, for a branch leaving a cross), lateral to the left.
            lateral = vcross([0, 1, 0], u)
            start = [8 - 8 * u[0], rung_top + ch, 8 - 8 * u[2]]
            if first_head.endswith("*"):
                start = [8 + 8 * u[0], rung_top + ch, 8 + 8 * u[2]]
            start = vadd(start, lateral, -clat)
            p = vadd(base, start)
            n = [0.0, 1.0, 0.0]
            pts = [(p, n)]
            heading = head
            for k, (pos, out_head) in enumerate(steps):
                if k == 0 and first_head.endswith("*"):
                    continue
                out_head = out_head.rstrip("*")
                base = [pos[0] * 16, pos[1] * 16, pos[2] * 16]
                ui, uo = FDIRS[heading], FDIRS[out_head]
                if out_head == heading:
                    # Straight through: to the far face.
                    q = list(p)
                    for i in range(3):
                        if ui[i]:
                            q[i] = base[i] + (16 if ui[i] > 0 else 0)
                    p = q
                    pts.append((p, n))
                    continue
                P = tray_turn_point(g, kind, None, heading, out_head, pos)
                ax = vnorm(vcross(ui, uo))
                P = vadd(P, ax, vdot(vsub(p, P), ax))
                e1 = vadd(p, ui, vdot(vsub(P, p), ui))
                R = math.sqrt(vdot(vsub(e1, P), vsub(e1, P)))
                pts.append((e1, n))
                vertical = heading in ("up", "down") or out_head in ("up", "down")
                sign = 1.0
                if vertical:
                    sign = 1.0 if vdot(vsub(P, e1), n) > 0 else -1.0
                for s in range(1, 7):
                    phi = math.radians(90 * s / 6)
                    q = [P[i] + R * (-uo[i] * math.cos(phi) + ui[i] * math.sin(phi)) for i in range(3)]
                    nn = vnorm([sign * (P[i] - q[i]) for i in range(3)]) if vertical else n
                    pts.append((q, nn))
                p, n = pts[-1]
                q = list(p)
                for i in range(3):
                    if uo[i]:
                        q[i] = base[i] + (16 if uo[i] > 0 else 0)
                p = q
                pts.append((p, n))
                heading = out_head
            lines.append((tier, r, pts))
    return lines


def tube_quads(root, tier, r, pts):
    """An 8-sided tube along a polyline, face 0 toward the floor normal, textured from the jacket (rows round it)."""
    img = texture(root, {}, f"{NS}:block/cable/{tier}")
    quads = []
    R = r / math.cos(math.radians(22.5))
    for (p0, n0), (p1, _) in zip(pts, pts[1:]):
        d = vsub(p1, p0)
        if vdot(d, d) < 1e-8:
            continue
        tdir = vnorm(d)
        up = vnorm(vsub(n0, [tdir[i] * vdot(n0, tdir) for i in range(3)]))
        side = vcross(tdir, up)
        verts = []
        for k in range(8):
            a = math.radians(-22.5 + 45 * k)
            verts.append([up[i] * math.cos(a) * R + side[i] * math.sin(a) * R for i in range(3)])
        for k in range(8):
            v0, v1 = verts[k], verts[(k + 1) % 8]
            a = math.radians(45 * k)
            normal = [up[i] * math.cos(a) + side[i] * math.sin(a) for i in range(3)]
            corners = [vadd(p0, v0), vadd(p1, v0), vadd(p1, v1), vadd(p0, v1)]
            corners = [[c / 16 for c in q] for q in corners]
            vv0, vv1 = (2 * k) / 16, (2 * k + 2) / 16
            quads.append((corners, [(0, vv0), (1, vv0), (1, vv1), (0, vv1)], img, normal))
    return quads


def tray_scene_quads(root, name):
    g = fgen()
    kind = name.split("_")[1]
    items, context, paths = tray_layout(name)
    block = TRAY_BLOCK[kind]
    quads = []
    for pos in items:
        quads += block_quads(root, block, tray_props(items, pos), pos)
    for pos, c in context.items():
        quads += context_quads(pos, c, items)
    if name.endswith("_close"):
        paths = [("a", [((0, 0, 0), "east"), ((1, 0, 0), "east"), ((2, 0, 0), "south"), ((2, 0, 1), "south")])]
    for path in paths:
        for tier, r, pts in cable_paths(g, kind, items, path):
            quads += tube_quads(root, tier, r, pts)
    return quads


def factory_scene(root, name):
    if name.startswith("busway"):
        return busway_scene_quads(root, name)
    return tray_scene_quads(root, name)


def render(quads, yaw_deg, pitch_deg, view=None):
    view = view or VIEW
    size = view * SUPER
    yaw, pitch = math.radians(yaw_deg), math.radians(pitch_deg)
    cy, sy, cp, sp = math.cos(yaw), math.sin(yaw), math.cos(pitch), math.sin(pitch)
    pts = np.array([p for q in quads for p in q[0]])
    lo, hi = pts.min(axis=0) - 0.5, pts.max(axis=0) + 0.5
    lo[1] = 0
    centre = (lo + hi) / 2

    def project(p):
        x, y, z = p[0] - centre[0], p[1] - centre[1], p[2] - centre[2]
        rx, rz = x * cy - z * sy, x * sy + z * cy
        return np.array([rx, y * cp + rz * sp, rz * cp - y * sp])

    gx = range(int(math.floor(lo[0])), int(math.ceil(hi[0])))
    gz = range(int(math.floor(lo[2])), int(math.ceil(hi[2])))
    probe = [project([x, y, z]) for x in (lo[0], hi[0]) for y in (0, hi[1]) for z in (lo[2], hi[2])]
    probe = np.array(probe)
    scale = 0.9 * size / max(np.ptp(probe[:, 0]), np.ptp(probe[:, 1]))
    off = np.array([size / 2 - (probe[:, 0].min() + probe[:, 0].max()) / 2 * scale, size / 2 + (probe[:, 1].min() + probe[:, 1].max()) / 2 * scale])
    colour = np.zeros((size, size, 3), np.float32)
    colour[:] = (0x9D, 0xB7, 0xCF)
    depth = np.full((size, size), np.inf, np.float32)

    def screen(p):
        r = project(p)
        return np.array([off[0] + r[0] * scale, off[1] - r[1] * scale, r[2] * scale])

    def tri(a, b, c, uv, img, flat, shade):
        xs, ys = [a[0], b[0], c[0]], [a[1], b[1], c[1]]
        x0, x1 = max(0, int(math.floor(min(xs)))), min(size - 1, int(math.ceil(max(xs))))
        y0, y1 = max(0, int(math.floor(min(ys)))), min(size - 1, int(math.ceil(max(ys))))
        if x0 > x1 or y0 > y1:
            return
        area = (b[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (b[1] - a[1])
        if abs(area) < 1e-9:
            return
        X, Y = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        w0 = ((b[0] - X) * (c[1] - Y) - (c[0] - X) * (b[1] - Y)) / area
        w1 = ((c[0] - X) * (a[1] - Y) - (a[0] - X) * (c[1] - Y)) / area
        w2 = 1 - w0 - w1
        inside = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
        d = w0 * a[2] + w1 * b[2] + w2 * c[2]
        region = depth[y0:y1 + 1, x0:x1 + 1]
        inside &= d < region
        if img is not None:
            arr = ARRAYS.get(id(img))
            if arr is None:
                arr = ARRAYS[id(img)] = np.asarray(img)
            h, w = arr.shape[0], arr.shape[1]
            u = w0 * uv[0][0] + w1 * uv[1][0] + w2 * uv[2][0]
            v = w0 * uv[0][1] + w1 * uv[1][1] + w2 * uv[2][1]
            tx = np.clip(np.floor(u * w).astype(int), 0, w - 1)
            ty = np.clip(np.floor(v * h).astype(int), 0, h - 1)
            texel = arr[ty, tx]
            inside &= texel[..., 3] >= 128
            rgb = texel[..., :3].astype(np.float32)
        else:
            rgb = np.broadcast_to(np.array(flat, np.float32), X.shape + (3,))
        region[inside] = d[inside]
        colour[y0:y1 + 1, x0:x1 + 1][inside] = rgb[inside] * shade

    for x in gx:
        for z in gz:
            c = (0x6F, 0x9A, 0x4C) if (x + z) % 2 == 0 else (0x64, 0x90, 0x4A)
            q = [screen([x, 0, z]), screen([x + 1, 0, z]), screen([x + 1, 0, z + 1]), screen([x, 0, z + 1])]
            for s in q:
                s[2] += 1e-3 * scale
            tri(q[0], q[1], q[2], None, None, c, 1.0)
            tri(q[0], q[2], q[3], None, None, c, 1.0)
    light = np.array([-0.4, 1.0, -0.7]) / np.linalg.norm([-0.4, 1.0, -0.7])
    for q in quads:
        corners, uvs, img, n = q[:4]
        flat = q[4] if len(q) > 4 else None
        s = [screen(p) for p in corners]
        nx, ny, nz = n
        shade = (1.0 if ny > 0 else 0.5) if abs(ny) > 0.5 else 0.6 + 0.2 * abs(nx)
        shade = min(1.0, shade * (0.85 + 0.25 * max(0.0, nx * light[0] + ny * light[1] + nz * light[2])))
        tri(s[0], s[1], s[2], [uvs[0], uvs[1], uvs[2]], img, flat, shade)
        tri(s[0], s[2], s[3], [uvs[0], uvs[2], uvs[3]], img, flat, shade)
    big = Image.fromarray(np.clip(colour, 0, 255).astype(np.uint8))
    return big.resize((view, view), Image.BILINEAR)


def main():
    global ROOT
    ROOT = sys.argv[1]
    out = sys.argv[2]
    os.makedirs(out, exist_ok=True)
    names = sys.argv[3].split(",")
    if names == ["factory"]:
        names = FACTORY_SCENE_LIST
    for name in names:
        quads = scene(ROOT, name)
        views = [(0, 6), (90, 6), (45, 28)]
        view = FACTORY_VIEW if name in FACTORY_SCENES else VIEW
        img = Image.new("RGB", (view * len(views), view + 28), (0x20, 0x22, 0x26))
        for i, (yaw, pitch) in enumerate(views):
            img.paste(render(quads, yaw, pitch, view), (view * i, 28))
        ImageDraw.Draw(img).text((10, 8), name + "   front, side, 45 degrees (grid squares are blocks)", fill=(255, 255, 255))
        img.save(os.path.join(out, name + ".png"))
        print("rendered " + name)


ROOT = "."
if __name__ == "__main__":
    main()
