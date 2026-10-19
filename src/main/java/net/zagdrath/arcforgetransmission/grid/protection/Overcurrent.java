/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.protection;

import net.zagdrath.arcforgetransmission.grid.FlowSolver;

// A breaker's or recloser's overcurrent protection, tick by tick: an instantaneous element (trips the tick its current
// passes instPickup, 0 for none, when nothing protective is between it and the trouble) and a time element (an inverse
// curve of timeDial over pickup with a relay, or a definite delay without). Graded: each overloaded protective device
// between it and the trouble adds `interval` ticks, and when those are all fuses it waits for the fuse to melt at the
// measured current plus `interval`, so the one nearest the trouble always goes first and the rest reset once it's cleared.
// A recloser set fuse-saving trips its first fastShots shots at once when only fuses (or nothing) are beyond it (clearing
// a passing fault before the fuse melts); set fuse-blowing, it waits its graded time so the fuse clears the fault.
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

    // One tick at `amps` with `beyond` protective devices (none of them fuses) between it and the trouble, on reclose shot
    // `shot`. True when it trips.
    public boolean tick(double amps, int beyond, int shot, Settings s) {
        return tick(amps, new FlowSolver.Beyond(beyond, false, 0), shot, s, 0);
    }

    // One tick at `amps` with `beyond` between it and the trouble, on reclose shot `shot` (0 the first), fuses melting at
    // `fuseMelt`. True when it trips.
    public boolean tick(double amps, FlowSolver.Beyond beyond, int shot, Settings s, double fuseMelt) {
        if (amps <= s.pickup() + 1.0E-9) {
            progress = 0;
            return false;
        }
        boolean overFuses = beyond.count() == 0 || beyond.allFuses();
        boolean fast = s.fuseSaving() && shot < s.fastShots() && overFuses;
        boolean instantaneous = s.instPickup() > 0 && amps > s.instPickup() && beyond.count() == 0;
        if (instantaneous || fast) {
            progress = 0;
            return true;
        }
        double own = s.inverse() ? Curves.inverseTicks(amps / s.pickup(), s.timeDial()) : s.definiteTicks();
        double ticks = beyond.count() > 0 && beyond.allFuses() && beyond.fuseRating() > 0
                ? Math.max(own, Curves.fuseTicks(amps / beyond.fuseRating(), fuseMelt) + s.interval())
                : own + (double) beyond.count() * s.interval();
        progress += 1.0 / Math.max(1.0, ticks);
        if (progress >= 1.0 - 1.0E-9) {
            progress = 0;
            return true;
        }
        return false;
    }
}
