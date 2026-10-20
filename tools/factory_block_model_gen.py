"""The factory power distribution blocks: Busways (LV bus duct, MV metal-enclosed bus, HV gas-insulated bus, EHV
gas-insulated line) with their feed-ins, tap-offs and reducers; the three Cable Trays; and the cable jacket textures.

    python tools/factory_block_model_gen.py <addon repo>          writes everything
    python tools/factory_block_model_gen.py <addon repo> --check  also checks the written models (see check())

Writes, under src/main/resources:
    assets/arcforgetransmission/blockstates/<block>.json, items/<block>.json
    assets/arcforgetransmission/models/block/busway/<block>/<piece>.json, textures/block/busway/<block>.png
    assets/arcforgetransmission/models/block/cable_tray/<block>/<piece>.json, textures/block/cable_tray/<block>.png
    assets/arcforgetransmission/textures/block/cable/<tier>.png
    data/arcforgetransmission/block_shapes/<block>.json: {"parts": {piece: [[x0,y0,z0,x1,y1,z1], ...]}, "multipart": [...]}
        every piece's hitbox boxes (px, block-local, clipped to the block), and which piece applies when (the same
        conditions as the blockstate), so Java can build the VoxelShapes from the same numbers.

Every block's pieces share one 64x64 sheet; every visible face has its own painted rect at exactly 1 texel per px
(never stretched; faces painted alike share a rect). Faces hidden behind another part of the same model, or against a
connected neighbour, are left out, and no two faces facing the same way overlap in one plane in any blockstate
(check() proves it). Round parts (the GIS tubes, flanges and tanks, rods) are stepped boxes, each face toned for how
it faces the light from the top left (a stepped ramp round the part); their ends are flat rings. Pieces are built in
world space for every orientation (no blockstate rotations), so UVs never turn. Deterministic.

The multipart conditions are worked out from Python predicates over every blockstate (minimised to a few OR'd
clauses), so the connection logic lives in one place: busway_class() and tray_class() below.
"""
import itertools
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


def pal(*cs):
    return [hx(c) for c in cs]


GALV = pal("#4E555C", "#6B737B", "#7D858D", "#89919A", "#8F979F", "#959DA5", "#9BA3AA", "#A6AEB5", "#B6BDC3", "#C9CFD4")
PAINT = pal("#4A4E4C", "#5E6360", "#727774", "#868B88", "#9A9F9C", "#AEB3B0", "#C2C6C3")
# Aluminium a step darker than bare metal reads in the sun: the game's top-face light takes its top tones to white.
ALU = pal("#4C5258", "#61686E", "#767D84", "#8A9198", "#9DA4AA", "#AFB5BA", "#C0C5C9")
STEEL = pal("#16181B", "#2B2F34", "#383C42", "#40454C", "#474C53", "#575D65", "#727982", "#959DA6")
JACKET = pal("#141516", "#1C1E20", "#26292B", "#313437", "#3D4144")
STRIPE = {"mv": pal("#E5483C", "#8E231C"), "hv": pal("#E8913A", "#9E5A1C"), "ehv": pal("#8A55C2", "#52307E")}
DIAL, NEEDLE, BAND = hx("#DCE8EE"), hx("#16181B"), hx("#5FD4C4")
RED = pal("#E5483C", "#8E231C")
GREEN = pal("#4FB04A", "#2A6E33")
HAZARD = pal("#E0B020", "#1A1A1A")
MARK = STEEL[0]

# Per material: its ramp and which tones a flat face uses (fill, lit top/left edge, dark bottom/right edge, the corner
# where lit meets dark), and the range a round part's facets step through.
MATS = {
    "paint": dict(ramp=PAINT, fill=4, lit=5, dark=2, shade=3, lo=1, hi=6),
    "alu": dict(ramp=ALU, fill=4, lit=5, dark=2, shade=3, lo=1, hi=6),
    "steel": dict(ramp=STEEL, fill=4, lit=6, dark=1, shade=2, lo=1, hi=7),
    "galv": dict(ramp=GALV, fill=5, lit=8, dark=2, shade=3, lo=1, hi=8),
    "bolt": dict(ramp=GALV, fill=7, lit=9, dark=4, shade=5, lo=4, hi=9),
}
LIGHT = (-0.45, 1.0, -0.35)
_n = math.sqrt(sum(c * c for c in LIGHT))
LIGHT = tuple(c / _n for c in LIGHT)

TIERS = ("lv", "mv", "hv", "ehv")
SIZES = ("2w", "4w", "8w")
BANDS = {"2w": 1, "4w": 2, "8w": 3}
DIRS = ("north", "east", "south", "west", "up", "down")
HORIZ = ("north", "east", "south", "west")
OPP = {"north": "south", "south": "north", "east": "west", "west": "east", "up": "down", "down": "up"}
AXIS = {"east": "x", "west": "x", "north": "z", "south": "z", "up": "y", "down": "y"}
DVEC = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}

# --- geometry -------------------------------------------------------------------------------------------------------

FACE_CORNERS = {
    # Each face's corners (box axes: 0 min, 1 max) in UV order, top-left, top-right, bottom-right, bottom-left, seen
    # from outside (as the game maps a face's UV rect; tools/block_model_preview.py uses the same table).
    "north": [(1, 1, 0), (0, 1, 0), (0, 0, 0), (1, 0, 0)],
    "south": [(0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1)],
    "west": [(0, 1, 0), (0, 1, 1), (0, 0, 1), (0, 0, 0)],
    "east": [(1, 1, 1), (1, 1, 0), (1, 0, 0), (1, 0, 1)],
    "up": [(0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)],
    "down": [(0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0)],
}
FACE_AXIS = {"north": (2, -1), "south": (2, 1), "west": (0, -1), "east": (0, 1), "down": (1, -1), "up": (1, 1)}
FACE_OF = {v: k for k, v in FACE_AXIS.items()}
ALL_FACES = ("north", "south", "east", "west", "up", "down")


def uv_axes(face):
    """(u axis, u sign, v axis, v sign) of a face as world axes."""
    tl, tr, _, bl = FACE_CORNERS[face]
    ua = [i for i in range(3) if tl[i] != tr[i]][0]
    va = [i for i in range(3) if tl[i] != bl[i]][0]
    return ua, (1 if tr[ua] > tl[ua] else -1), va, (1 if bl[va] > tl[va] else -1)


def face_size(frm, to, face):
    ua, _, va, _ = uv_axes(face)
    return to[ua] - frm[ua], to[va] - frm[va]


# Local frames: a box is drawn in (a, l, h) = (along, lateral, height) and mapped to world (x, y, z). Each entry gives
# for world x, y, z the local axis (0 a, 1 l, 2 h) and whether it's flipped (16 - v). The horizontal ones are a facing
# direction (a+ toward it, h up); U / D run up / down with the lateral axis along x; UZ / DZ the same along z.
FRAMES = {
    "north": ((1, False), (2, False), (0, True)),
    "south": ((1, True), (2, False), (0, False)),
    "east": ((0, False), (2, False), (1, False)),
    "west": ((0, True), (2, False), (1, True)),
    "up": ((1, False), (0, False), (2, False)),
    "down": ((1, False), (0, True), (2, True)),
    "upz": ((2, True), (0, False), (1, False)),
    "downz": ((2, False), (0, True), (1, False)),
}
LOCAL = {"a": 0, "l": 1, "h": 2}


def rotate(p, axis, angle, origin):
    """The game's element rotation (and tools/block_model_preview.py's)."""
    a = math.radians(angle)
    x, y, z = p[0] - origin[0], p[1] - origin[1], p[2] - origin[2]
    c, s = math.cos(a), math.sin(a)
    if axis == 0:
        y, z = y * c - z * s, y * s + z * c
    elif axis == 1:
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return [x + origin[0], y + origin[1], z + origin[2]]


class Frame:
    def __init__(self, name):
        self.name = name
        self.map = FRAMES[name]

    def point(self, p):
        out = [0.0, 0.0, 0.0]
        for w in range(3):
            li, flip = self.map[w]
            out[w] = 16 - p[li] if flip else p[li]
        return out

    def axis(self, li):
        for w in range(3):
            if self.map[w][0] == li:
                return w, (-1 if self.map[w][1] else 1)

    def face(self, lf):
        """A local face name ("a+", "l-", ...) as the world face."""
        w, s = self.axis(LOCAL[lf[0]])
        return FACE_OF[(w, s if lf[1] == "+" else -s)]

    def vec(self, v):
        """A local direction vector in world space."""
        out = [0.0, 0.0, 0.0]
        for w in range(3):
            li, flip = self.map[w]
            out[w] = -v[li] if flip else v[li]
        return out


class Box:
    """One element. kind: "panel" (flat tones, 1 px lit / dark edges), "round" (a stepped round part: faces toned by
    where they face round its axis), "flat" (one tone). deco: face -> decoration (see paint_face)."""

    def __init__(self, frm, to, mat, kind="panel", name="part", hide=(), deco=None, rot=None, cont=(), raxis=None,
                 rc=None, rmax=None, tone=None, solid=True, hit=True):
        self.frm = [round(v, 4) for v in frm]
        self.to = [round(v, 4) for v in to]
        self.mat, self.kind, self.name = mat, kind, name
        self.hidden = set(hide)
        self.deco = dict(deco or {})
        self.rot = rot
        self.cont = set(cont)
        self.raxis, self.rc, self.rmax, self.tone = raxis, rc, rmax, tone
        self.solid, self.hit = solid and rot is None, hit

    def faces(self):
        return [f for f in ALL_FACES if f not in self.hidden]

    def vol(self):
        return all(self.to[i] - self.frm[i] > 1e-6 for i in range(3))


def B(frm, to, mat, **kw):
    return Box(frm, to, mat, **kw)


def L(fr, a, l, h, mat, **kw):
    """A box in a local frame (fr a Frame or its name): a, l, h are (lo, hi) ranges. Local face names in hide / deco,
    local axis letters in cont / raxis, a local point for rc and the rotation's (axis letter, angle, origin)."""
    fr = fr if isinstance(fr, Frame) else Frame(fr)
    rng = (a, l, h)
    frm, to = [0.0] * 3, [0.0] * 3
    for w in range(3):
        li, flip = fr.map[w]
        lo, hi = rng[li]
        if flip:
            lo, hi = 16 - hi, 16 - lo
        frm[w], to[w] = lo, hi
    if "hide" in kw:
        kw["hide"] = [fr.face(f) if f[0] in "alh" and len(f) == 2 else f for f in kw["hide"]]
    if kw.get("deco"):
        deco = {}
        for f, d in kw["deco"].items():
            wf = fr.face(f) if f[0] in "alh" and len(f) == 2 else f
            deco[wf] = tuple(fr.axis(LOCAL[x[1]])[0] if isinstance(x, str) and x.startswith("@") else x for x in d)
        kw["deco"] = deco
    if "cont" in kw:
        kw["cont"] = [fr.axis(LOCAL[c])[0] for c in kw["cont"]]
    if "raxis" in kw and kw["raxis"] is not None:
        kw["raxis"] = fr.axis(LOCAL[kw["raxis"]])[0]
        kw["rc"] = fr.point(kw["rc"])
    if kw.get("rot"):
        ax, angle, origin = kw["rot"]
        li = LOCAL[ax]
        o_l = list(origin)
        test = [origin[0] + 1.0, origin[1] + 2.0, origin[2] + 3.0]
        rl = rotate(test, li, angle, o_l)
        want = fr.point(rl)
        w, _ = fr.axis(li)
        ow = fr.point(o_l)
        best = None
        for sgn in (1, -1):
            got = rotate(fr.point(test), w, sgn * angle, ow)
            err = sum((got[i] - want[i]) ** 2 for i in range(3))
            if best is None or err < best[0]:
                best = (err, sgn)
        kw["rot"] = dict(origin=[round(v, 4) for v in ow], axis="xyz"[w], angle=best[1] * angle)
    return Box(frm, to, mat, **kw)


def mirror_box(b, axis):
    """b mirrored across the block's middle on a world axis (rotations negated)."""
    frm, to = list(b.frm), list(b.to)
    frm[axis], to[axis] = 16 - b.to[axis], 16 - b.frm[axis]
    swap = {FACE_OF[(axis, 1)]: FACE_OF[(axis, -1)], FACE_OF[(axis, -1)]: FACE_OF[(axis, 1)]}
    nb = Box(frm, to, b.mat, kind=b.kind, name=b.name, hide=[swap.get(f, f) for f in b.hidden],
             deco={swap.get(f, f): d for f, d in b.deco.items()}, cont=b.cont, raxis=b.raxis, rmax=b.rmax, tone=b.tone,
             solid=b.solid, hit=b.hit)
    if b.rc is not None:
        rc = list(b.rc)
        rc[axis] = 16 - rc[axis]
        nb.rc = rc
    if b.rot:
        o = list(b.rot["origin"])
        o[axis] = 16 - o[axis]
        ax = "xyz".index(b.rot["axis"])
        nb.rot = dict(origin=o, axis=b.rot["axis"], angle=b.rot["angle"] if ax == axis else -b.rot["angle"])
        nb.solid = False
    return nb


def columns(profile):
    """A stepped round cross-section as non-overlapping columns: profile [(L0, H0), (L1, H1), ...] with L falling and H
    rising means |lateral| <= Li reaches |height| <= Hi. Returns [((l0, l1), (h0, h1)), ...] offsets."""
    out = []
    for i, (lw, hw) in enumerate(profile):
        inner = profile[i + 1][0] if i + 1 < len(profile) else None
        if inner is None:
            out.append(((-lw, lw), (-hw, hw)))
        else:
            out.append(((inner, lw), (-hw, hw)))
            out.append(((-lw, -inner), (-hw, hw)))
    return out


def round_part(fr, a, lc, hc, profile, mat, name, hide=(), deco=None, cont=True, solid=True):
    """A stepped round part along a (from a[0] to a[1]) centred on (lc, hc) in the frame."""
    out = []
    rmax = max(max(p) for p in profile)
    for (l0, l1), (h0, h1) in columns(profile):
        out.append(L(fr, a, (lc + l0, lc + l1), (hc + h0, hc + h1), mat, kind="round", name=name, hide=hide,
                     deco=deco, raxis="a", rc=(0, lc, hc), rmax=rmax, cont=["a"] if cont else [], solid=solid))
    return out


def prism(fr, axis_letter, centre, half_len, profile, mat, name, hide=()):
    """A short stepped round part about a local axis through a local centre (a, l, h): used for knuckles and tanks."""
    out = []
    rmax = max(max(p) for p in profile)
    ai = LOCAL[axis_letter]
    others = [i for i in range(3) if i != ai]
    for (p0, p1), (q0, q1) in columns(profile):
        rng = [None] * 3
        rng[ai] = (centre[ai] - half_len, centre[ai] + half_len)
        rng[others[0]] = (centre[others[0]] + p0, centre[others[0]] + p1)
        rng[others[1]] = (centre[others[1]] + q0, centre[others[1]] + q1)
        out.append(L(fr, rng[0], rng[1], rng[2], mat, kind="round", name=name, hide=hide, raxis=axis_letter, rc=list(centre),
                     rmax=rmax))
    return out


# --- culling --------------------------------------------------------------------------------------------------------

def inside(p, b, eps=1e-4):
    return all(b.frm[i] + eps < p[i] < b.to[i] - eps for i in range(3))


def cull(boxes):
    """Leaves out every face whole covered by another (unrotated) box flush against it or round it."""
    solids = [b for b in boxes if b.solid and b.vol()]
    for b in boxes:
        if b.rot:
            continue
        for face in b.faces():
            axis, sign = FACE_AXIS[face]
            plane = b.to[axis] if sign > 0 else b.frm[axis]
            others = [i for i in range(3) if i != axis]
            spans = []
            for i in others:
                lo, hi = b.frm[i], b.to[i]
                n = max(1, int(math.ceil((hi - lo) / 0.25)))
                spans.append([lo + (k + 0.5) * (hi - lo) / n for k in range(n)])
            covered = True
            for s0 in spans[0]:
                for s1 in spans[1]:
                    p = [0.0, 0.0, 0.0]
                    p[axis] = plane + sign * 0.01
                    p[others[0]], p[others[1]] = s0, s1
                    if not any(o is not b and inside(p, o) for o in solids):
                        covered = False
                        break
                if not covered:
                    break
            if covered:
                b.hidden.add(face)
    return [b for b in boxes if b.faces()]


# --- painting -------------------------------------------------------------------------------------------------------

def texel_points(b, face, cw, ch, w, h):
    """World points at each texel's centre (over the part of the texel the face covers)."""
    corners = []
    for cx, cy, cz in FACE_CORNERS[face]:
        corners.append([b.frm[0] if cx == 0 else b.to[0], b.frm[1] if cy == 0 else b.to[1], b.frm[2] if cz == 0 else b.to[2]])
    tl, tr, _, bl = corners
    pts = []
    for j in range(ch):
        row = []
        fv = ((j + min(j + 1, h)) / 2) / h if h > 0 else 0.5
        for i in range(cw):
            fu = ((i + min(i + 1, w)) / 2) / w if w > 0 else 0.5
            row.append([tl[k] + fu * (tr[k] - tl[k]) + fv * (bl[k] - tl[k]) for k in range(3)])
        pts.append(row)
    return pts


def round_tone(b, p, normal):
    m = MATS[b.mat]
    off = [p[i] - b.rc[i] for i in range(3)]
    off[b.raxis] = 0.0
    n = math.sqrt(sum(c * c for c in off))
    if n < 1e-6:
        d = normal
    else:
        d = [0.6 * off[i] / n + 0.4 * normal[i] for i in range(3)]
        k = math.sqrt(sum(c * c for c in d))
        d = [c / k for c in d]
    bright = sum(d[i] * LIGHT[i] for i in range(3))
    t = m["lo"] + (m["hi"] - m["lo"]) * (bright + 1) / 2
    if b.mat in SMOOTH:
        return blend(m["ramp"], max(m["lo"], min(m["hi"], t)))
    return m["ramp"][max(m["lo"], min(m["hi"], int(round(t))))]


# Materials shaded smoothly (between their ramp's tones, not stepped): the switchgear's aluminium and paint.
SMOOTH = ("alu", "paint")


def blend(ramp, t):
    """The colour t of the way along a ramp (t in tones), blended between its two nearest tones."""
    i = max(0, min(len(ramp) - 2, int(math.floor(t))))
    f = max(0.0, min(1.0, t - i))
    a, b = ramp[i], ramp[i + 1]
    return tuple(int(round(a[k] + (b[k] - a[k]) * f)) for k in range(3)) + (255,)


def paint_face(b, face, cw, ch, w, h):
    """The face's texels, [row][col] of RGBA."""
    m = MATS.get(b.mat)
    deco = b.deco.get(face) or b.deco.get("*")
    ua, _, va, _ = uv_axes(face)
    axis, sign = FACE_AXIS[face]
    normal = [0.0, 0.0, 0.0]
    normal[axis] = float(sign)
    if deco and deco[0] in CUSTOM:
        return CUSTOM[deco[0]](b, face, cw, ch, w, h, deco)
    px = [[None] * cw for _ in range(ch)]
    if b.kind == "flat":
        for j in range(ch):
            for i in range(cw):
                px[j][i] = m["ramp"][b.tone]
    elif b.kind == "round" and b.raxis is not None and axis != b.raxis:
        pts = texel_points(b, face, cw, ch, w, h)
        for j in range(ch):
            for i in range(cw):
                px[j][i] = round_tone(b, pts[j][i], normal)
    elif b.kind == "round" and b.raxis is not None:
        # An end: flat rings, the rim lit on the top-left half and a step darker on the bottom-right.
        pts = texel_points(b, face, cw, ch, w, h)
        ud = [0.0] * 3
        vd = [0.0] * 3
        _, us, _, vs = uv_axes(face)
        ud[ua], vd[va] = us, vs
        for j in range(ch):
            for i in range(cw):
                off = [pts[j][i][k] - b.rc[k] for k in range(3)]
                du, dv = sum(off[k] * ud[k] for k in range(3)), sum(off[k] * vd[k] for k in range(3))
                dist = max(abs(du), abs(dv), (abs(du) + abs(dv)) / 1.35)
                lit = du + dv < 0
                if dist > (b.rmax or 3) - 0.9:
                    t = m["lit"] if lit else m["dark"]
                else:
                    t = m["fill"] if lit else m["fill"] - 1
                px[j][i] = m["ramp"][t]
    else:
        tiny = cw <= 2 and ch <= 2
        for j in range(ch):
            for i in range(cw):
                px[j][i] = m["ramp"][m["fill"]]
                # Smooth materials: a gentle fall from the top-left (half a tone lighter) to the bottom-right.
                if b.mat in SMOOTH and not tiny:
                    k = ((i + 0.5) / cw + (j + 0.5) / ch) / 2
                    px[j][i] = blend(m["ramp"], m["fill"] + 0.5 - 1.3 * k)
        if not tiny:
            eu = cw >= 3 and ua not in b.cont
            ev = ch >= 3 and va not in b.cont
            for j in range(ch):
                for i in range(cw):
                    t = None
                    if ev and j == 0 or eu and i == 0:
                        t = m["lit"]
                    if ev and j == ch - 1 or eu and i == cw - 1:
                        t = m["dark"] if t is None else m["shade"]
                    if t is not None:
                        px[j][i] = m["ramp"][t]
    if deco:
        DECO[deco[0]](px, b, face, cw, ch, w, h, deco)
    return px


def deco_ribs(px, b, face, cw, ch, w, h, deco):
    """Ribs pressed along a world axis: a lit row and its shadow every 3 px, across the face's interior."""
    m = MATS[b.mat]
    ua, _, va, _ = uv_axes(face)
    along = deco[1]
    # Over the face's fill (graded or not), not its edge lines.
    edges = {m["ramp"][m["lit"]], m["ramp"][m["dark"]], m["ramp"][m["shade"]]}
    if along == ua:
        for j in range(1, ch - 1):
            k = (j - 1) % 3
            if k in (1, 2):
                for i in range(cw):
                    if px[j][i] not in edges:
                        px[j][i] = m["ramp"][m["lit"] if k == 1 else m["dark"]]
    elif along == va:
        for i in range(1, cw - 1):
            k = (i - 1) % 3
            if k in (1, 2):
                for j in range(ch):
                    if px[j][i] not in edges:
                        px[j][i] = m["ramp"][m["lit"] if k == 1 else m["dark"]]


def bar_positions(n, count):
    """n 1 px bars with 1 px gaps, centred in count texels."""
    span = 2 * n - 1
    if span > count:
        n = max(1, (count + 1) // 2)
        span = 2 * n - 1
    start = (count - span) // 2
    return [start + 2 * i for i in range(n)]


def deco_bars(px, b, face, cw, ch, w, h, deco):
    """The size marking: n dark bars along the run (world axis deco[2]), centred across the face."""
    n, along = deco[1], deco[2]
    ua, _, va, _ = uv_axes(face)
    if along == ua:
        lo, hi = (1, ch - 1) if ch >= 2 * n + 1 + 2 else (0, ch)
        for j in bar_positions(n, hi - lo):
            for i in range(cw):
                px[lo + j][i] = MARK
    else:
        lo, hi = (1, cw - 1) if cw >= 2 * n + 1 + 2 else (0, cw)
        for i in bar_positions(n, hi - lo):
            for j in range(ch):
                px[j][lo + i] = MARK


def deco_recess(px, b, face, cw, ch, w, h, deco):
    """A socket: a recess (lips inverted: dark top/left, lit bottom/right), its floor dark, two stab contacts."""
    m = MATS[b.mat]
    for j in range(ch):
        for i in range(cw):
            if j == 0 or i == 0:
                c = STEEL[0]
            elif j == ch - 1 or i == cw - 1:
                c = m["ramp"][m["lit"]]
            else:
                c = STEEL[1]
            px[j][i] = c
    px[0][cw - 1] = STEEL[2]
    px[ch - 1][0] = STEEL[2]
    if cw >= 5 and ch >= 5:
        for i in (1, cw - 2):
            px[ch - 3][i] = GALV[7]


def deco_gland(px, b, face, cw, ch, w, h, deco):
    """A gland plate: cable entries as small dark rings with a lit lower lip."""
    n = deco[1]
    cy = ch // 2
    xs = bar_positions(n, max(1, cw // 3))
    for k in range(n):
        cx = int(round((k + 0.5) * cw / n - 0.5))
        for dj, di, c in ((-1, 0, STEEL[0]), (0, -1, STEEL[0]), (0, 0, STEEL[1]), (0, 1, STEEL[6]), (1, 0, STEEL[6])):
            j, i = cy + dj, cx + di
            if 0 <= j < ch and 0 <= i < cw:
                px[j][i] = c
    return xs


def deco_rivets(px, b, face, cw, ch, w, h, deco):
    """Bolt heads in the corners of a face 9 px or bigger (each with its shadow inward)."""
    if cw < 9 or ch < 9:
        return
    m = MATS[b.mat]
    for x, y, sx, sy in ((2, 2, 1, 1), (cw - 3, 2, -1, 1), (2, ch - 3, 1, -1), (cw - 3, ch - 3, -1, -1)):
        px[y][x] = m["ramp"][len(m["ramp"]) - 1]
        px[y + sy][x + sx] = m["ramp"][m["dark"]]


DECO = {"rivets": deco_rivets, "ribs": deco_ribs, "bars": deco_bars, "recess": deco_recess, "gland": deco_gland}


def custom_dial(b, face, cw, ch, w, h, deco):
    """An SF6 density gauge face: a white dial in a steel rim, the green band at the top right, the needle on it."""
    px = [[DIAL] * cw for _ in range(ch)]
    for j in range(ch):
        for i in range(cw):
            corner = (i in (0, cw - 1)) and (j in (0, ch - 1))
            if corner:
                px[j][i] = STEEL[5]
    if cw >= 4 and ch >= 4:
        px[0][cw - 2] = BAND
        px[1][cw - 1] = BAND
        px[0][1] = STEEL[6]
        px[ch - 1][cw - 2] = STEEL[3]
    px[ch // 2][cw // 2 - (1 if cw >= 4 else 0)] = NEEDLE
    if cw >= 4:
        px[ch // 2 - 1][cw // 2] = NEEDLE
    return px


def custom_flag(b, face, cw, ch, w, h, deco):
    tones = deco[1]
    return [[tones[0] if j < max(1, ch - 1) else tones[1] for i in range(cw)] for j in range(ch)]


def custom_hazard(b, face, cw, ch, w, h, deco):
    return [[HAZARD[(i + j) // 2 % 2] for i in range(cw)] for j in range(ch)]


def custom_plate(b, face, cw, ch, w, h, deco):
    """A rating plate: white, with n dark bars across it along world axis deco[2] (the size marking)."""
    px = [[DIAL] * cw for _ in range(ch)]
    n, along = deco[1], deco[2]
    ua, _, va, _ = uv_axes(face)
    if along == ua:
        for i in bar_positions(n, cw):
            for j in range(ch):
                px[j][i] = MARK
    else:
        for j in bar_positions(n, ch):
            for i in range(cw):
                px[j][i] = MARK
    return px


def custom_tone(b, face, cw, ch, w, h, deco):
    return [[deco[1]] * cw for _ in range(ch)]


CUSTOM = {"dial": custom_dial, "flag": custom_flag, "hazard": custom_hazard, "plate": custom_plate, "tone": custom_tone}

# --- sheets and models ----------------------------------------------------------------------------------------------


def face_rects(boxes):
    """Every visible face as (box index, face, w, h, cw, ch, pixels)."""
    out = []
    for i, b in enumerate(boxes):
        for face in b.faces():
            w, h = face_size(b.frm, b.to, face)
            if w < 1e-6 or h < 1e-6:
                continue
            cw, ch = max(1, int(math.ceil(w - 1e-6))), max(1, int(math.ceil(h - 1e-6)))
            px = paint_face(b, face, cw, ch, w, h)
            out.append((i, face, round(w, 4), round(h, 4), cw, ch, tuple(tuple(r) for r in px)))
    return out


def pack(models):
    """Shelf-packs the unique painted rects of all the models onto one sheet. Returns {(model, box, face): (x, y, w, h)}
    and the sheet image, or None when they don't fit."""
    uniq, owners = {}, {}
    for mname, boxes in models.items():
        for i, face, w, h, cw, ch, px in face_rects(boxes):
            key = (w, h, px)
            if key not in uniq:
                uniq[key] = (cw, ch)
            owners.setdefault(key, []).append((mname, i, face))
    keys = sorted(uniq, key=lambda k: (-uniq[k][1], -uniq[k][0], k[0], k[1], str(k[2])))
    x = y = shelf = 0
    placed, img = {}, Image.new("RGBA", (SHEET, SHEET), (0, 0, 0, 0))
    pix = img.load()
    for key in keys:
        cw, ch = uniq[key]
        if x + cw > SHEET:
            x, y, shelf = 0, y + shelf, 0
        if y + ch > SHEET:
            return None
        for j in range(ch):
            for i in range(cw):
                pix[x + i, y + j] = key[2][j][i]
        for owner in owners[key]:
            placed[owner] = (x, y, key[0], key[1])
        x += cw
        shelf = max(shelf, ch)
    return placed, img


def pack_sheets(models):
    """The models packed onto as few 64 px sheets as they need, in order (a block's related pieces share rects):
    [(model names, placements, sheet image)]."""
    sheets, group, last = [], [], None
    for name in models:
        trial = pack({n: models[n] for n in group + [name]})
        if trial is None:
            if not group:
                raise SystemExit(f"{name}: its faces don't fit one {SHEET}x{SHEET} sheet")
            sheets.append((group, last[0], last[1]))
            group, last = [name], pack({name: models[name]})
        else:
            group, last = group + [name], trial
    sheets.append((group, last[0], last[1]))
    return sheets


def model_json(mname, boxes, placed, texture, display=None):
    out = {"credit": "tools/factory_block_model_gen.py", "parent": "block/block", "ambientocclusion": False,
           "textures": {"sheet": texture, "particle": texture}, "elements": []}
    for i, b in enumerate(boxes):
        faces = {}
        for face in b.faces():
            if (mname, i, face) not in placed:
                continue
            x, y, w, h = placed[(mname, i, face)]
            faces[face] = {"uv": [round(x / 4, 4), round(y / 4, 4), round((x + w) / 4, 4), round((y + h) / 4, 4)], "texture": "#sheet"}
        if not faces:
            continue
        e = {"name": b.name, "from": b.frm, "to": b.to, "faces": faces}
        if b.rot:
            e["rotation"] = b.rot
        out["elements"].append(e)
    if display:
        out["display"] = display
    return out


DISPLAY = {"gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
           "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
           "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
           "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
           "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]}}


def hitboxes(boxes):
    """A piece's hitbox: its boxes (rotated ones by their bounds), clipped to the block, tiny trim (bolts, plates
    under 1 px^3) left out, and boxes inside another dropped."""
    out = []
    for b in boxes:
        if not b.hit:
            continue
        frm, to = list(b.frm), list(b.to)
        if b.rot:
            pts = []
            for cx in (frm[0], to[0]):
                for cy in (frm[1], to[1]):
                    for cz in (frm[2], to[2]):
                        pts.append(rotate([cx, cy, cz], "xyz".index(b.rot["axis"]), b.rot["angle"], b.rot["origin"]))
            frm = [min(p[i] for p in pts) for i in range(3)]
            to = [max(p[i] for p in pts) for i in range(3)]
        frm = [max(0.0, min(16.0, v)) for v in frm]
        to = [max(0.0, min(16.0, v)) for v in to]
        dims = [to[i] - frm[i] for i in range(3)]
        if min(dims) <= 1e-6 or dims[0] * dims[1] * dims[2] < 1.0:
            continue
        out.append([round(v, 3) for v in frm + to])
    keep = []
    for i, a in enumerate(out):
        if any(j != i and all(b[k] <= a[k] + 1e-6 for k in range(3)) and all(b[k + 3] >= a[k + 3] - 1e-6 for k in range(3))
               and (b != a or j < i) for j, b in enumerate(out)):
            continue
        keep.append(a)
    return sorted(keep)


# --- conditions -----------------------------------------------------------------------------------------------------

def fmt(v):
    return ("true" if v else "false") if isinstance(v, bool) else str(v)


class Domain:
    def __init__(self, props):
        self.props = props
        self.names = [n for n, _ in props]
        self.states = list(itertools.product(*[v for _, v in props]))

    def dicts(self):
        return [dict(zip(self.names, s)) for s in self.states]

    def when(self, pred):
        """A minimal-ish multipart condition for pred: {} (always), None (never), a dict, or {"OR": [...]}."""
        true = set(s for s in self.states if pred(dict(zip(self.names, s))))
        if not true:
            return None
        if len(true) == len(self.states):
            return {}
        values = [v for _, v in self.props]

        def expand(cube):
            return itertools.product(*[values[i] if c is None else [c] for i, c in enumerate(cube)])

        cubes, covered = [], set()
        for s in self.states:
            if s not in true or s in covered:
                continue
            cube = list(s)
            for i in range(len(cube)):
                trial = list(cube)
                trial[i] = None
                if all(t in true for t in expand(trial)):
                    cube = trial
            cubes.append(cube)
            covered |= set(expand(cube))
        # Drop cubes the others cover.
        changed = True
        while changed:
            changed = False
            for k in range(len(cubes)):
                rest = set()
                for j, c in enumerate(cubes):
                    if j != k:
                        rest |= set(expand(c))
                if set(expand(cubes[k])) <= rest:
                    cubes.pop(k)
                    changed = True
                    break
        terms = [{self.names[i]: fmt(c) for i, c in enumerate(cube) if c is not None} for cube in cubes]
        return terms[0] if len(terms) == 1 else {"OR": terms}


def matches(when, props):
    if not when:
        return True
    if "OR" in when:
        return any(matches(w, props) for w in when["OR"])
    if "AND" in when:
        return all(matches(w, props) for w in when["AND"])
    # A busway run's `damaged` only swaps its textures: the check reads it undamaged.
    return all(fmt(props.get(k, False if k == "damaged" else None)) in str(v).split("|") for k, v in when.items())


# --- busways --------------------------------------------------------------------------------------------------------

DUCT = {"lv": dict(W=6, H=8, socket=True), "mv": dict(W=8, H=10, socket=False)}
# GIS tubes: the profile (stepped octagon), the row pitch; flanges, knuckles (elbows) and the junction tank.
GIS = {
    "hv": dict(tube=[(2, 1), (1, 2)], cap=[(2.5, 1.25), (1.25, 2.5)], r=2.0, pitch=5.0, flange=[(3, 1), (2, 2), (1, 3)], fr=3.0,
               knuckle=[(3, 1.25), (2.25, 2.25), (1.25, 3)], kh=2.25,
               tank=[(7.5, 3.75), (6.25, 6.25), (3.75, 7.5)], tank_half=7.5, th=3.5, dial=3, rod=(7.25, 8.0)),
    "ehv": dict(tube=[(2.5, 1), (2, 2), (1, 2.5)], cap=[(2.75, 1.25), (2.25, 2.25), (1.25, 2.75)], r=2.5, pitch=5.5, flange=[(3, 1.5), (2.5, 2.5), (1.5, 3)], fr=3.0,
                knuckle=[(3, 1.25), (2.75, 2.75), (1.25, 3)], kh=2.75,
                tank=[(8, 4.25), (7.25, 7.25), (4.25, 8)], tank_half=8.0, th=3.75, dial=4, rod=(2.5, 3.0)),
}
STRAIGHT_FRAME = {"x": "east", "z": "south", "yx": "up", "yz": "upz"}


def conns(st):
    return [d for d in DIRS if st[d]]


def busway_class(st, gis):
    """("straight", key) with key x / z / yx / yz (a run along that axis, or nothing: x), ("helbow", d1, d2),
    ("velbow", h, v) (GIS only; the riser's row across the horizontal arm), or ("junction",)."""
    c = conns(st)
    axes = sorted({AXIS[d] for d in c})
    if len(axes) <= 1:
        ax = axes[0] if axes else "x"
        return ("straight", ("y" + st["vaxis"]) if ax == "y" else ax)
    if gis and len(c) == 2:
        h = [d for d in c if d in HORIZ]
        v = [d for d in c if d not in HORIZ]
        if len(h) == 2:
            return ("helbow",) + tuple(sorted(h))
        if len(h) == 1 and st["vaxis"] != AXIS[h[0]]:
            return ("velbow", h[0], v[0])
    return ("junction",)


def arm_frame(d, vaxis):
    if d in HORIZ:
        return d
    return d if vaxis == "x" else d + "z"


def duct_section(d, vaxis, W, H):
    """An arm's half-extents on world x, y, z (0 along the arm)."""
    hw, hh = W / 2, H / 2
    if d in ("east", "west"):
        return (0, hh, hw)
    if d in ("north", "south"):
        return (hw, hh, 0)
    return (hw, 0, hh) if vaxis == "x" else (hh, 0, hw)


def duct_centre(st, W, H):
    c = conns(st)
    ext = [0.0, 0.0, 0.0]
    for d in c:
        sec = duct_section(d, st["vaxis"], W, H)
        for i in range(3):
            ext[i] = max(ext[i], sec[i])
    return tuple(ext)


def bolt(fr, a, l, h, name="bolt"):
    return L(fr, a, l, h, "bolt", kind="panel", name=name, hit=False)


class Busway:
    """All the pieces of one busway block (tier, size), and the blockstate parts that pick them."""

    def __init__(self, tier, size):
        self.tier, self.size, self.n = tier, size, BANDS[size]
        self.gis = tier in GIS
        self.p = GIS[tier] if self.gis else DUCT[tier]
        self.models = {}
        self.parts = []  # (model, predicate)

    # Duct (LV / MV) ------------------------------------------------------------------------------------------------
    def duct_cover(self, fr, a, cap=False):
        """A joint cover (half of the band over a joint, a[1] at the block edge) or, capped, the end cover."""
        W, H = self.p["W"], self.p["H"]
        hw, hh = W / 2 + 0.5, H / 2 + 0.5
        lo, hi = a
        plus = lo >= 8
        outer = "a+" if plus else "a-"
        hide = [] if cap else [outer]
        deco = {"l+": ("bars", self.n, "@a"), "l-": ("bars", self.n, "@a"), "h+": ("bars", self.n, "@a"),
                "h-": ("bars", self.n, "@a")}
        out = [L(fr, a, (8 - hw, 8 + hw), (8 - hh, 8 + hh), "paint", name="cover", hide=hide, deco=deco)]
        mid = (lo + hi) / 2
        for s in (-1, 1):
            # Bolt heads: two on each side face, two on the top and bottom.
            for k in (-1, 1):
                out.append(bolt(fr, (mid - 0.5, mid + 0.5), (8 + s * hw, 8 + s * (hw + 0.5)) if s > 0 else (8 - hw - 0.5, 8 - hw),
                                (8 + k * (hh - 1) - 0.5, 8 + k * (hh - 1) + 0.5)))
                out.append(bolt(fr, (mid - 0.5, mid + 0.5), (8 + k * (hw - 1) - 0.5, 8 + k * (hw - 1) + 0.5),
                                (8 + hh, 8 + hh + 0.5) if s > 0 else (8 - hh - 0.5, 8 - hh)))
        if cap:
            e = hi if plus else lo
            sgn = 1 if plus else -1
            for k in (-1, 1):
                out.append(bolt(fr, (e, e + 0.5) if sgn > 0 else (e - 0.5, e), (7.5, 8.5), (8 + k * (hh - 1.5) - 0.5, 8 + k * (hh - 1.5) + 0.5)))
        return out

    def duct_body(self, fr, a, ribs=True, hide=("a-", "a+")):
        W, H = self.p["W"], self.p["H"]
        deco = {}
        if ribs and self.tier == "lv":
            deco = {"l+": ("ribs", "@a"), "l-": ("ribs", "@a")}
        return [L(fr, a, (8 - W / 2, 8 + W / 2), (8 - H / 2, 8 + H / 2), "paint", name="housing", deco=deco, hide=hide)]

    def duct_socket(self, fr):
        """LV plug-in opening on both sides: a raised frame with the socket recessed in it, the upper part behind a
        closed hinged flap (a tap-off's stab goes in below it)."""
        W = self.p["W"]
        out = []
        for s in (1, -1):
            l_in, l_out = 8 + s * W / 2, 8 + s * (W / 2 + 0.5)
            lr = (min(l_in, l_out), max(l_in, l_out))
            out.append(L(fr, (5.5, 10.5), lr, (4.5, 10.5), "paint", name="socket", deco={("l+" if s > 0 else "l-"): ("recess",)}))
            fl = (8 + s * (W / 2 + 0.5), 8 + s * (W / 2 + 0.75))
            out.append(L(fr, (5.75, 10.25), (min(fl), max(fl)), (7.75, 10.25), "paint", name="flap", hit=False))
            hl = (8 + s * (W / 2 + 0.5), 8 + s * (W / 2 + 1.0))
            out.append(L(fr, (6.0, 10.0), (min(hl), max(hl)), (10.25, 10.75), "steel", kind="round", raxis="a",
                         rc=(8, sum(hl) / 2, 10.5), rmax=0.25, name="hinge", hit=False))
        return out

    def duct_feature(self, fr):
        """MV access cover: a bolted panel on each side."""
        W, H = self.p["W"], self.p["H"]
        out = []
        for s in (1, -1):
            lr = (8 + W / 2, 8 + W / 2 + 0.5) if s > 0 else (8 - W / 2 - 0.5, 8 - W / 2)
            out.append(L(fr, (4, 12), lr, (8 - H / 2 + 1.5, 8 + H / 2 - 1.5), "paint", name="access_cover"))
            bl = (lr[1], lr[1] + 0.5) if s > 0 else (lr[0] - 0.5, lr[0])
            for a in (4.75, 11.25):
                for h in (8 - H / 2 + 2.25, 8 + H / 2 - 2.25):
                    out.append(bolt(fr, (a - 0.5, a + 0.5), bl, (h - 0.5, h + 0.5)))
        return out

    def duct_support(self, fr, kind):
        W, H = self.p["W"], self.p["H"]
        hw, hh = W / 2, H / 2
        out = [L(fr, (11.75, 13.25), (8 - hw - 1.25, 8 + hw + 1.25), (8 - hh - 1, 8 - hh), "steel", name="strap")]
        for s in (1, -1):
            lr = (8 + hw + 0.5, 8 + hw + 1.25) if s > 0 else (8 - hw - 1.25, 8 - hw - 0.5)
            if kind == "ceiling":
                out.append(L(fr, (12.125, 12.875), lr, (8 - hh, 15.5), "steel", kind="round", raxis="h", rc=(12.5, sum(lr) / 2, 0),
                             rmax=0.4, name="rod"))
                out.append(L(fr, (11.5, 13.5), (lr[0] - 0.25, lr[1] + 0.25), (15.5, 16), "steel", name="anchor"))
            else:
                out.append(L(fr, (12.125, 12.875), lr, (0.5, 8 - hh - 1), "steel", name="leg"))
                out.append(L(fr, (11.5, 13.5), (lr[0] - 0.25, lr[1] + 0.25), (0, 0.5), "steel", name="foot"))
        return out

    # GIS (HV / EHV) ------------------------------------------------------------------------------------------------
    def lats(self):
        p = self.p["pitch"]
        return [8 - p, 8.0, 8 + p]

    def tube(self, fr, a, lc, hide=()):
        return round_part(fr, a, lc, 8.0, self.p["tube"], "alu", "tube", hide=hide)

    def flange(self, fr, a, lc, hide=(), name="flange"):
        return round_part(fr, a, lc, 8.0, self.p["flange"], "alu", name, hide=hide, cont=False)

    def gis_body(self, fr):
        """A straight's tubes between its ends: the outer two from 2 to 14, the middle one either side of its own
        flange in the middle of the block (the joints are staggered so neighbouring flanges never meet), and the
        rating plate (the size marking) on that flange."""
        out = []
        lo, mid, hi = self.lats()
        for lc in (lo, hi):
            out += self.tube(fr, (2, 14), lc, hide=["a-", "a+"])
        out += self.tube(fr, (2, 5.5), mid, hide=["a-"])
        out += self.flange(fr, (5.5, 10.5), mid)
        out += self.tube(fr, (10.5, 14), mid, hide=["a+"])
        out.append(L(fr, (5.5, 10.5), (7, 9), (8 + self.p["fr"], 8 + self.p["fr"] + 0.25), "alu", name="rating_plate",
                     deco={"h+": ("plate", self.n, "@a")}, hit=False))
        return out

    def gis_end(self, fr, side, cap):
        """One end of a straight (side +1: a 14..16, -1: 0..2): the outer tubes' half flanges and the middle tube's
        last 2 px, or, capped, an end cover on all three."""
        out = []
        lo, mid, hi = self.lats()
        rng = (14, 16) if side > 0 else (0, 2)
        edge = "a+" if side > 0 else "a-"
        if cap:
            # End covers just over the tubes (neighbouring ones touch), each with a filling boss.
            cr = (14, 15.25) if side > 0 else (0.75, 2)
            br = (15.25, 15.75) if side > 0 else (0.25, 0.75)
            for lc in (lo, mid, hi):
                out += round_part(fr, cr, lc, 8.0, self.p["cap"], "alu", "end_cover", cont=False)
                out += round_part(fr, br, lc, 8.0, [(1, 0.5), (0.5, 1)], "alu", "filling_boss", cont=False)
        else:
            for lc in (lo, hi):
                out += self.flange(fr, rng, lc, hide=[edge])
            out += self.tube(fr, rng, mid, hide=["a-", "a+"])
        return out

    def gis_feature(self, fr):
        """The SF6 density gauge: a dial drum on a stub off the middle tube's top, facing both sides."""
        r, d = self.p["r"], self.p["dial"]
        a0 = 12.5 - d / 2
        out = [L(fr, (12, 13), (7.5, 8.5), (8 + r, 8 + r + 1.5), "steel", name="gauge_stub")]
        out.append(L(fr, (a0, a0 + d), (7.25, 8.75), (8 + r + 1.5, 8 + r + 1.5 + d), "steel", name="gauge",
                     deco={"l+": ("dial",), "l-": ("dial",)}))
        return out

    def gis_support(self, fr, kind):
        r = self.p["r"]
        top = 8 - r
        span = (0.25, 15.75)
        out = [L(fr, (11.75, 13.25), span, (top - 1.5, top), "steel", name="beam")]
        if kind == "floor":
            for lr in ((2.5, 4), (12, 13.5)):
                out.append(L(fr, (11.875, 13.125), lr, (0.5, top - 1.5), "steel", name="leg"))
                out.append(L(fr, (11.25, 13.75), (lr[0] - 0.5, lr[1] + 0.5), (0, 0.5), "steel", name="foot"))
        else:
            r0, r1 = self.p["rod"]
            for lr in ((8 - r1, 8 - r0), (8 + r0, 8 + r1)):
                out.append(L(fr, (12.25, 12.75), lr, (top, 15.5), "steel", kind="round", raxis="h", rc=(12.5, sum(lr) / 2, 0),
                             rmax=0.3, name="rod"))
                out.append(L(fr, (11.75, 13.25), (lr[0] - 0.25, lr[1] + 0.25), (15.5, 16), "steel", name="anchor"))
        return out

    # Pieces ------------------------------------------------------------------------------------------------------------
    def add(self, name, boxes, pred):
        if name not in self.models:
            self.models[name] = cull(boxes)
        self.parts.append((name, pred))

    def build(self):
        gis = self.gis
        cls = lambda st: busway_class(st, gis)
        for key, frn in STRAIGHT_FRAME.items():
            fr = Frame(frn)
            is_s = (lambda k: lambda st: cls(st) == ("straight", k))(key)
            ends = {+1: "a+", -1: "a-"}
            if gis:
                self.add(f"straight_{key}", self.gis_body(fr), is_s)
            else:
                body = self.duct_body(fr, (2, 14))
                if self.p["socket"]:
                    body += self.duct_socket(fr)
                self.add(f"straight_{key}", body, is_s)
            for side in (1, -1):
                d = self.dir_of(fr, side)
                conn = (lambda k, d: lambda st: cls(st) == ("straight", k) and st[d])(key, d)
                capd = (lambda k, d: lambda st: cls(st) == ("straight", k) and not st[d])(key, d)
                rng = (14, 16) if side > 0 else (0, 2)
                if gis:
                    self.add(f"joint_{key}_{d}", self.gis_end(fr, side, False), conn)
                    self.add(f"cap_{key}_{d}", self.gis_end(fr, side, True), capd)
                else:
                    self.add(f"joint_{key}_{d}", self.duct_cover(fr, rng), conn)
                    self.add(f"cap_{key}_{d}", self.duct_cover(fr, (14, 15.5) if side > 0 else (0.5, 2), cap=True), capd)
            feat = (lambda k: lambda st: cls(st) == ("straight", k) and st["feature"])(key)
            if gis:
                self.add(f"feature_{key}", self.gis_feature(fr), feat)
            elif self.tier == "mv":
                self.add(f"feature_{key}", self.duct_feature(fr), feat)
            if key in ("x", "z"):
                for kind in ("floor", "ceiling"):
                    sup = (lambda k, kind: lambda st: cls(st) == ("straight", k) and st["support"] == kind)(key, kind)
                    self.add(f"support_{key}_{kind}", self.gis_support(fr, kind) if gis else self.duct_support(fr, kind), sup)
        if gis:
            self.build_gis_junctions(cls)
        else:
            self.build_duct_junctions(cls)

    def dir_of(self, fr, side):
        w, s = fr.axis(0)
        return {(0, 1): "east", (0, -1): "west", (1, 1): "up", (1, -1): "down", (2, 1): "south", (2, -1): "north"}[(w, s * side)]

    def build_duct_junctions(self, cls):
        W, H = self.p["W"], self.p["H"]
        dom = busway_domain()
        centres = {}
        for st in dom.dicts():
            if cls(st)[0] != "junction":
                continue
            centres.setdefault(duct_centre(st, W, H), True)
        for ext in sorted(centres):
            cname = "centre_" + "_".join(f"{v:g}" for v in ext).replace(".", "p")
            box = B([8 - ext[0], 8 - ext[1], 8 - ext[2]], [8 + ext[0], 8 + ext[1], 8 + ext[2]], "paint", name="junction")
            for d in DIRS:
                f = {"north": "north", "south": "south", "east": "east", "west": "west", "up": "up", "down": "down"}[d]
                # The face toward d shows unless an arm as big as it covers it.
                def pred(st, ext=ext, d=d):
                    if cls(st)[0] != "junction" or duct_centre(st, W, H) != ext:
                        return False
                    if not st[d]:
                        return True
                    sec = duct_section(d, st["vaxis"], W, H)
                    return any(abs(sec[i] - ext[i]) > 1e-6 for i in range(3) if i != "xyz".index(AXIS[d]))
                bx = Box(box.frm, box.to, "paint", name="junction", hide=[x for x in ALL_FACES if x != f])
                self.add(f"{cname}_{d}", [bx], pred)
        # Arms: from the centre's face to the edge, with the joint cover.
        for d in DIRS:
            for vaxis in (("x", "z") if d in ("up", "down") else ("x",)):
                starts = set()
                for st in dom.dicts():
                    if cls(st)[0] == "junction" and st[d] and (d in HORIZ or st["vaxis"] == vaxis):
                        starts.add(duct_centre(st, W, H)["xyz".index(AXIS[d])])
                for s in sorted(starts):
                    fr = Frame(arm_frame(d, vaxis))
                    boxes = []
                    if 8 + s < 14:
                        boxes += self.duct_body(fr, (8 + s, 14))
                    boxes += self.duct_cover(fr, (14, 16))
                    name = f"arm_{d}" + (f"_{vaxis}" if d not in HORIZ else "") + f"_{s:g}".replace(".", "p")

                    def pred(st, d=d, vaxis=vaxis, s=s):
                        return (cls(st)[0] == "junction" and st[d] and (d in HORIZ or st["vaxis"] == vaxis)
                                and duct_centre(st, W, H)["xyz".index(AXIS[d])] == s)
                    self.add(name, boxes, pred)

    def gis_arm(self, d, vaxis, start_of):
        """Tubes from inside the tank / knuckle to the block edge, the outer two with their half flanges."""
        fr = Frame(arm_frame(d, vaxis))
        out = []
        lo, mid, hi = self.lats()
        for lc in (lo, mid, hi):
            s = start_of(lc)
            if lc == mid:
                if s < 16:
                    out += self.tube(fr, (s, 16), lc, hide=["a+"])
            else:
                if s < 14:
                    out += self.tube(fr, (s, 14), lc)
                out += self.flange(fr, (max(14, s), 16), lc, hide=["a+"])
        return out

    def build_gis_junctions(self, cls):
        p = self.p
        th, T = p["th"], p["tank_half"]
        # The junction: a square cross-module tank the tubes butt into (the row is as wide as the tank, so nothing
        # rounder keeps two arms' outer tubes apart at a corner), bolted covers painted on its top and bottom.
        tank = B([8 - T, 8 - th, 8 - T], [8 + T, 8 + th, 8 + T], "alu", name="tank", deco={"up": ("rivets",), "down": ("rivets",)})
        self.add("tank", [tank], lambda st: cls(st) == ("junction",))
        for d in DIRS:
            for vaxis in (("x", "z") if d in ("up", "down") else ("x",)):
                fr = Frame(arm_frame(d, vaxis))
                out = []
                if d in HORIZ:
                    if 8 + T < 16:
                        for lc in self.lats():
                            out += self.tube(fr, (8 + T, 16), lc, hide=["a-", "a+"])
                else:
                    lo, mid, hi = self.lats()
                    for lc in (lo, hi):
                        out += self.tube(fr, (8 + th, 14), lc, hide=["a-"])
                        out += self.flange(fr, (14, 16), lc, hide=["a+"])
                    out += self.tube(fr, (8 + th, 16), mid, hide=["a-", "a+"])
                if not out:
                    continue
                name = f"tank_arm_{d}" + (f"_{vaxis}" if d not in HORIZ else "")
                self.add(name, out, (lambda d, vaxis: lambda st: cls(st) == ("junction",) and st[d] and (d in HORIZ or st["vaxis"] == vaxis))(d, vaxis))
        # Horizontal elbows: each tube turns about its own knuckle, the knuckles on the diagonal so the bends nest.
        for d1, d2 in itertools.combinations(sorted(HORIZ), 2):
            if AXIS[d1] == AXIS[d2]:
                continue
            boxes = []
            v1, v2 = DVEC[d1], DVEC[d2]
            for o in (-p["pitch"], 0.0, p["pitch"]):
                cx, cz = 8 + o * (v1[0] + v2[0]), 8 + o * (v1[2] + v2[2])
                boxes += prism(Frame("east"), "h", (cx, cz, 8), p["kh"], p["knuckle"], "alu", "knuckle")
                for d, v in ((d1, v1), (d2, v2)):
                    fr = Frame(d)
                    # Local a along d; the knuckle centre's distance from the middle along d, its lateral position.
                    along = 8 + (cx - 8) * v[0] + (cz - 8) * v[2] + p["r"]
                    lat_world = cz if AXIS[d] == "x" else cx
                    # The lateral local coordinate for a world lateral position (frames may flip l).
                    lloc = lat_world if fr.axis(1)[1] > 0 else 16 - lat_world
                    if o > 0:
                        # The innermost bend: its knuckle sits in the corner and covers the joints itself.
                        boxes += self.tube(fr, (along, 16), lloc, hide=["a-", "a+"])
                    elif abs(lloc - 8) > 1e-6:
                        if along < 14:
                            boxes += self.tube(fr, (along, 14), lloc, hide=["a-"])
                        boxes += self.flange(fr, (max(14, along), 16), lloc, hide=["a+"])
                    else:
                        boxes += self.tube(fr, (along, 16), lloc, hide=["a-", "a+"])
            self.add(f"elbow_{d1}_{d2}", boxes, (lambda d1, d2: lambda st: cls(st) == ("helbow", d1, d2))(d1, d2))
        # Vertical elbows: the row stays across the horizontal arm; each tube bends about a knuckle in the middle.
        for h in HORIZ:
            for v in ("up", "down"):
                vaxis = "z" if AXIS[h] == "x" else "x"
                boxes = []
                frh, frv = Frame(h), Frame(arm_frame(v, vaxis))
                # Knuckles: drums across the row (along the lateral axis), one per tube.
                for lc in self.lats():
                    boxes += prism(frh, "l", (8, lc, 8), p["kh"], p["knuckle"], "alu", "knuckle")
                k0 = 8 + p["r"]
                for fr in (frh, frv):
                    for lc in self.lats():
                        if abs(lc - 8) > 1e-6:
                            boxes += self.tube(fr, (k0, 14), lc, hide=["a-"])
                            boxes += self.flange(fr, (14, 16), lc, hide=["a+"])
                        else:
                            boxes += self.tube(fr, (k0, 16), lc, hide=["a-", "a+"])
                self.add(f"elbow_{h}_{v}", boxes, (lambda h, v: lambda st: cls(st) == ("velbow", h, v))(h, v))


def busway_domain():
    return Domain([(d, (False, True)) for d in DIRS] + [("vaxis", ("x", "z")), ("feature", (False, True)),
                                                        ("support", ("none", "floor", "ceiling"))])


# --- busway fittings ------------------------------------------------------------------------------------------------

FIT_FRAME = {"north": "north", "south": "south", "east": "east", "west": "west", "up": "up", "down": "down"}


class Fitting(Busway):
    """A feed-in, tap-off or reducer: pieces chosen by facing (and the tap-off's closed / tripped flags)."""

    def __init__(self, tier, kind, sizes=None):
        Busway.__init__(self, tier, sizes[0] if sizes else "2w")
        self.kind, self.sizes = kind, sizes
        self.domain = fitting_domain(kind)

    def outlet_from(self, fr, a0):
        """The busway end a fitting presents on its a+ face (from a0): like a straight's connected end."""
        if self.gis:
            out = self.gis_end(fr, 1, False)
            lo, mid, hi = self.lats()
            for lc in (lo, mid, hi):
                out += self.tube(fr, (a0, 14), lc, hide=["a-"])
            return out
        W, H = self.p["W"], self.p["H"]
        out = [L(fr, (a0, 14), (8 - W / 2, 8 + W / 2), (8 - H / 2, 8 + H / 2), "paint", name="housing", hide=["a-"],
                 deco={"l+": ("ribs", "@a"), "l-": ("ribs", "@a")} if self.tier == "lv" else {})]
        return out + self.duct_cover(fr, (14, 16))

    # Feed-in -------------------------------------------------------------------------------------------------------
    def feed_in(self, facing, axis="x"):
        if facing == "up":
            # Rising to busway that runs along `axis`: its tubes (or duct) turned as that busway's riser is.
            return self.feed_in_up(Frame(arm_frame("up", "z" if axis == "x" else "x")))
        fr = Frame(facing)
        if self.gis:
            return self.gis_feed(fr, False)
        H = self.p["H"]
        top = 8 + H / 2 + 1.5
        out = [L(fr, (1.5, 12.5), (2, 14), (1, top), "paint", name="entry_box"),
               L(fr, (1, 13), (1.5, 14.5), (top, top + 0.5), "paint", name="lid"),
               L(fr, (0.5, 13.5), (0.75, 15.25), (0, 1), "paint", name="gland_plate", deco={"h-": ("gland", 2)})]
        out += self.side_doors(fr, (3.5, 10.5), (3, top - 2), 2, 14)
        for a in (2.5, 7, 11.5):
            for l in (1.375, 14.625):
                out.append(bolt(fr, (a - 0.5, a + 0.5), (l - 0.5, l + 0.5), (1, 1.5)))
        out += self.outlet_from(fr, 12.5)
        return out

    def side_doors(self, fr, a, h, l0, l1):
        """A bolted door panel on each side face of a box (l0, l1 its side planes)."""
        out = []
        for lr, bl in (((l1, l1 + 0.5), (l1 + 0.5, l1 + 1)), ((l0 - 0.5, l0), (l0 - 1, l0 - 0.5))):
            out.append(L(fr, a, lr, h, "paint", name="door"))
            for aa in (a[0] + 0.75, a[1] - 0.75):
                for hh in (h[0] + 0.75, h[1] - 0.75):
                    out.append(bolt(fr, (aa - 0.5, aa + 0.5), bl, (hh - 0.5, hh + 0.5)))
        return out

    def feed_in_up(self, fr):
        """Facing up: the box stands on the floor (local a is up) and the outlet rises from its lid."""
        if self.gis:
            return self.gis_feed(fr, True)
        out = [L(fr, (1, 11.5), (2, 14), (2, 14), "paint", name="entry_box"),
               L(fr, (11.5, 12), (1.5, 14.5), (1.5, 14.5), "paint", name="lid"),
               L(fr, (0, 1), (1.5, 14.5), (1.5, 14.5), "paint", name="gland_plate", deco={"a-": ("gland", 2)})]
        for hr, bl in (((14, 14.5), (14.5, 15)), ((1.5, 2), (1, 1.5))):
            out.append(L(fr, (3, 9.5), (4, 12), hr, "paint", name="door"))
            for a in (3.75, 8.75):
                for l in (4.75, 11.25):
                    out.append(bolt(fr, (a - 0.5, a + 0.5), (l - 0.5, l + 0.5), bl))
        return out + self.outlet_from(fr, 12)

    def gis_feed(self, fr, up):
        """HV / EHV: an elbow tank on a bottom flange; the three tubes leave on the facing side (or up)."""
        p = self.p
        th = p["th"]
        out = []
        base = [(7.5, 4), (6.5, 6.5), (4, 7.5)]
        if up:
            # Local a is up: a vertical tank from the flange to below the outlet.
            out += prism(fr, "a", (0.75, 8, 8), 0.75, base, "alu", "base_flange")
            out += prism(fr, "a", (6.75, 8, 8), 5.25, p["tank"], "alu", "tank")
            out += self.outlet_from(fr, 12)
            return out
        out += prism(fr, "h", (8, 8, 0.75), 0.75, base, "alu", "base_flange")
        top = 8 + th
        out += prism(fr, "h", (8, 8, (1.5 + top) / 2), (top - 1.5) / 2, p["tank"], "alu", "tank")
        out += prism(fr, "h", (6, 8, top + 0.5), 0.5, [(2, 1), (1, 2)], "alu", "relief_disc")
        tank = p["tank"]
        lo, mid, hi = self.lats()
        for lc in (lo, mid, hi):
            m = abs(lc - 8) + p["r"]
            s = 8 + max(A for A, lat in tank if lat >= m - 1e-6)
            if lc == mid:
                if s < 16:
                    out += self.tube(fr, (s, 16), lc, hide=["a+"])
            else:
                if s < 14:
                    out += self.tube(fr, (s, 14), lc)
                out += self.flange(fr, (max(14, s), 16), lc, hide=["a+"])
        return out

    # Tap-off -------------------------------------------------------------------------------------------------------
    def neck_end(self, facing):
        """How far (local a, in the tap-off's own block's px) it reaches toward the busway's housing / nearest tube."""
        if self.gis:
            r, pitch = self.p["r"], self.p["pitch"]
            return 24 - r if facing in ("up", "down") else 24 - pitch - r
        W, H = self.p["W"], self.p["H"]
        if facing in ("up", "down"):
            return 24 - H / 2
        return 24 - W / 2 - (0.5 if self.p["socket"] else 0)

    def tap_body(self, facing):
        fr = Frame(FIT_FRAME[facing])
        end = self.neck_end(facing)
        out = []
        if self.tier == "lv":
            out += [L(fr, (9, 15.5), (3, 13), (2.5, 13), "paint", name="tap_box"),
                    L(fr, (15.5, end), (6.5, 9.5), (5.75, 7.75), "paint", name="plug_in_stab"),
                    L(fr, (8.5, 9), (4, 12), (3.5, 12), "paint", name="gland_plate", deco={"a-": ("gland", 1)})]
            out += round_part(fr, (7, 8.5), 8, 7.75, [(1.5, 0.75), (0.75, 1.5)], "steel", "cable_gland", cont=False)
            out += self.handle_base(fr, 13, (10.5, 12.5), 9.75)
        elif self.tier == "mv":
            out += [L(fr, (6, 14.5), (2, 14), (1.5, 14.5), "paint", name="tap_box"),
                    L(fr, (14.5, end - 1), (5.5, 10.5), (5.5, 10.5), "paint", name="collar"),
                    L(fr, (end - 1, end), (4.5, 11.5), (4.5, 11.5), "paint", name="tap_flange"),
                    L(fr, (5.5, 6), (3, 13), (2.5, 13.5), "paint", name="gland_plate", deco={"a-": ("gland", 2)})]
            for l in (5.25, 10.75):
                for h in (5.25, 10.75):
                    out.append(bolt(fr, (end - 1.5, end - 1), (l - 0.5, l + 0.5), (h - 0.5, h + 0.5)))
            for l in (5.5, 10.5):
                out += round_part(fr, (4, 5.5), l, 8, [(1.5, 0.75), (0.75, 1.5)], "steel", "cable_gland", cont=False)
            out += self.handle_base(fr, 14, (10, 12), 10.25)
        else:
            p = self.p
            tank = [(4.5, 2), (3.5, 3.5), (2, 4.5)]
            out += round_part(fr, (2, 11), 8, 8, tank, "alu", "tee_tank", cont=False)
            out += round_part(fr, (11, 12.5), 8, 8, p["flange"], "alu", "nozzle_flange", cont=False)
            out += round_part(fr, (12.5, end), 8, 8, p["tube"], "alu", "branch", cont=False)
            # The cable termination on the far end, the earthing-switch housing on top, the mechanism cabinet beside.
            out += round_part(fr, (0.5, 2), 8, 8, [(3, 1.25), (1.25, 3)], "alu", "cable_termination", cont=False)
            out += round_part(fr, (0, 0.5), 8, 8, [(1.5, 0.75), (0.75, 1.5)], "steel", "cable_entry", cont=False)
            out.append(L(fr, (4.75, 6.25), (7.25, 8.75), (12.5, 13), "alu", name="earthing_neck"))
            out += prism(fr, "h", (5.5, 8, 14.25), 1.25, [(1.75, 0.75), (0.75, 1.75)], "alu", "earthing_switch")
            out.append(L(fr, (3.5, 9.5), (12, 15), (5, 12), "steel", name="mechanism_cabinet"))
            out += self.handle_base(fr, 15, (4.75, 6.75), 9.25)
        return out

    def handle_base(self, fr, l_face, a, h):
        """The operating handle's boss on a box side (at local l = l_face)."""
        return [L(fr, a, (l_face, l_face + 0.75), (h - 0.75, h + 0.75), "steel", name="handle_boss")]

    def flag_window(self):
        """(a range, h range, l face) of the ON / OFF flag window on the operating side."""
        if self.tier == "lv":
            return (13, 14.75), (9, 10.75), 13
        if self.tier == "mv":
            return (12.5, 14), (11.75, 13.25), 14
        return (7.5, 9.0), (9.75, 11.25), 15

    def tap_flag(self, facing, closed):
        fr = Frame(FIT_FRAME[facing])
        a, h, lf = self.flag_window()
        tones = RED if closed else GREEN
        out = [L(fr, a, (lf, lf + 0.25), h, "steel", name="flag_window", deco={"*": ("flag", tones)}, hit=False)]
        # The handle's lever: up when closed, down when open.
        if self.tier == "lv":
            ab, hb, lb = (11.25, 11.75), 9.75, 13.75
        elif self.tier == "mv":
            ab, hb, lb = (10.75, 11.25), 10.25, 14.75
        else:
            ab, hb, lb = (5.5, 6.0), 9.25, 15.75
        hr = (hb, hb + 2.75) if closed else (hb - 2.75, hb)
        lr = (lb - 0.5, lb) if lb > 16 else (lb, lb + 0.5)
        out.append(L(fr, ab, lr, hr, "steel", name="handle", hit=False))
        return out

    def tap_tripped(self, facing):
        fr = Frame(FIT_FRAME[facing])
        if self.tier == "lv":
            a, l, h = (10.25, 11.75), (10.75, 11.25), (13, 14.5)
        elif self.tier == "mv":
            a, l, h = (7.25, 8.75), (11.75, 12.25), (14.5, 16)
        else:
            a, l, h = (7.75, 9.25), (13.75, 14.25), (12, 13.5)
        return [L(fr, a, l, h, "steel", name="trip_flag", deco={"*": ("hazard",)}, hit=False)]

    # Reducer -------------------------------------------------------------------------------------------------------
    def reducer(self, facing):
        """From the bigger size (a-) to the smaller (a+): the housing steps down through a breaker section that carries
        its own protection (a small breaker handle). Each end's cover / plate carries its own size marking."""
        fr = Frame({"up": "upz", "down": "downz"}.get(facing, facing))
        n_from, n_to = BANDS[self.sizes[0]], BANDS[self.sizes[1]]
        out = []
        if not self.gis:
            W, H = self.p["W"], self.p["H"]
            hw, hh = W / 2, H / 2
            self.n = n_from
            out += self.duct_cover(fr, (0, 2))
            self.n = n_to
            out += self.duct_cover(fr, (14, 16))
            out += [L(fr, (2, 4), (8 - hw, 8 + hw), (8 - hh, 8 + hh), "paint", name="housing"),
                    L(fr, (4, 11), (8 - hw - 1.5, 8 + hw + 1.5), (8 - hh - 1.5, 8 + hh + 1.5), "paint", name="breaker_section"),
                    L(fr, (11, 12), (8 - hw - 0.75, 8 + hw + 0.75), (8 - hh - 0.75, 8 + hh + 0.75), "paint", name="step"),
                    L(fr, (12, 14), (8 - hw, 8 + hw), (8 - hh, 8 + hh), "paint", name="housing")]
            lf = 8 + hw + 1.5
            out += [L(fr, (5.5, 9.5), (lf, lf + 0.5), (8 - 1.5, 8 + 2.5), "steel", name="breaker_plate"),
                    L(fr, (7, 8), (lf + 0.5, lf + 1.25), (8 + 0.25, 8 + 1.25), "steel", name="breaker_boss"),
                    L(fr, (7.25, 7.75), (lf + 1.25, lf + 1.75), (8 + 0.5, 8 + 3.0), "steel", name="breaker_handle", hit=False)]
            return out
        p = self.p
        lo, mid, hi = self.lats()
        r = p["r"]
        wide = min(8, 8 + p["pitch"] + r + 0.5) - 8
        tank = [(wide, r + 0.75), (wide - 1, r + 1.75)]
        sw = min(8, 8 + p["pitch"] + r) - 8
        step = [(sw, r + 0.5), (sw - 1, r + 1.25)]
        for lc in (lo, hi):
            out += self.flange(fr, (0, 2), lc, hide=["a-"])
            out += self.tube(fr, (2, 3), lc)
            out += self.tube(fr, (12, 14), lc)
            out += self.flange(fr, (14, 16), lc, hide=["a+"])
        out += self.tube(fr, (0, 3), mid, hide=["a-"])
        out += self.tube(fr, (12, 16), mid, hide=["a+"])
        out += self.rounded(fr, (3, 10), tank, "breaker_tank")
        out += self.rounded(fr, (10, 12), step, "step")
        top = 8 + r + 1.75
        out += [L(fr, (4.5, 8.5), (5, 11), (top, top + 2.5), "steel", name="mechanism"),
                L(fr, (6, 7), (11, 11.75), (top + 0.75, top + 1.75), "steel", name="breaker_boss"),
                L(fr, (6.25, 6.75), (11.75, 12.25), (top + 1, top + 3.25), "steel", name="breaker_handle", hit=False)]
        side = 8 + wide
        out += [L(fr, (3.75, 8.75), (side, side + 0.25), (8.75, 10.25), "alu", name="rating_plate_from",
                  deco={"l+": ("plate", n_from, "@a")}, hit=False),
                L(fr, (3.75, 8.75), (side, side + 0.25), (6.0, 7.5), "alu", name="rating_plate_to",
                  deco={"l+": ("plate", n_to, "@a")}, hit=False)]
        return out

    def rounded(self, fr, a, profile, name):
        """A rounded-rectangle enclosure along a: profile [(lateral half, height half), ...] stepping in at the corners."""
        out = []
        rmax = max(max(q) for q in profile)
        for (l0, l1), (h0, h1) in columns(profile):
            out.append(L(fr, a, (8 + l0, 8 + l1), (8 + h0, 8 + h1), "alu", kind="round", name=name, raxis="a", rc=(0, 8, 8), rmax=rmax))
        return out

    def build(self):
        facings = dict(self.domain.props)["facing"]
        for f in facings:
            if self.kind == "feed_in" and f == "up":
                for axis in ("x", "z"):
                    self.add(f"feed_in_up" + ("" if axis == "x" else "_z"), self.feed_in(f, axis),
                             (lambda a: lambda st: st["facing"] == "up" and st["axis"] == a)(axis))
            elif self.kind == "feed_in":
                self.add(f"feed_in_{f}", self.feed_in(f), (lambda f: lambda st: st["facing"] == f)(f))
            elif self.kind == "tap_off":
                self.add(f"tap_off_{f}", self.tap_body(f), (lambda f: lambda st: st["facing"] == f)(f))
                for closed in (True, False):
                    self.add(f"flag_{f}_{'closed' if closed else 'open'}", self.tap_flag(f, closed),
                             (lambda f, c: lambda st: st["facing"] == f and st["closed"] == c)(f, closed))
                self.add(f"tripped_{f}", self.tap_tripped(f), (lambda f: lambda st: st["facing"] == f and st["tripped"])(f))
            else:
                self.add(f"reducer_{f}", self.reducer(f), (lambda f: lambda st: st["facing"] == f)(f))


def fitting_domain(kind):
    if kind == "feed_in":
        # axis: facing up, the way the busway it rises to runs.
        return Domain([("facing", ("north", "east", "south", "west", "up")), ("axis", ("x", "z"))])
    if kind == "tap_off":
        return Domain([("facing", DIRS), ("closed", (False, True)), ("tripped", (False, True))])
    return Domain([("facing", DIRS)])


# --- cable trays ----------------------------------------------------------------------------------------------------

TB = 5.0  # every tray's rails stand on y = 5 (a riser's back is 5 px off its wall)
TRAY = {
    "cable_tray": dict(l0=2.0, l1=14.0, lips="out", H=3.0, rw=1.0, rt=1.0, k=5.0, k2=2.0, slot=2.0, slots=6),
    "heavy_duty_cable_tray": dict(l0=2.0, l1=14.0, lips="out", H=5.0, rw=1.5, rt=1.5, k=5.5, k2=3.0, slot=2.0, slots=6),
    "wide_cable_tray": dict(l0=0.5, l1=15.5, lips="in", H=7.5, rw=1.0, rt=1.0, k=4.5, k2=4.0, slot=2.5, slots=6),
}
RUNGS = (2.0, 6.0, 10.0, 14.0)
MOUNTS = ("none", "ceiling", "floor", "north", "east", "south", "west")
FRAMES.update({
    # Risers: a up, h the depth off the wall the ladder's back is toward, l across it.
    "rwest": ((2, False), (0, False), (1, False)),
    "reast": ((2, True), (0, False), (1, False)),
    "rnorth": ((1, False), (0, False), (2, False)),
    "rsouth": ((1, False), (0, False), (2, True)),
})
CORNERS = {"ne": ("north", "east"), "nw": ("north", "west"), "se": ("south", "east"), "sw": ("south", "west")}


def tray_domain():
    return Domain([(d, (False, True)) for d in DIRS] + [("mount", MOUNTS)])


def tray_eff(st):
    """The horizontal connections a tray's flat part is drawn for: a lone or missing connection reads as a straight."""
    ch = [d for d in HORIZ if st[d]]
    if len(ch) == 0:
        return frozenset(("east", "west"))
    if len(ch) == 1:
        return frozenset((ch[0], OPP[ch[0]]))
    return frozenset(ch)


def tray_class(st):
    """("flat", eff) (no vertical connection), ("riser", F) (only vertical: the ladder's back toward F, the wall its
    mount names, else north), ("velbow", h, v) (one of each), or ("mixed", eff, F) (the flat part and a riser)."""
    ch = [d for d in HORIZ if st[d]]
    cv = [d for d in ("up", "down") if st[d]]
    F = st["mount"] if st["mount"] in HORIZ else "north"
    if not cv:
        return ("flat", tray_eff(st))
    if not ch:
        return ("riser", F)
    if len(ch) == 1 and len(cv) == 1:
        return ("velbow", ch[0], cv[0])
    return ("mixed", tray_eff(st), F)


def flat_eff(st):
    c = tray_class(st)
    return c[1] if c[0] in ("flat", "mixed") else None


def rung_kind(eff):
    """Which rungs a flat part has: across x (a straight along x, a tee through x, a cross), across z, or an elbow's."""
    if len(eff) == 4 or {"east", "west"} <= eff:
        return "x"
    if {"north", "south"} <= eff:
        return "z"
    for c, (d1, d2) in CORNERS.items():
        if eff == frozenset((d1, d2)):
            return "elbow_" + c
    return None


class Tray:
    def __init__(self, block):
        self.block = block
        self.t = TRAY[block]
        self.models, self.parts = {}, []

    def add(self, name, boxes, pred):
        if name not in self.models:
            self.models[name] = cull(boxes)
        self.parts.append((name, pred))

    def lips_l(self, side):
        t = self.t
        if t["lips"] == "out":
            return (t["l1"] + 0.5, t["l1"] + 1.5) if side > 0 else (t["l0"] - 1.5, t["l0"] - 0.5)
        return (t["l1"] - 1.0, t["l1"]) if side > 0 else (t["l0"], t["l0"] + 1.0)

    def web_l(self, side):
        t = self.t
        return (t["l1"], t["l1"] + 0.5) if side > 0 else (t["l0"] - 0.5, t["l0"])

    def rung_h(self):
        t = self.t
        base = TB if t["lips"] == "out" else TB + 0.5
        return (base, base + t["rt"])

    def rail(self, fr, a, side):
        t = self.t
        H = t["H"]
        out = [L(fr, a, self.web_l(side), (TB, TB + H), "galv", name="rail_web", cont=["a"])]
        for hr in ((TB, TB + 0.5), (TB + H - 0.5, TB + H)):
            out.append(L(fr, a, self.lips_l(side), hr, "galv", name="rail_lip", cont=["a"]))
        return out

    def rungs(self, fr, positions=RUNGS):
        t = self.t
        return [L(fr, (c - t["rw"] / 2, c + t["rw"] / 2), (t["l0"], t["l1"]), self.rung_h(), "galv", name="rung")
                for c in positions]

    def straight(self, fr):
        return self.rail(fr, (0, 16), 1) + self.rail(fr, (0, 16), -1) + self.rungs(fr)

    # Flat parts ----------------------------------------------------------------------------------------------------
    def side_rail(self, d, trim0, trim1):
        """The rail along side d of the block, trimmed for a mitre at its a = 0 / a = 16 end."""
        k = self.t["k"]
        if d in ("north", "south"):
            fr, side = Frame("east"), (-1 if d == "north" else 1)
        else:
            fr, side = Frame("south"), (1 if d == "west" else -1)
        return self.rail(fr, (k if trim0 else 0, 16 - k if trim1 else 16), side)

    def mitre_se(self):
        """The 45 degree rail across the south-east corner (south and east both closed); the others mirror it. Its top
        and bottom stand 0.05 px proud of the straight rails' so the overlap at the mitres never shares a plane."""
        t = self.t
        k = t["k"]
        wc = t["l1"] + 0.25
        p1, p2 = (16 - k, wc), (wc, 16 - k)
        mx, mz = (p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2
        length = math.hypot(p1[0] - p2[0], p1[1] - p2[1]) + 0.7
        H = t["H"]
        rot = dict(origin=[round(mx, 4), TB + H / 2, round(mz, 4)], axis="y", angle=45)
        out = [B([mx - length / 2, TB - 0.05, mz - 0.25], [mx + length / 2, TB + H + 0.05, mz + 0.25], "galv", name="mitre_web", rot=rot)]
        lz = (mz + 0.25, mz + 1.25) if t["lips"] == "out" else (mz - 1.25, mz - 0.25)
        for h0, h1 in ((TB - 0.05, TB + 0.5), (TB + H - 0.5, TB + H + 0.05)):
            out.append(B([mx - length / 2 + 0.6, h0, lz[0]], [mx + length / 2 - 0.6, h1, lz[1]], "galv", name="mitre_lip", rot=rot))
        return out

    def inner_se(self):
        """Where the east and south arms' inner rails meet in the south-east corner."""
        t = self.t
        H, l1 = t["H"], t["l1"]
        if t["lips"] == "in":
            return [B([l1, TB, l1], [16, TB + H, 16], "galv", name="corner_post")]
        out = [B([l1, TB, l1], [16, TB + H, l1 + 0.5], "galv", name="rail_web"),
               B([l1, TB, l1 + 0.5], [l1 + 0.5, TB + H, 16], "galv", name="rail_web")]
        for h0, h1 in ((TB, TB + 0.5), (TB + H - 0.5, TB + H)):
            out.append(B([l1 + 0.5, h0, l1 + 0.5], [16, h1, l1 + 1.5], "galv", name="rail_lip"))
            out.append(B([l1 + 0.5, h0, l1 + 1.5], [l1 + 1.5, h1, 16], "galv", name="rail_lip"))
        return out

    def elbow_rungs_se(self):
        """An elbow's rungs fan out from the inner corner (the east and south arms meeting in the south-east): five at
        22.5 degree steps, from 3 px out to the outer rails and their mitre."""
        t = self.t
        P = (t["l1"], t["l1"])
        k, l0, rw = t["k"], t["l0"], t["rw"]
        out = []
        for i, deg in enumerate((0, 22.5, 45, 67.5, 90)):
            # Each a fiftieth of a px above the last (and off the rails' underside): where they overlap near the corner, or
            # run into a rail, no two faces lie in one plane to fight.
            h0, h1 = (h + 0.02 * (i + 1) for h in self.rung_h())
            a = math.radians(deg)
            u = (-math.sin(a), -math.cos(a))  # 0: north, 90: west
            ends = []
            if u[1] < -1e-9:
                ends.append((P[1] - l0) / -u[1])
            if u[0] < -1e-9:
                ends.append((P[0] - l0) / -u[0])
            s = u[0] + u[1]
            if s < -1e-9:
                ends.append((P[0] + P[1] - (k + l0 + 0.15)) / -s)
            r0, r1 = 3.0, min(ends)
            mx, mz = P[0] + u[0] * (r0 + r1) / 2, P[1] + u[1] * (r0 + r1) / 2
            ln = r1 - r0
            if deg == 0:
                out.append(B([mx - rw / 2, h0, mz - ln / 2], [mx + rw / 2, h1, mz + ln / 2], "galv", name="rung"))
                continue
            if deg == 90:
                out.append(B([mx - ln / 2, h0, mz - rw / 2], [mx + ln / 2, h1, mz + rw / 2], "galv", name="rung"))
                continue
            # A rung along x turned theta about y points (cos t, -sin t); along z, (sin t, cos t).
            best = None
            for base in ("x", "z"):
                for th in (-45, -22.5, 22.5, 45):
                    r = math.radians(th)
                    v = (math.cos(r), -math.sin(r)) if base == "x" else (math.sin(r), math.cos(r))
                    err = min(abs(v[0] - u[0]) + abs(v[1] - u[1]), abs(v[0] + u[0]) + abs(v[1] + u[1]))
                    if best is None or err < best[0] - 1e-9:
                        best = (err, base, th)
            _, base, th = best
            rot = dict(origin=[round(mx, 4), h0, round(mz, 4)], axis="y", angle=th)
            if base == "x":
                out.append(B([mx - ln / 2, h0, mz - rw / 2], [mx + ln / 2, h1, mz + rw / 2], "galv", name="rung", rot=rot))
            else:
                out.append(B([mx - rw / 2, h0, mz - ln / 2], [mx + rw / 2, h1, mz + ln / 2], "galv", name="rung", rot=rot))
        return out

    def corner_mirror(self, boxes, corner):
        """South-east-built boxes moved to another corner."""
        out = boxes
        if "w" in corner:
            out = [mirror_box(b, 0) for b in out]
        if "n" in corner:
            out = [mirror_box(b, 2) for b in out]
        return out

    # Vertical elbows -----------------------------------------------------------------------------------------------
    def velbow(self, h, v):
        """The ladder bending between the horizontal arm h and up / down, built in h's frame (a toward h). Up: the
        riser's back stands 5 px off the far side (outer corner at a = h = 5, mitred); down: its back faces h at
        a = 11 and the outer corner is the top far one."""
        t = self.t
        fr = Frame(h)
        H, k2, b = t["H"], t["k2"], TB
        r0, r1 = self.rung_h()
        lift = 0.05
        out = []
        for side in (1, -1):
            web, lip = self.web_l(side), self.lips_l(side)
            webx = (web[0] - lift, web[1] + lift)
            lipx = (lip[0] - lift, lip[1] + lift)
            if v == "up":
                out += [L(fr, (b + k2, 16), web, (b, b + H), "galv", name="rail_web", cont=["a"]),
                        L(fr, (b, b + H), web, (b + H, 16), "galv", name="rail_web", cont=["h"]),
                        L(fr, (b, b + k2), web, (b + k2, b + H), "galv", name="rail_web")]
                c = (b + 0.75 * k2, b + 0.75 * k2)
                out.append(L(fr, (c[0] - k2 * 0.7071 - 0.1, c[0] + k2 * 0.7071 + 0.1), webx, (c[1] - k2 * 0.3536, c[1] + k2 * 0.3536),
                             "galv", name="mitre_web", rot=("l", 45, (c[0], 8, c[1]))))
                out += [L(fr, (b + k2, 16), lip, (b, b + 0.5), "galv", name="rail_lip", cont=["a"]),
                        L(fr, (b, b + 0.5), lip, (b + k2, 16), "galv", name="rail_lip", cont=["h"]),
                        L(fr, (b + H - 0.5, 16), lip, (b + H - 0.5, b + H), "galv", name="rail_lip", cont=["a"]),
                        L(fr, (b + H - 0.5, b + H), lip, (b + H, 16), "galv", name="rail_lip", cont=["h"])]
                m0 = (b + k2 / 2 + 0.1768, b + k2 / 2 + 0.1768)
                out.append(L(fr, (m0[0] - k2 * 0.7071 + 0.3, m0[0] + k2 * 0.7071 - 0.3), lipx, (m0[1] - 0.25, m0[1] + 0.25), "galv",
                             name="mitre_lip", rot=("l", 45, (m0[0], 8, m0[1]))))
            else:
                c0, c1 = 16 - b - H, 16 - b
                out += [L(fr, (c0 + k2, 16), web, (b, b + H), "galv", name="rail_web", cont=["a"]),
                        L(fr, (c0, c0 + k2), web, (b, b + H - k2), "galv", name="rail_web"),
                        L(fr, (c0, c1), web, (0, b), "galv", name="rail_web", cont=["h"])]
                c = (c0 + 0.75 * k2, b + H - 0.75 * k2)
                out.append(L(fr, (c[0] - k2 * 0.7071 - 0.1, c[0] + k2 * 0.7071 + 0.1), webx, (c[1] - k2 * 0.3536, c[1] + k2 * 0.3536),
                             "galv", name="mitre_web", rot=("l", -45, (c[0], 8, c[1]))))
                out += [L(fr, (c1, 16), lip, (b, b + 0.5), "galv", name="rail_lip", cont=["a"]),
                        L(fr, (c1 - 0.5, c1), lip, (0, b + 0.5), "galv", name="rail_lip", cont=["h"]),
                        L(fr, (c0 + k2, 16), lip, (b + H - 0.5, b + H), "galv", name="rail_lip", cont=["a"]),
                        L(fr, (c0, c0 + 0.5), lip, (0, b + H - k2), "galv", name="rail_lip", cont=["h"])]
                m0 = (c0 + k2 / 2 + 0.1768, b + H - k2 / 2 - 0.1768)
                out.append(L(fr, (m0[0] - k2 * 0.7071 + 0.3, m0[0] + k2 * 0.7071 - 0.3), lipx, (m0[1] - 0.25, m0[1] + 0.25), "galv",
                             name="mitre_lip", rot=("l", -45, (m0[0], 8, m0[1]))))
        rw, rt = t["rw"], t["rt"]
        l0, l1 = t["l0"], t["l1"]
        off = r0 - b  # rungs on inward lips sit 0.5 off the back
        if v == "up":
            for c in RUNGS:
                if c - rw / 2 >= b + H:
                    out.append(L(fr, (c - rw / 2, c + rw / 2), (l0, l1), (r0, r1), "galv", name="rung"))
                    out.append(L(fr, (b + off, b + off + rt), (l0, l1), (c - rw / 2, c + rw / 2), "galv", name="rung"))
            m = (b + k2 / 2 + 0.7071 * (off + rt / 2), b + k2 / 2 + 0.7071 * (off + rt / 2))
            out.append(L(fr, (m[0] - rw / 2, m[0] + rw / 2), (l0, l1), (m[1] - rt / 2, m[1] + rt / 2), "galv", name="rung",
                         rot=("l", 45, (m[0], 8, m[1]))))
        else:
            c1 = 16 - b
            for c in RUNGS:
                if c - rw / 2 >= c1:
                    out.append(L(fr, (c - rw / 2, c + rw / 2), (l0, l1), (r0, r1), "galv", name="rung"))
                if c + rw / 2 <= b:
                    out.append(L(fr, (c1 - off - rt, c1 - off), (l0, l1), (c - rw / 2, c + rw / 2), "galv", name="rung"))
            out.append(L(fr, (c1 - off - rt, c1 - off), (l0, l1), (r0, r1), "galv", name="rung"))
        return out

    # Mounts --------------------------------------------------------------------------------------------------------
    def hanger(self, fr, kind):
        """A trapeze hanger (a strut under the tray, threaded rods up to the block above) or a floor stand (the strut
        on two legs). Standard / heavy: the rods pass outside the rails' lips; wide: inside its rail webs."""
        t = self.t
        b = TB
        span = (max(0.0, t["l0"] - 1.5), min(16.0, t["l1"] + 1.5))
        out = [L(fr, (7.25, 8.75), span, (b - 1.5, b), "steel", name="strut")]
        if kind == "ceiling":
            rods = [(0.0, 0.5), (15.5, 16.0)] if t["lips"] == "out" else [(0.05, 0.45), (15.55, 15.95)]
            for lr in rods:
                out.append(L(fr, (7.75, 8.25), lr, (b - 1.5, 15.5), "steel", kind="round", raxis="h", rc=(8, sum(lr) / 2, 0),
                             rmax=0.25, name="threaded_rod"))
                out.append(L(fr, (7.25, 8.75), (max(0.0, lr[0] - 0.5), min(16.0, lr[1] + 0.5)), (15.5, 16), "steel", name="anchor"))
                out.append(L(fr, (7.5, 8.5), (max(0.0, lr[0] - 0.25), min(16.0, lr[1] + 0.25)), (b - 2.25, b - 1.5), "steel",
                             name="nut", hit=False))
        else:
            for lr in ((0.75, 2.25), (13.75, 15.25)):
                out.append(L(fr, (7.5, 8.5), lr, (0.5, b - 1.5), "steel", name="leg"))
                out.append(L(fr, (6.75, 9.25), (lr[0] - 0.5, lr[1] + 0.5), (0, 0.5), "steel", name="foot"))
        return out

    def wall_bracket(self, d):
        """An L bracket off the wall on side d: a plate on the wall, an arm under the tray across the block, a brace."""
        fr = Frame(OPP[d])
        b = TB
        out = [L(fr, (0, 0.75), (6.5, 9.5), (0.5, b), "steel", name="wall_plate"),
               L(fr, (0.75, 15.5), (7.25, 8.75), (b - 1.5, b), "steel", name="bracket_arm")]
        c = (2.25, 2.25)
        ln = 4.2
        out.append(L(fr, (c[0] - ln / 2, c[0] + ln / 2), (7.5, 8.5), (c[1] - 0.4, c[1] + 0.4), "steel", name="brace",
                     rot=("l", -45, (c[0], 8, c[1]))))
        return out

    def standoffs(self, F):
        """A riser's wall channels and the standoffs out to its rails, on the wall F."""
        fr = Frame("r" + F)
        t = self.t
        if t["lips"] == "out":
            chans = [(t["l0"] - 1.75, t["l0"] - 0.25), (t["l1"] + 0.25, t["l1"] + 1.75)]
        else:
            chans = [(t["l0"] + 0.25, t["l0"] + 1.75), (t["l1"] - 1.75, t["l1"] - 0.25)]
        out = []
        for lr in chans:
            out.append(L(fr, (0, 16), lr, (0, 0.5), "steel", name="wall_channel", cont=["a"]))
            for a in (3.0, 11.0):
                out.append(L(fr, (a, a + 1.5), (lr[0] + 0.5, lr[1] - 0.5), (0.5, TB), "steel", name="standoff"))
        return out

    def build(self):
        cls = tray_class
        dom = tray_domain()
        for d in HORIZ:
            ends = {"north": ("nw", "ne"), "south": ("sw", "se"), "west": ("nw", "sw"), "east": ("ne", "se")}[d]
            for trim0 in (False, True):
                for trim1 in (False, True):
                    def pred(st, d=d, trim0=trim0, trim1=trim1, ends=ends):
                        eff = flat_eff(st)
                        if eff is None or d in eff:
                            return False
                        t0 = all(x not in eff for x in CORNERS[ends[0]])
                        t1 = all(x not in eff for x in CORNERS[ends[1]])
                        return t0 == trim0 and t1 == trim1
                    if dom.when(pred) is None:
                        continue
                    self.add(f"rail_{d}" + ("_a" if trim0 else "") + ("_b" if trim1 else ""), self.side_rail(d, trim0, trim1), pred)
        for c, (d1, d2) in CORNERS.items():
            self.add(f"mitre_{c}", self.corner_mirror(self.mitre_se(), c),
                     (lambda d1, d2: lambda st: flat_eff(st) is not None and d1 not in flat_eff(st) and d2 not in flat_eff(st))(d1, d2))
            self.add(f"inner_{c}", self.corner_mirror(self.inner_se(), c),
                     (lambda d1, d2: lambda st: flat_eff(st) is not None and d1 in flat_eff(st) and d2 in flat_eff(st))(d1, d2))
            self.add(f"rungs_elbow_{c}", self.corner_mirror(self.elbow_rungs_se(), c),
                     (lambda c: lambda st: flat_eff(st) is not None and rung_kind(flat_eff(st)) == "elbow_" + c)(c))
        self.add("rungs_x", self.rungs(Frame("east")), lambda st: flat_eff(st) is not None and rung_kind(flat_eff(st)) == "x")
        self.add("rungs_z", self.rungs(Frame("south")), lambda st: flat_eff(st) is not None and rung_kind(flat_eff(st)) == "z")
        for F in HORIZ:
            fr = Frame("r" + F)
            self.add(f"riser_{F}", self.straight(fr), (lambda F: lambda st: cls(st) == ("riser", F))(F))
            self.add(f"standoffs_{F}", self.standoffs(F), (lambda F: lambda st: cls(st) == ("riser", F) and st["mount"] == F)(F))
            # A riser through a flat junction: its arms above and below the flat part only.
            top = TB + self.t["H"]
            up = self.rail(fr, (top, 16), 1) + self.rail(fr, (top, 16), -1) + self.rungs(fr, [c for c in RUNGS if c - self.t["rw"] / 2 >= top])
            down = self.rail(fr, (0, TB), 1) + self.rail(fr, (0, TB), -1) + self.rungs(fr, [c for c in RUNGS if c + self.t["rw"] / 2 <= TB])
            self.add(f"riser_{F}_up", up, (lambda F: lambda st: cls(st)[0] == "mixed" and cls(st)[2] == F and st["up"])(F))
            self.add(f"riser_{F}_down", down, (lambda F: lambda st: cls(st)[0] == "mixed" and cls(st)[2] == F and st["down"])(F))
        for h in HORIZ:
            for v in ("up", "down"):
                self.add(f"elbow_{h}_{v}", self.velbow(h, v), (lambda h, v: lambda st: cls(st) == ("velbow", h, v))(h, v))
        for axis in ("x", "z"):
            fr = Frame("east" if axis == "x" else "south")
            for kind in ("ceiling", "floor"):
                self.add(f"{kind}_{axis}", self.hanger(fr, kind),
                         (lambda axis, kind: lambda st: cls(st)[0] == "flat" and st["mount"] == kind
                          and (rung_kind(cls(st)[1]) == "z") == (axis == "z"))(axis, kind))
        for d in HORIZ:
            self.add(f"wall_{d}", self.wall_bracket(d), (lambda d: lambda st: cls(st)[0] == "flat" and st["mount"] == d)(d))


# --- cables ---------------------------------------------------------------------------------------------------------

def cable_texture(tier):
    """A cable's jacket seen along its length: 16 rows round the 8-sided tube (2 rows a face, face 0 on top, then
    round toward the cable's right), each face one flat jacket tone for how it faces the light (the top lightest);
    MV, HV and EHV carry their tier stripe along the top face (its lit row, then its dark row). Columns run along the
    cable at 1 texel per px and repeat every 16 px."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    tones = [4, 3, 2, 1, 0, 1, 2, 3]
    for k in range(8):
        for j in (2 * k, 2 * k + 1):
            for i in range(16):
                px[i, j] = JACKET[tones[k]]
    if tier in STRIPE:
        for i in range(16):
            px[i, 0] = STRIPE[tier][0]
            px[i, 1] = STRIPE[tier][1]
    return img


# --- output ---------------------------------------------------------------------------------------------------------

def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=False)


def merged(models, names):
    out = []
    for n in names:
        for b in models[n]:
            nb = Box(b.frm, b.to, b.mat, kind=b.kind, name=b.name, hide=b.hidden, deco=b.deco, cont=b.cont, raxis=b.raxis, rc=b.rc,
                     rmax=b.rmax, tone=b.tone, solid=b.solid, hit=b.hit)
            nb.rot = b.rot
            out.append(nb)
    return out


WHEN = {}


def dull(img):
    """A damaged busway's sheet: every tone sooted and dulled toward a burnt dark grey (the same pixels, so its UVs hold)."""
    out = img.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a:
                grey = (r * 30 + g * 59 + b * 11) // 100
                px[x, y] = ((grey * 45 + 34 * 55) // 100, (grey * 45 + 31 * 55) // 100, (grey * 45 + 29 * 55) // 100, a)
    return out


def with_state(when, key, value):
    """A multipart condition with one more property: added to a plain one, joined by AND to an OR."""
    if not when:
        return {key: value}
    if "OR" in when:
        return {"AND": [when, {key: value}]}
    return {**when, key: value}


def write_block(repo, block, family, models, parts, domain, inventory, cache_key, damage=False):
    """Writes a block's sheet, piece models, blockstate, item definition and shapes. With damage (a busway run), a dull
    copy of each sheet and piece model, the blockstate choosing them by `damaged`."""
    assets = os.path.join(repo, "src/main/resources/assets", NS)
    data = os.path.join(repo, "src/main/resources/data", NS)
    models = dict(models)
    models = {"inventory": merged(models, inventory), **models}
    for k, (names, placed, img) in enumerate(pack_sheets(models)):
        stem = block if k == 0 else f"{block}_{k}"
        tex = f"{NS}:block/{family}/{stem}"
        save(img, os.path.join(assets, "textures/block", family, stem + ".png"))
        if damage:
            save(dull(img), os.path.join(assets, "textures/block", family, stem + "_damaged.png"))
        for name in names:
            write_json(os.path.join(assets, "models/block", family, block, name + ".json"),
                       model_json(name, models[name], placed, tex, DISPLAY if name == "inventory" else None))
            if damage and name != "inventory":
                write_json(os.path.join(assets, "models/block", family, block, name + "_damaged.json"),
                           model_json(name, models[name], placed, tex + "_damaged", None))
    multipart, shape_parts = [], []
    for name, pred in parts:
        key = (cache_key, name)
        if key not in WHEN:
            WHEN[key] = domain.when(pred)
        when = WHEN[key]
        if when is None:
            continue
        if damage:
            for value, suffix in (("false", ""), ("true", "_damaged")):
                multipart.append({"when": with_state(when, "damaged", value), "apply": {"model": f"{NS}:block/{family}/{block}/{name}{suffix}"}})
        else:
            entry = {"apply": {"model": f"{NS}:block/{family}/{block}/{name}"}}
            if when:
                entry = {"when": when, **entry}
            multipart.append(entry)
        shape_parts.append({"when": when, "part": name} if when else {"part": name})
    write_json(os.path.join(assets, "blockstates", block + ".json"), {"multipart": multipart})
    write_json(os.path.join(assets, "items", block + ".json"),
               {"model": {"type": "minecraft:model", "model": f"{NS}:block/{family}/{block}/inventory"}})
    write_json(os.path.join(data, "block_shapes", block + ".json"),
               {"units": "px, block-local (origin the block's minimum corner); boxes are [x0, y0, z0, x1, y1, z1]",
                "parts": {name: hitboxes(models[name]) for name in dict(parts)},
                "multipart": shape_parts})
    return len(models)


def all_blocks():
    out = []
    for tier in TIERS:
        out += [f"{tier}_busway_{s}" for s in SIZES]
        out += [f"{tier}_busway_feed_in", f"{tier}_busway_tap_off", f"{tier}_busway_reducer_8w_4w", f"{tier}_busway_reducer_4w_2w"]
    return out + list(TRAY)


def main():
    repo = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else "."
    assets = os.path.join(repo, "src/main/resources/assets", NS)
    only = [a.split("=", 1)[1] for a in sys.argv if a.startswith("--only=")]
    count, blocks = 0, []
    if not only:
        # The folders this generator owns whole are rebuilt from empty, so no piece it stops writing lingers.
        import shutil
        for rel in ("models/block/busway", "models/block/cable_tray", "textures/block/busway", "textures/block/cable_tray",
                    "textures/block/cable"):
            shutil.rmtree(os.path.join(assets, rel), ignore_errors=True)
        shutil.rmtree(os.path.join(repo, "src/main/resources/data", NS, "block_shapes"), ignore_errors=True)
    for tier in TIERS:
        if only and tier not in only[0].split(",") and "trays" != only[0]:
            continue
        if only and only[0] == "trays":
            break
        for size in SIZES:
            bw = Busway(tier, size)
            bw.build()
            count += write_block(repo, f"{tier}_busway_{size}", "busway", bw.models, bw.parts, busway_domain(),
                                 ["straight_x", "cap_x_east", "cap_x_west"], ("busway", tier), damage=True)
            blocks.append(f"{tier}_busway_{size}")
        for kind, inv in (("feed_in", ["feed_in_east"]), ("tap_off", ["tap_off_north", "flag_north_closed"])):
            ft = Fitting(tier, kind)
            ft.build()
            count += write_block(repo, f"{tier}_busway_{kind}", "busway", ft.models, ft.parts, ft.domain, inv, (kind, tier))
            blocks.append(f"{tier}_busway_{kind}")
        for big, small in (("8w", "4w"), ("4w", "2w")):
            ft = Fitting(tier, "reducer", (big, small))
            ft.build()
            count += write_block(repo, f"{tier}_busway_reducer_{big}_{small}", "busway", ft.models, ft.parts, ft.domain,
                                 ["reducer_east"], ("reducer", tier))
            blocks.append(f"{tier}_busway_reducer_{big}_{small}")
    if not only or only[0] == "trays":
        for block in TRAY:
            tr = Tray(block)
            tr.build()
            count += write_block(repo, block, "cable_tray", tr.models, tr.parts, tray_domain(),
                                 ["rail_north", "rail_south", "rungs_x"], ("tray", block))
            blocks.append(block)
    for tier in TIERS:
        save(cable_texture(tier), os.path.join(assets, "textures/block/cable", tier + ".png"))
    print(f"wrote {len(blocks)} blocks ({count} models) and the cable jackets")
    if "--check" in sys.argv:
        sys.exit(1 if check(repo, blocks) else 0)


# --- check ----------------------------------------------------------------------------------------------------------

def domain_for(block):
    if block in TRAY:
        return tray_domain()
    for kind in ("feed_in", "tap_off", "reducer"):
        if kind in block:
            return fitting_domain(kind)
    return busway_domain()


def possible(block, st):
    """States the Java never sets: a tray mounted toward a side it's connected on (mount names a solid neighbour)."""
    if block in TRAY:
        return not (st["mount"] in HORIZ and st[st["mount"]])
    return True


def check(repo, blocks):
    """For every block and every blockstate: each face of every applied model samples exactly its own size in texels
    (1 texel per px on the 64 px sheet), and no two faces facing the same way overlap in one plane (rotated elements,
    whose faces lie off the block's planes, are left to the eye)."""
    assets = os.path.join(repo, "src/main/resources/assets", NS)
    problems = []
    cache = {}

    def faces_of(ref):
        if ref in cache:
            return cache[ref]
        model = json.load(open(os.path.join(assets, "models", ref.split(":")[1] + ".json")))
        out = []
        for e in model.get("elements", []):
            frm, to = e["from"], e["to"]
            for face, f in e["faces"].items():
                w, h = face_size(frm, to, face)
                u0, v0, u1, v1 = f["uv"]
                tw, th = (u1 - u0) * SHEET / 16, (v1 - v0) * SHEET / 16
                if abs(tw - w) > 1e-3 or abs(th - h) > 1e-3:
                    problems.append(f"{ref} {e['name']} {face}: {w}x{h} px samples {tw}x{th} texels")
                if "rotation" in e:
                    continue
                axis, sign = FACE_AXIS[face]
                plane = to[axis] if sign > 0 else frm[axis]
                others = [i for i in range(3) if i != axis]
                out.append((face, round(plane, 4), [(frm[i], to[i]) for i in others], ref.split("/")[-1] + ":" + e["name"]))
        cache[ref] = out
        return out

    states = 0
    for block in blocks:
        bs = json.load(open(os.path.join(assets, "blockstates", block + ".json")))
        dom = domain_for(block)
        seen = set()
        for st in dom.dicts():
            if not possible(block, st):
                continue
            refs = tuple(p["apply"]["model"] for p in bs["multipart"] if matches(p.get("when"), st))
            if refs in seen:
                continue
            seen.add(refs)
            states += 1
            groups = {}
            for ref in refs:
                for f in faces_of(ref):
                    groups.setdefault((f[0], f[1]), []).append(f)
            for fs in groups.values():
                for i in range(len(fs)):
                    for j in range(i + 1, len(fs)):
                        a, b = fs[i], fs[j]
                        if all(min(p[1], q[1]) - max(p[0], q[0]) > 1e-4 for p, q in zip(a[2], b[2])):
                            problems.append(f"{block}: {a[3]} and {b[3]} {a[0]} faces overlap at {a[1]}")
    uniq = sorted(set(problems))
    for p in uniq[:40]:
        print("check:", p)
    per = {}
    for p in uniq:
        per[p.split(":")[0]] = per.get(p.split(":")[0], 0) + 1
    for k, v in sorted(per.items()):
        print(f"check: {k}: {v}")
    print(f"check: {states} distinct blockstates over {len(blocks)} blocks, {len(uniq)} problems")
    return uniq


if __name__ == "__main__":
    main()
