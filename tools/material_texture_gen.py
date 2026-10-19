"""The addon's own material, aluminium from bauxite: its ore, raw ore, dust, ingot and blocks, recoloured from Arcforge's
nickel set so they sit in Arcforge's vanilla-style material language (docs/TEXTURE_STYLE.md, "Natural materials").

    python tools/material_texture_gen.py <addon repo>

Reads the nickel textures out of libs/arcforge-*.jar (beside this tool) and writes (assets/arcforgetransmission/textures/):
    block/ore/bauxite_ore.png, block/ore/deepslate_bauxite_ore.png   the ore overlays (on stone / deepslate in the model)
    item/raw_bauxite.png, block/raw_bauxite_block.png               the raw ore: red-brown, iron-stained bauxite
    item/aluminium_dust.png, item/aluminium_ingot.png, block/aluminium_block.png   aluminium: a cool, light silver

Each coloured pixel keeps its place in the source's own order of light to dark: the source's distinct coloured tones,
sorted by luminance, are spread evenly over the new ramp (one new tone each, so no two tones merge and no pixel becomes
isolated). Neutral greys (the ore overlays' stone shadows) stay as they are, and a pixel left isolated by the source takes
its neighbours' tone. Deterministic: the same jar gives the same
textures every run.
"""
import glob
import io
import os
import sys
import zipfile

from PIL import Image

NS = "arcforgetransmission"
HERE = os.path.dirname(os.path.abspath(__file__))


def hx(s):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16))


# Dark to light.
BAUXITE = [hx(c) for c in ("#4A1D10", "#6B2D18", "#8A4122", "#A6572F", "#BE7040", "#D08B57", "#E2AA79")]
ALUMINIUM = [hx(c) for c in ("#5E656C", "#7D858C", "#99A1A8", "#B3BAC0", "#CBD1D6", "#E0E4E8", "#F2F4F6")]

# Output name: (source in the Arcforge jar, ramp).
OUTPUTS = {
    "block/ore/bauxite_ore.png": ("block/ore/nickel_ore.png", BAUXITE),
    "block/ore/deepslate_bauxite_ore.png": ("block/ore/deepslate_nickel_ore.png", BAUXITE),
    "item/raw_bauxite.png": ("item/raw_nickel.png", BAUXITE),
    "block/raw_bauxite_block.png": ("block/raw_nickel_block.png", BAUXITE),
    "item/aluminium_dust.png": ("item/nickel_dust.png", ALUMINIUM),
    "item/aluminium_ingot.png": ("item/nickel_ingot.png", ALUMINIUM),
    "block/aluminium_block.png": ("block/nickel_block.png", ALUMINIUM),
}


def luminance(c):
    return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]


def coloured(c):
    """A tone of the metal or ore (not a neutral grey stone shadow, and not the item outline)."""
    return max(c[:3]) - min(c[:3]) > 12


def ramp_at(ramp, t):
    """The ramp's colour at t (0 dark to 1 light), between its stops."""
    x = t * (len(ramp) - 1)
    i = min(int(x), len(ramp) - 2)
    f = x - i
    return tuple(round(ramp[i][k] + (ramp[i + 1][k] - ramp[i][k]) * f) for k in range(3))


def recolour(img, ramp):
    img = img.convert("RGBA")
    px = img.load()
    tones = sorted({px[x, y][:3] for y in range(img.height) for x in range(img.width) if px[x, y][3] and coloured(px[x, y])},
                   key=lambda c: (luminance(c), c))
    lums = [luminance(c) for c in tones]
    lo, hi = (min(lums), max(lums)) if lums else (0, 1)
    mapping = {}
    used = set()
    for c, lum in zip(tones, lums):
        t = 0.0 if hi == lo else (lum - lo) / (hi - lo)
        new = ramp_at(ramp, t)
        # Keep every source tone distinct.
        while new in used:
            new = (new[0], new[1], min(255, new[2] + 1))
        used.add(new)
        mapping[c] = new
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a and (r, g, b) in mapping:
                px[x, y] = mapping[(r, g, b)] + (255,)
    # Flat tones, no speckle (the addon's audit): a pixel unlike its four neighbours, all alike, takes their tone.
    for y in range(1, img.height - 1):
        for x in range(1, img.width - 1):
            c = px[x, y]
            n = [px[x - 1, y], px[x + 1, y], px[x, y - 1], px[x, y + 1]]
            if c[3] and all(m[3] for m in n) and all(m == n[0] for m in n) and c != n[0]:
                px[x, y] = n[0]
    return img


def arcforge_jar():
    jars = sorted(glob.glob(os.path.join(HERE, "..", "libs", "arcforge-*.jar")))
    if not jars:
        sys.exit("material_texture_gen: no libs/arcforge-*.jar beside the tools")
    return jars[-1]


def main():
    repo = sys.argv[1] if len(sys.argv) > 1 else "."
    tex = os.path.join(repo, "src/main/resources/assets", NS, "textures")
    with zipfile.ZipFile(arcforge_jar()) as jar:
        for out, (source, ramp) in OUTPUTS.items():
            img = Image.open(io.BytesIO(jar.read("assets/arcforge/textures/" + source)))
            path = os.path.join(tex, out)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            recolour(img, ramp).save(path, optimize=False)
    print("wrote the aluminium and bauxite textures")


if __name__ == "__main__":
    main()
