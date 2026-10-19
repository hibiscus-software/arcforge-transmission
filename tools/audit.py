"""Audits the addon's generated art: every generator rebuilds byte-identically, every texture comes from one, and the
textures and element models follow Arcforge's docs/TEXTURE_STYLE.md as this addon uses it.

    python tools/audit.py [<addon repo>]

Exits non-zero (listing every problem) when anything fails. Checks:
  rebuild     each generator in GENERATORS, run into an empty folder, writes exactly the files in the repo, byte for byte
  manifest    every PNG under textures/ is written by some generator (nothing hand-edited)
  png         RGBA; sizes (16x16 block and item sprites, 64x64 model sheets, the tower sheet 256x512); alpha only 0 or
              255 (no half-transparent pixels)
  speckle     block sprites (16x16) have no isolated pixel unlike all four neighbours that are all alike (flat
              tones: no noise or dithering); the large-model sheets are exempt (their grain is controlled)
  models      element models: UVs inside the sheet; every face laid at 1 texel per px (its UV rect the face's size);
              no face samples a clear texel; no two faces facing the same way overlapping in one plane (z-fighting)
  attach      each pin insulator's attach point is its model's top, centred
"""
import filecmp
import hashlib
import json
import os
import subprocess
import sys
import tempfile

from PIL import Image

NS = "arcforgetransmission"
GENERATORS = ["tools/transmission_tower_texture_gen.py", "tools/tower_attach_groups_gen.py", "tools/grid_block_model_gen.py",
              "tools/wire_texture_gen.py", "tools/material_texture_gen.py"]
SHEET_SIZES = {"block/transmission_tower/towers.png": (256, 512)}
# Isolated pixels that are deliberate details, by texture: the rivet shadows of Arcforge's machine casing.
INTENTIONAL = {"block/wall_bushing.png": {(3, 3), (12, 3), (3, 12), (12, 12)}}

problems = []


def problem(message):
    problems.append(message)


def files_under(root):
    out = []
    for base, _, names in os.walk(root):
        for name in names:
            out.append(os.path.relpath(os.path.join(base, name), root).replace(os.sep, "/"))
    return sorted(out)


def rebuild(repo):
    """Runs each generator into an empty folder; returns every file they wrote (paths relative to the repo)."""
    written = set()
    for generator in GENERATORS:
        with tempfile.TemporaryDirectory() as tmp:
            # Twice: the second run must match the first, and both the repo.
            runs = []
            for i in range(2):
                out = os.path.join(tmp, f"run{i}")
                os.makedirs(out)
                result = subprocess.run([sys.executable, os.path.join(repo, generator), out], capture_output=True, text=True)
                if result.returncode != 0:
                    problem(f"rebuild: {generator} failed: {result.stderr.strip()}")
                    break
                runs.append(out)
            if len(runs) < 2:
                continue
            for rel in files_under(runs[0]):
                written.add(rel)
                a, b = os.path.join(runs[0], rel), os.path.join(runs[1], rel)
                if not os.path.exists(b) or not same(a, b):
                    problem(f"rebuild: {generator} isn't deterministic: {rel}")
                committed = os.path.join(repo, rel)
                if not os.path.exists(committed):
                    problem(f"rebuild: {generator} writes {rel}, which isn't in the repo")
                elif not same(a, committed):
                    problem(f"rebuild: {rel} differs from what {generator} writes (sha {sha(committed)} vs {sha(a)})")
    return written


def same(a, b):
    """Two outputs match: PNGs pixel for pixel (encoders differ between Pillow versions), text with line endings
    normalised (git may check text out with CRLF), anything else byte for byte."""
    if a.endswith(".png"):
        ia, ib = Image.open(a).convert("RGBA"), Image.open(b).convert("RGBA")
        return ia.size == ib.size and ia.tobytes() == ib.tobytes()
    with open(a, "rb") as fa, open(b, "rb") as fb:
        return fa.read().replace(b"\r\n", b"\n") == fb.read().replace(b"\r\n", b"\n")


def sha(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()[:12]


def check_textures(repo, written):
    tex = os.path.join(repo, "src/main/resources/assets", NS, "textures")
    for rel in files_under(tex):
        if not rel.endswith(".png"):
            continue
        full = f"src/main/resources/assets/{NS}/textures/{rel}"
        if full not in written:
            problem(f"manifest: textures/{rel} isn't written by any generator")
        img = Image.open(os.path.join(tex, rel))
        if img.mode != "RGBA":
            problem(f"png: textures/{rel} is {img.mode}, not RGBA")
            img = img.convert("RGBA")
        size = img.size
        expected = SHEET_SIZES.get(rel)
        if expected:
            if size != expected:
                problem(f"png: textures/{rel} is {size}, expected {expected}")
        elif size not in ((16, 16), (64, 64)):
            problem(f"png: textures/{rel} is {size}: sprites are 16x16, model sheets 64x64")
        px = img.load()
        w, h = size
        half = [(x, y) for y in range(h) for x in range(w) if 0 < px[x, y][3] < 255]
        if half:
            problem(f"png: textures/{rel} has {len(half)} half-transparent pixels (first {half[0]})")
        if size == (16, 16) and rel.startswith("block/"):
            for y in range(1, h - 1):
                for x in range(1, w - 1):
                    c = px[x, y]
                    n = [px[x - 1, y], px[x + 1, y], px[x, y - 1], px[x, y + 1]]
                    if c[3] and all(m[3] for m in n) and all(m == n[0] for m in n) and c != n[0]:
                        if (x, y) not in INTENTIONAL.get(rel, ()):
                            problem(f"speckle: textures/{rel} has an isolated pixel at ({x}, {y})")


FACE_AXES = {"north": ("z", 0), "south": ("z", 1), "west": ("x", 0), "east": ("x", 1), "down": ("y", 0), "up": ("y", 1)}


def face_rect(frm, to, face):
    """The face's plane coordinate and its 2D extent (in the other two axes)."""
    axis, side = FACE_AXES[face]
    i = "xyz".index(axis)
    plane = (frm, to)[side][i]
    others = [j for j in range(3) if j != i]
    return plane, [(frm[j], to[j]) for j in others]


def face_size(frm, to, face):
    dx, dy, dz = (to[0] - frm[0], to[1] - frm[1], to[2] - frm[2])
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy), "up": (dx, dz), "down": (dx, dz)}[face]


def check_models(repo):
    root = os.path.join(repo, "src/main/resources/assets", NS, "models/block")
    for rel in files_under(root):
        model = json.load(open(os.path.join(root, rel)))
        elements = model.get("elements")
        if not elements:
            continue
        sheet_id = model["textures"].get("sheet")
        if not sheet_id:
            continue
        sheet = Image.open(os.path.join(repo, "src/main/resources/assets", NS, "textures", sheet_id.split(":")[1] + ".png")).convert("RGBA")
        sw, sh = sheet.size
        px = sheet.load()
        planes = []
        for e in elements:
            frm, to = e["from"], e["to"]
            for face, f in e["faces"].items():
                u0, v0, u1, v1 = f["uv"]
                if min(u0, v0, u1, v1) < 0 or max(u0, v0, u1, v1) > 16:
                    problem(f"models: {rel} {e['name']} {face} UV outside the sheet")
                w, h = face_size(frm, to, face)
                tw, th = (u1 - u0) * sw / 16, (v1 - v0) * sh / 16
                if abs(tw - w) > 1e-3 or abs(th - h) > 1e-3:
                    problem(f"models: {rel} {e['name']} {face} is {w}x{h} px but samples {tw}x{th} texels")
                # Every texel the face covers (sampled at texel centres) is painted.
                x0, y0 = u0 * sw / 16, v0 * sh / 16
                for ty in range(int(th + 0.999)):
                    for tx in range(int(tw + 0.999)):
                        x, y = int(x0 + min(tx + 0.5, tw - 0.01)), int(y0 + min(ty + 0.5, th - 0.01))
                        if px[x, y][3] == 0:
                            problem(f"models: {rel} {e['name']} {face} samples a clear texel at ({x}, {y})")
                            break
                    else:
                        continue
                    break
                if not e.get("rotation"):
                    plane, extent = face_rect(frm, to, face)
                    planes.append((face, plane, extent, e["name"]))
        for i in range(len(planes)):
            for j in range(i + 1, len(planes)):
                a, b = planes[i], planes[j]
                if a[0] != b[0] or abs(a[1] - b[1]) > 1e-6:
                    continue
                overlap = all(min(p[1], q[1]) - max(p[0], q[0]) > 1e-6 for p, q in zip(a[2], b[2]))
                if overlap:
                    problem(f"models: {rel} {a[3]} and {b[3]} {a[0]} faces overlap in one plane (z-fighting)")


def check_attach(repo):
    data = os.path.join(repo, "src/main/resources/data", NS, "attach_groups")
    for tier in ("lv", "mv", "hv", "ehv"):
        point = json.load(open(os.path.join(data, f"pin_insulator_{tier}.json")))["groups"][0]["terminals"][0]["pos"]
        model = json.load(open(os.path.join(repo, "src/main/resources/assets", NS, "models/block/pin_insulator", tier + ".json")))
        top = max(e["to"][1] for e in model["elements"])
        if point != [8.0, top, 8.0]:
            problem(f"attach: pin_insulator_{tier} attaches at {point}, its model's top is (8, {top}, 8)")


def main():
    repo = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
    written = rebuild(repo)
    check_textures(repo, written)
    check_models(repo)
    check_attach(repo)
    if problems:
        print(f"audit: {len(problems)} problem(s)")
        for p in problems:
            print("  " + p)
        sys.exit(1)
    print(f"audit: OK ({len(GENERATORS)} generators rebuilt identically, {len(written)} files)")


if __name__ == "__main__":
    main()
