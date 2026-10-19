"""Writes the addon's particle sprites (textures/particle/) and their particle definitions (particles/*.json), in the
house style of Arcforge's docs/TEXTURE_STYLE.md: flat tones with hard edges, no gradients, no dithering, no noise; the
colours from its accent tables.

    python tools/transmission_particle_gen.py <addon repo>

  corona     a faint blue-violet mote (the Link ramp's glow tones, section 4), three frames shrinking from a 5 px cross
             to a point: corona's glow on a wet EHV conductor
  arc_flash  an arc's flash (the arc cyan ramp round a white core, section 4), four frames: a 13 px starburst falling
             back to a small star; 16 x 16, drawn full-bright
  spark      a spark (the hot orange ramp, white-hot core), four frames from a 3 px cross to a point; 8 x 8
"""
import json
import os
import sys

from PIL import Image

NS = "arcforgetransmission"


def hx(c):
    c = c.lstrip("#")
    return (int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), 255)


WHITE = hx("#FFFFFF")
# Link (purple) glow tones and the arc cyan, TEXTURE_STYLE section 4.
VIOLET = [hx("#B4C4F6"), hx("#7C9CEC"), hx("#636CD0")]
ARC = [hx("#B8F2EA"), hx("#5FD4C4"), hx("#2F6E68")]
HOT = [hx("#FFB02E"), hx("#F26A16"), hx("#C73A0E")]


def sprite(size, cells):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    for (x, y), colour in cells.items():
        px[x, y] = colour
    return img


def cross(cx, cy, arm, tones):
    """A plus: its centre the first tone, each step out the next (the last for the rest)."""
    cells = {(cx, cy): tones[0]}
    for d in range(1, arm + 1):
        tone = tones[min(d, len(tones) - 1)]
        for dx, dy in ((d, 0), (-d, 0), (0, d), (0, -d)):
            cells[(cx + dx, cy + dy)] = tone
    return cells


def corona_frames():
    frames = []
    for arm in (2, 1, 0):
        cells = cross(3, 3, arm, VIOLET)
        if arm == 2:
            for dx, dy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
                cells[(3 + dx, 3 + dy)] = VIOLET[2]
        frames.append(sprite(8, cells))
    return frames


def flash_frames():
    frames = []
    for arm, diagonal in ((6, 4), (5, 3), (3, 2), (2, 0)):
        cells = cross(7, 7, arm, [WHITE, WHITE, ARC[0], ARC[0], ARC[1], ARC[1], ARC[2]])
        for d in range(1, diagonal + 1):
            tone = [WHITE, ARC[0], ARC[1], ARC[2]][min(d, 3)]
            for dx, dy in ((d, d), (-d, d), (d, -d), (-d, -d)):
                cells[(7 + dx, 7 + dy)] = tone
        # A solid white core, 3 x 3, while it's at its brightest.
        if arm >= 5:
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    cells[(7 + dx, 7 + dy)] = WHITE
        frames.append(sprite(16, cells))
    return frames


def spark_frames():
    frames = []
    for arm, core in ((2, WHITE), (1, WHITE), (1, HOT[0]), (0, HOT[1])):
        frames.append(sprite(8, cross(3, 3, arm, [core, HOT[0], HOT[1]])))
    return frames


PARTICLES = {"corona": corona_frames, "arc_flash": flash_frames, "spark": spark_frames}


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    assets = os.path.join(root, "src/main/resources/assets", NS)
    os.makedirs(os.path.join(assets, "textures/particle"), exist_ok=True)
    os.makedirs(os.path.join(assets, "particles"), exist_ok=True)
    for name, make in PARTICLES.items():
        names = []
        for i, frame in enumerate(make()):
            frame.save(os.path.join(assets, "textures/particle", f"{name}_{i}.png"), optimize=False)
            names.append(f"{NS}:{name}_{i}")
        with open(os.path.join(assets, "particles", name + ".json"), "w", newline="\n") as f:
            json.dump({"textures": names}, f, indent=2)
            f.write("\n")
    print("wrote " + ", ".join(PARTICLES))


if __name__ == "__main__":
    main()
