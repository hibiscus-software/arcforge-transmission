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
  registry    every block the code registers has a blockstate, a loot table (but the invisible parts) and a
              name; every item an item definition and a name
  references  every model, texture and parent a blockstate, item definition or model names exists (this addon's own;
              Arcforge's and vanilla's against their jars when they're at hand)
  lang        every complete translation key in the code is in en_us.json, and every key in en_us.json is used
  recipes     every item a recipe names is registered (this addon's) or exists (Arcforge's, vanilla's), every shaped
              key symbol is used in its pattern and every pattern symbol has a key
  sounds      every sound event the code registers is in sounds.json, every sounds.json entry has its .ogg and its
              subtitle in en_us.json, and every .ogg under sounds/ is written by a generator (sounds are compared by
              length, channels and rate, not bytes: Vorbis encoding needn't be byte-identical)
  particles   every particle the code registers has its definition, and every sprite a definition names exists; particle
              sprites are 8x8 or 16x16
"""
import filecmp
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile

from PIL import Image

NS = "arcforgetransmission"
GENERATORS = ["tools/transmission_tower_texture_gen.py", "tools/tower_attach_groups_gen.py", "tools/grid_block_model_gen.py",
              "tools/wire_texture_gen.py", "tools/material_texture_gen.py", "tools/transmission_sound_gen.py",
              "tools/transmission_particle_gen.py", "tools/factory_block_model_gen.py"]
SHEET_SIZES = {"block/transmission_tower/towers.png": (256, 512)}
# Isolated pixels that are deliberate details, by texture: the rivet shadows of Arcforge's machine casing.
INTENTIONAL = {"block/wall_bushing.png": {(3, 3), (12, 3), (3, 12), (12, 12)}}

# The invisible blocks a structure stands in: no item, no loot table of their own.
PARTS = {"transmission_tower_part", "cable_termination_part", "switchgear_section_part"}

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
    if a.endswith(".ogg"):
        import soundfile
        sa, sb = soundfile.info(a), soundfile.info(b)
        return (sa.frames, sa.channels, sa.samplerate) == (sb.frames, sb.channels, sb.samplerate)
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
        elif rel.startswith("particle/"):
            if size not in ((8, 8), (16, 16)):
                problem(f"png: textures/{rel} is {size}: particle sprites are 8x8 or 16x16")
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


TIERS = ["lv", "mv", "hv", "ehv"]
# VoltageTier.insulatable(): LV and MV.
INSULATABLE = ["lv", "mv"]
# Blocks registered per tier but not at LV: a lineup's transformer section steps down to the tier below.
NOT_LV = ["_transformer_section"]


def java_sources(repo):
    out = {}
    for path in glob.glob(os.path.join(repo, "src/main/java/**/*.java"), recursive=True):
        out[path] = open(path, encoding="utf-8").read()
    return out


def registered(repo, sources):
    """The blocks and items the code registers: (blocks, items)."""
    blocks, items = set(), set()
    for text in sources.values():
        blocks.update(re.findall(r'register(?:Simple)?Block\("([a-z0-9_]+)"', text))
        items.update(re.findall(r'register(?:Simple)?Item\("([a-z0-9_]+)"', text))
        for suffix in re.findall(r'BLOCKS\.registerBlock\(tier\.id\(\) \+ "([a-z0-9_]+)"', text):
            blocks.update(t + suffix for t in TIERS if not (t == "lv" and suffix in NOT_LV))
        for suffix in re.findall(r'ITEMS\.registerItem\(tier\.id\(\) \+ "([a-z0-9_]+)"', text):
            items.update(t + suffix for t in (INSULATABLE if "insulated" in suffix else TIERS))
    tiers = open(os.path.join(repo, "src/main/java/net/zagdrath/arcforgetransmission/tower/TowerTier.java"), encoding="utf-8").read()
    towers = re.findall(r'^    [A-Z0-9_]+\("([a-z0-9_]+)", Kind\.', tiers, re.M)
    blocks.update(towers)
    # Every block has its item, but the invisible parts (a tower's, a tall cable termination's).
    items.update(b for b in blocks if b not in PARTS)
    return blocks, items


def check_registry(repo, blocks, items, lang):
    assets = os.path.join(repo, "src/main/resources/assets", NS)
    data = os.path.join(repo, "src/main/resources/data", NS)
    for block in sorted(blocks):
        if not os.path.exists(os.path.join(assets, "blockstates", block + ".json")):
            problem(f"registry: block {block} has no blockstate")
        if block not in PARTS and not os.path.exists(os.path.join(data, "loot_table/blocks", block + ".json")):
            problem(f"registry: block {block} has no loot table")
        if f"block.{NS}.{block}" not in lang:
            problem(f"registry: block {block} has no name (block.{NS}.{block})")
    for item in sorted(items):
        if not os.path.exists(os.path.join(assets, "items", item + ".json")):
            problem(f"registry: item {item} has no item definition (items/{item}.json)")
        if f"item.{NS}.{item}" not in lang and f"block.{NS}.{item}" not in lang:
            problem(f"registry: item {item} has no name")
    for path in glob.glob(os.path.join(assets, "blockstates", "*.json")) + glob.glob(os.path.join(assets, "items", "*.json")):
        name = os.path.splitext(os.path.basename(path))[0]
        if name not in (blocks if "blockstates" in path else items):
            problem(f"registry: {os.path.relpath(path, assets)} is for nothing registered")


class Jars:
    """Arcforge's and vanilla's assets, from their jars when they're at hand (None when not: those checks are skipped)."""

    def __init__(self, repo):
        self.names = {}
        for ns, patterns in (("arcforge", ["libs/arcforge-*.jar"]), ("minecraft", [".gradle/caches/minecraft/versions/*/client.jar"])):
            for pattern in patterns:
                for jar in glob.glob(os.path.join(repo, pattern)):
                    with zipfile.ZipFile(jar) as z:
                        self.names.setdefault(ns, set()).update(z.namelist())

    def has(self, ns, path):
        names = self.names.get(ns)
        return None if names is None else path in names


def resource(repo, jars, ns, kind, path):
    """Whether assets/<ns>/<kind>/<path> exists (True/False), or None when it can't be told."""
    rel = f"assets/{ns}/{kind}/{path}"
    if ns == NS:
        return os.path.exists(os.path.join(repo, "src/main/resources", rel))
    return jars.has(ns, rel)


def split(ref, default="minecraft"):
    ns, _, path = ref.partition(":") if ":" in ref else (default, "", ref)
    return ns, path


def check_references(repo, jars):
    assets = os.path.join(repo, "src/main/resources/assets", NS)

    def model_exists(ref, where):
        if ref.startswith("builtin/"):
            return
        ns, path = split(ref)
        if resource(repo, jars, ns, "models", path + ".json") is False:
            problem(f"references: {where} names model {ref}, which doesn't exist")

    def walk_models(node, where):
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "model" and isinstance(value, str):
                    model_exists(value, where)
                else:
                    walk_models(value, where)
        elif isinstance(node, list):
            for value in node:
                walk_models(value, where)

    for path in glob.glob(os.path.join(assets, "blockstates", "*.json")) + glob.glob(os.path.join(assets, "items", "*.json")):
        walk_models(json.load(open(path)), os.path.relpath(path, assets).replace(os.sep, "/"))
    for path in glob.glob(os.path.join(assets, "models", "**", "*.json"), recursive=True):
        where = os.path.relpath(path, assets).replace(os.sep, "/")
        model = json.load(open(path))
        if "parent" in model:
            model_exists(model["parent"], where)
        for key, ref in model.get("textures", {}).items():
            if ref.startswith("#"):
                continue
            ns, tex = split(ref)
            if resource(repo, jars, ns, "textures", tex + ".png") is False:
                problem(f"references: {where} texture {key} is {ref}, which doesn't exist")


def check_lang(repo, sources, lang, blocks, items):
    literal = re.compile(r'"((?:block|item|message|hud|tooltip|port_status|pin_insulator_mode|itemGroup|death\.attack|subtitles)\.' + NS + r'\.[a-z0-9_.]+)"')
    joined = re.compile(r'"([a-zA-Z_]+)\.?" \+ (?:m|MODID|ArcforgeTransmission\.MODID) \+ "\.([a-z0-9_.]+)"\s*[,)]')
    fragments = set()
    for path, text in sources.items():
        for key in literal.findall(text):
            if key not in lang and not key.endswith("."):
                problem(f"lang: {os.path.basename(path)} uses {key}, which en_us.json doesn't have")
        for prefix, rest in joined.findall(text):
            key = f"{prefix.rstrip('.')}.{NS}.{rest}"
            if key not in lang:
                problem(f"lang: {os.path.basename(path)} uses {key}, which en_us.json doesn't have")
        fragments.update(re.findall(r'"([^"\\]*)"', text))
    sounds_json = os.path.join(repo, "src/main/resources/assets", NS, "sounds.json")
    if os.path.exists(sounds_json):
        for entry in json.load(open(sounds_json)).values():
            if entry.get("subtitle"):
                fragments.add(entry["subtitle"])
    # Keys the code builds from an enum: every port status has its words.
    solver = re.sub(r"//[^\n]*", "", open(os.path.join(repo, "src/main/java/net/zagdrath/arcforgetransmission/grid/FlowSolver.java"), encoding="utf-8").read())
    statuses = re.search(r"enum PortStatus \{([^}]*)\}", solver).group(1)
    for status in re.findall(r"\b([A-Z][A-Z0-9_]*)\b", statuses):
        if f"port_status.{NS}.{status.lower()}" not in lang:
            problem(f"lang: port status {status} has no port_status.{NS}.{status.lower()}")
    pieces = set()
    for f in fragments:
        pieces.update(p for p in re.split(r"[.]", f) if p)
    for key in lang:
        prefix, _, rest = key.partition("." + NS + ".")
        if not rest:
            problem(f"lang: {key} isn't in this addon's namespace")
            continue
        if prefix == "block" and rest in blocks or prefix == "item" and rest in items:
            continue
        # A death message's ".player" form (the game adds it), and an item's info tooltip (ItemInfoTooltips: by its id,
        # tiered ones without the tier).
        if rest.endswith(".player") and f"{prefix}.{NS}.{rest[:-len('.player')]}" in lang:
            continue
        if prefix == "tooltip" and rest.startswith("info."):
            name = rest[len("info."):]
            if any(i == name or i.endswith("_" + name) for i in items):
                continue
        # Used if the code spells it out, or builds it: every dotted part of its name is a piece of some string in the code
        # (an enum's or a tier's name counts: the code lower-cases those).
        parts = rest.split(".")
        if rest in fragments or any(f.endswith("." + rest) or f.endswith(rest) and f.startswith(".") for f in fragments):
            continue
        if all(p in pieces or p in ENUM_WORDS for p in parts):
            continue
        problem(f"lang: {key} isn't used anywhere in the code")


ENUM_WORDS = set()


def enum_words(sources):
    for text in sources.values():
        text = re.sub(r"//[^\n]*", "", text)
        for body in re.findall(r"enum \w+[^{]*\{([^;}]*)", text):
            for name in re.findall(r"\b([A-Z][A-Z0-9_]+)\b", body):
                ENUM_WORDS.add(name.lower())


def check_recipes(repo, jars, items):
    root = os.path.join(repo, "src/main/resources/data", NS, "recipe")

    def item_exists(ref, where):
        if ref.startswith("#"):
            ns, tag = split(ref[1:])
            if ns == NS and not glob.glob(os.path.join(repo, "src/main/resources/data", NS, "tags/item", tag + ".json")):
                problem(f"recipes: {where} names tag {ref}, which doesn't exist")
            return
        ns, path = split(ref)
        if ns == NS:
            if path not in items:
                problem(f"recipes: {where} names {ref}, which isn't registered")
        elif jars.has(ns, f"assets/{ns}/items/{path}.json") is False:
            problem(f"recipes: {where} names {ref}, which {ns} doesn't have")

    def ingredients(node, where):
        if isinstance(node, str):
            item_exists(node, where)
        elif isinstance(node, list):
            for value in node:
                ingredients(value, where)
        elif isinstance(node, dict):
            for key in ("item", "id"):
                if isinstance(node.get(key), str):
                    item_exists(node[key], where)
            if isinstance(node.get("tag"), str):
                item_exists("#" + node["tag"], where)

    for path in glob.glob(os.path.join(root, "**", "*.json"), recursive=True):
        where = os.path.relpath(path, root).replace(os.sep, "/")
        recipe = json.load(open(path))
        for key in ("ingredient", "ingredients", "base", "addition", "template"):
            if key in recipe:
                ingredients(recipe[key], where)
        if "key" in recipe:
            used = set("".join(recipe.get("pattern", []))) - {" "}
            keys = set(recipe["key"])
            for symbol in sorted(keys - used):
                problem(f"recipes: {where} key {symbol!r} isn't in its pattern")
            for symbol in sorted(used - keys):
                problem(f"recipes: {where} pattern symbol {symbol!r} has no key")
            for value in recipe["key"].values():
                ingredients(value, where)
        result = recipe.get("result")
        if isinstance(result, dict) and isinstance(result.get("id"), str):
            item_exists(result["id"], where)
        elif isinstance(result, str):
            item_exists(result, where)


def check_sounds(repo, sources, lang, written):
    """Every sound event the code registers (TransmissionSounds: register("name")) is in sounds.json; every entry has its
    files and its subtitle; every .ogg is a generator's."""
    assets = os.path.join(repo, "src/main/resources/assets", NS)
    path = os.path.join(assets, "sounds.json")
    events = json.load(open(path)) if os.path.exists(path) else {}
    registered = set()
    for name, text in sources.items():
        if name.endswith("TransmissionSounds.java"):
            registered.update(re.findall(r'register\("([a-z0-9_]+)"\)', text))
    for event in sorted(registered - set(events)):
        problem(f"sounds: {event} is registered but not in sounds.json")
    for event, entry in events.items():
        if event not in registered:
            problem(f"sounds: sounds.json has {event}, which the code doesn't register")
        subtitle = entry.get("subtitle")
        if not subtitle:
            problem(f"sounds: {event} has no subtitle")
        elif subtitle not in lang:
            problem(f"sounds: {event}'s subtitle {subtitle} isn't in en_us.json")
        for sound in entry.get("sounds", []):
            ref = sound["name"] if isinstance(sound, dict) else sound
            ns, file = split(ref)
            if ns == NS and not os.path.exists(os.path.join(assets, "sounds", file + ".ogg")):
                problem(f"sounds: {event} names {ref}, which has no sounds/{file}.ogg")
    for rel in files_under(os.path.join(assets, "sounds")):
        if rel.endswith(".ogg") and f"src/main/resources/assets/{NS}/sounds/{rel}" not in written:
            problem(f"manifest: sounds/{rel} isn't written by any generator")


def check_particles(repo, sources):
    """Every particle the code registers (TransmissionParticles) has particles/<name>.json, and its sprites exist."""
    assets = os.path.join(repo, "src/main/resources/assets", NS)
    registered = set()
    for name, text in sources.items():
        if name.endswith("TransmissionParticles.java"):
            registered.update(re.findall(r'PARTICLES\.register\("([a-z0-9_]+)"', text))
    for particle in sorted(registered):
        path = os.path.join(assets, "particles", particle + ".json")
        if not os.path.exists(path):
            problem(f"particles: {particle} has no particles/{particle}.json")
            continue
        for ref in json.load(open(path)).get("textures", []):
            ns, tex = split(ref)
            if ns == NS and not os.path.exists(os.path.join(assets, "textures/particle", tex + ".png")):
                problem(f"particles: {particle} names {ref}, which has no textures/particle/{tex}.png")


def main():
    repo = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
    written = rebuild(repo)
    check_textures(repo, written)
    check_models(repo)
    check_attach(repo)
    sources = java_sources(repo)
    enum_words(sources)
    lang = json.load(open(os.path.join(repo, "src/main/resources/assets", NS, "lang/en_us.json"), encoding="utf-8"))
    blocks, items = registered(repo, sources)
    jars = Jars(repo)
    check_registry(repo, blocks, items, lang)
    check_references(repo, jars)
    check_lang(repo, sources, lang, blocks, items)
    check_recipes(repo, jars, items)
    check_sounds(repo, sources, lang, written)
    check_particles(repo, sources)
    if problems:
        print(f"audit: {len(problems)} problem(s)")
        for p in problems:
            print("  " + p)
        sys.exit(1)
    print(f"audit: OK ({len(GENERATORS)} generators rebuilt identically, {len(written)} files; {len(blocks)} blocks, {len(items)} items, "
          f"{len(lang)} lang keys)")


if __name__ == "__main__":
    main()
