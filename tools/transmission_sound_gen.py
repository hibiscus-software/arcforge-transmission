"""Writes the addon's sounds (assets/arcforgetransmission/sounds/*.ogg, mono, 44.1 kHz, Vorbis) and sounds.json, as
Arcforge's tools/diesel_sound_gen.py and reactor_sound_gen.py write theirs: seeded, so a rebuild matches.

    python tools/transmission_sound_gen.py <addon repo>       (needs numpy and soundfile)

  transformer_hum   a seamless 2 s loop: mains hum at twice the line frequency (60 Hz line, so 120 Hz, from the core's
                    magnetostriction) with its even harmonics falling away, a slow beat between two near partials and a
                    faint cooling-fan hiss. TransformerHums bends its volume and pitch with the transformer's load.
  corona_buzz       a seamless 3 s loop: the soft crackling buzz of corona on a wet EHV line: sparse ticks of high-passed
                    noise, bunched on the voltage's peaks (120 a second), over a thin hiss.
  arc_crack         an electric arc's sharp crack: a broadband snap and a short crackling tail.
  fuse_pop          a fuse blowing: a dull pop with a short ring.
  breaker_clunk     a breaker's mechanism: a heavy low clunk and the ring of its steel.

Filters work in the frequency domain over the whole clip, so a loop's filtering wraps round and its seam doesn't click.
"""
import json
import os
import sys

import numpy as np
import soundfile as sf

NS = "arcforgetransmission"
RATE = 44100


def shaped(x, lo=None, hi=None, order=2):
    """x band-limited (Butterworth-like magnitude, applied in the frequency domain: circular)."""
    spectrum = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1.0 / RATE)
    gain = np.ones_like(f)
    if hi is not None:
        gain /= np.sqrt(1.0 + (f / hi) ** (2 * order))
    if lo is not None:
        with np.errstate(divide="ignore"):
            gain /= np.sqrt(1.0 + (lo / np.maximum(f, 1e-9)) ** (2 * order))
    return np.fft.irfft(spectrum * gain, len(x))


def normalised(x, peak):
    x = x - x.mean()
    return x * (peak / max(1e-9, np.abs(x).max()))


def hum():
    seconds = 2.0
    n = int(RATE * seconds)
    t = np.arange(n) / RATE
    rng = np.random.default_rng(1201)
    # 120 Hz and its harmonics (each a whole number of cycles in the loop), falling off, the odd ones of the line weaker.
    partials = [(120, 1.0), (240, 0.55), (360, 0.32), (480, 0.22), (600, 0.12), (720, 0.08), (960, 0.04)]
    x = np.zeros(n)
    for f, a in partials:
        x += a * np.sin(2 * np.pi * f * t + rng.uniform(0, 2 * np.pi))
    # A slow beat: a partial 0.5 Hz off the second harmonic (one cycle a loop).
    x += 0.12 * np.sin(2 * np.pi * 240.5 * t)
    # The fans: a faint low-passed hiss.
    x += 0.05 * normalised(shaped(rng.normal(0, 1, n), lo=200, hi=1500), 1.0)
    x = np.tanh(1.2 * x)
    return normalised(x, 0.7)


def corona():
    seconds = 3.0
    n = int(RATE * seconds)
    t = np.arange(n) / RATE
    rng = np.random.default_rng(2402)
    ticks = np.zeros(n)
    # About 220 ticks a second, most near the voltage's peaks (twice a cycle of 60 Hz).
    count = int(220 * seconds)
    peaks = rng.integers(0, int(120 * seconds), count) / 120.0
    at = (peaks + rng.normal(0, 0.0012, count)) % seconds
    for i, when in enumerate(at):
        start = int(when * RATE)
        length = int(RATE * rng.uniform(0.0006, 0.002))
        burst = rng.normal(0, 1, length) * np.exp(-np.arange(length) / (length / 4)) * rng.uniform(0.3, 1.0)
        idx = (start + np.arange(length)) % n
        ticks[idx] += burst
    ticks = shaped(ticks, lo=1800, hi=9000)
    hiss = shaped(rng.normal(0, 1, n), lo=3000, hi=10000)
    buzz = 0.25 * np.sin(2 * np.pi * 120 * t) + 0.1 * np.sin(2 * np.pi * 240 * t)
    x = normalised(ticks, 1.0) + 0.18 * normalised(hiss, 1.0) + 0.12 * buzz
    return normalised(x, 0.6)


def envelope(n, attack, decay):
    t = np.arange(n) / RATE
    return np.minimum(1.0, t / max(attack, 1e-6)) * np.exp(-t / decay)


def crack():
    n = int(RATE * 0.7)
    rng = np.random.default_rng(3603)
    snap = shaped(rng.normal(0, 1, n), lo=400, hi=12000) * envelope(n, 0.0004, 0.018)
    tail = np.zeros(n)
    for _ in range(40):
        start = int(rng.uniform(0.01, 0.35) * RATE)
        length = int(RATE * rng.uniform(0.001, 0.004))
        burst = rng.normal(0, 1, length) * np.exp(-np.arange(length) / (length / 3))
        end = min(n, start + length)
        tail[start:end] += burst[:end - start] * rng.uniform(0.1, 0.5) * np.exp(-start / RATE / 0.15)
    tail = shaped(tail, lo=1500, hi=10000)
    t = np.arange(n) / RATE
    thump = np.sin(2 * np.pi * 90 * t * (1 - 2 * t)) * envelope(n, 0.001, 0.04)
    x = normalised(snap, 1.0) + 0.35 * normalised(tail, 1.0) + 0.4 * thump
    return normalised(np.tanh(1.5 * x), 0.9)


def pop():
    n = int(RATE * 0.4)
    rng = np.random.default_rng(4804)
    t = np.arange(n) / RATE
    burst = shaped(rng.normal(0, 1, n), lo=150, hi=3000) * envelope(n, 0.0008, 0.012)
    ring = np.sin(2 * np.pi * 310 * t) * envelope(n, 0.001, 0.05) + 0.4 * np.sin(2 * np.pi * 620 * t) * envelope(n, 0.001, 0.03)
    x = normalised(burst, 1.0) + 0.5 * ring
    return normalised(np.tanh(1.3 * x), 0.8)


def clunk():
    n = int(RATE * 0.9)
    rng = np.random.default_rng(6005)
    t = np.arange(n) / RATE
    thud = np.sin(2 * np.pi * 70 * t * (1 - 1.5 * t)) * envelope(n, 0.002, 0.07)
    click = shaped(rng.normal(0, 1, n), lo=800, hi=8000) * envelope(n, 0.0003, 0.006)
    ring = sum(a * np.sin(2 * np.pi * f * t) * envelope(n, 0.001, d) for f, a, d in ((830, 0.5, 0.18), (1370, 0.35, 0.12), (2140, 0.2, 0.08)))
    rattle = shaped(rng.normal(0, 1, n), lo=300, hi=2500) * envelope(n, 0.01, 0.05)
    x = normalised(thud, 1.0) + 0.6 * normalised(click, 1.0) + 0.25 * ring + 0.2 * normalised(rattle, 1.0)
    return normalised(np.tanh(1.2 * x), 0.9)


SOUNDS = {
    "transformer_hum": (hum, "Transformer hums"),
    "corona_buzz": (corona, "Power line crackles"),
    "arc_crack": (crack, "Electric arc cracks"),
    "fuse_pop": (pop, "Fuse pops"),
    "breaker_clunk": (clunk, "Breaker trips"),
}


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    out = os.path.join(root, "src/main/resources/assets", NS)
    os.makedirs(os.path.join(out, "sounds"), exist_ok=True)
    events = {}
    for name, (make, _) in SOUNDS.items():
        sf.write(os.path.join(out, "sounds", name + ".ogg"), make().astype(np.float32), RATE, format="OGG", subtype="VORBIS")
        events[name] = {"subtitle": f"subtitles.{NS}.{name}", "sounds": [{"name": f"{NS}:{name}", "stream": False}]}
    with open(os.path.join(out, "sounds.json"), "w", newline="\n") as f:
        json.dump(events, f, indent=2)
        f.write("\n")
    print("wrote " + ", ".join(SOUNDS))


if __name__ == "__main__":
    main()
