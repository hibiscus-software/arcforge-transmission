"""Renders JSON block models (multipart blockstates of plain element boxes) to PNGs for review, as ModelPreview does for
the towers: orthographic, textured from the real textures (nearest texel, cutout), lit like the game's block faces, on a
ground of block squares.

    python tools/block_model_preview.py <addon repo> <out dir> <scene>[,<scene>...]

A scene is a few placed blocks of one block (its blockstate's multipart picks each block's models from its properties):
  fence_run        a straight run of four Station Fences with a corner at its end
  barbed_run       the same fence run with Station Fence Barbed Wire on top of it
  barbed_close     a close look: two fences and a corner, with their barbed wire
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


def texture(root, textures, key):
    ref = key
    while ref.startswith("#"):
        ref = textures[ref[1:]]
    ns, path = ref.split(":") if ":" in ref else ("minecraft", ref)
    return Image.open(os.path.join(root, "src/main/resources/assets", ns, "textures", path + ".png")).convert("RGBA")


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
    return all(props.get(k) in str(v).split("|") for k, v in when.items())


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


def render(quads, yaw_deg, pitch_deg):
    size = VIEW * SUPER
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
            arr = np.asarray(img)
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
    for corners, uvs, img, n in quads:
        s = [screen(p) for p in corners]
        nx, ny, nz = n
        shade = (1.0 if ny > 0 else 0.5) if abs(ny) > 0.5 else 0.6 + 0.2 * abs(nx)
        shade = min(1.0, shade * (0.85 + 0.25 * max(0.0, nx * light[0] + ny * light[1] + nz * light[2])))
        tri(s[0], s[1], s[2], [uvs[0], uvs[1], uvs[2]], img, None, shade)
        tri(s[0], s[2], s[3], [uvs[0], uvs[2], uvs[3]], img, None, shade)
    big = Image.fromarray(np.clip(colour, 0, 255).astype(np.uint8))
    return big.resize((VIEW, VIEW), Image.BILINEAR)


def main():
    global ROOT
    ROOT = sys.argv[1]
    out = sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for name in sys.argv[3].split(","):
        quads = scene(ROOT, name)
        views = [(0, 6), (90, 6), (45, 28)]
        img = Image.new("RGB", (VIEW * len(views), VIEW + 28), (0x20, 0x22, 0x26))
        for i, (yaw, pitch) in enumerate(views):
            img.paste(render(quads, yaw, pitch), (VIEW * i, 28))
        ImageDraw.Draw(img).text((10, 8), name + "   front, side, 45 degrees (grid squares are blocks)", fill=(255, 255, 255))
        img.save(os.path.join(out, name + ".png"))
        print("rendered " + name)


ROOT = "."
if __name__ == "__main__":
    main()
