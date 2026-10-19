/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.protection;

// A breaker's or recloser's overcurrent protection, tick by tick: an instantaneous element (trips the tick its current
// passes instPickup, 0 for none, when nothing protective is between it and the trouble) and a time element (an inverse
// curve of timeDial over pickup with a relay, or a definite delay without). Graded: each protective device between it and
// the fault or load adds `interval` ticks, so the one nearest the trouble always goes first and the rest reset once it's
// cleared. A recloser set fuse-saving trips its first fastShots shots at once, even with a fuse beyond it (clearing a
// passing fault before the fuse melts); set fuse-blowing, it waits its graded time so the fuse clears the fault.
public final class Overcurrent {
    public record Settings(double pickup, double instPickup, double timeDial, boolean inverse, int definiteTicks, int interval, boolean fuseSaving,
            int fastShots) {}

    // Progress toward a trip (1 trips), kept between ticks.
    private double progress;

    public double progress() {
        return progress;
    }

    public void reset() {
        progress = 0;
    }

    // One tick at `amps` with `beyond` protective devices between it and the trouble, on reclose shot `shot` (0 the
    // first). True when it trips.
    public boolean tick(double amps, int beyond, int shot, Settings s) {
        if (amps <= s.pickup() + 1.0E-9) {
            progress = 0;
            return false;
        }
        boolean fast = s.fuseSaving() && shot < s.fastShots();
        boolean instantaneous = s.instPickup() > 0 && amps > s.instPickup() && beyond == 0;
        if (instantaneous || fast) {
            progress = 0;
            return true;
        }
        double ticks = (s.inverse() ? Curves.inverseTicks(amps / s.pickup(), s.timeDial()) : s.definiteTicks()) + (double) beyond * s.interval();
        progress += 1.0 / Math.max(1.0, ticks);
        if (progress >= 1.0 - 1.0E-9) {
            progress = 0;
            return true;
        }
        return false;
    }
}
