/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.protection;

// Time-current curves, in ticks (20 a second): an overcurrent relay's (IEC standard inverse: t = TD x 0.14 / (M^0.02 - 1)
// seconds, M the current over its pickup) and a fuse's melting (I squared t: it melts once its heat, the sum each tick of
// M^2 - 1 over its rating, reaches its melting rating).
public final class Curves {
    private Curves() {}

    // Ticks an inverse-time relay takes to trip at a multiple of its pickup (never at or below 1).
    public static double inverseTicks(double multiple, double timeDial) {
        if (multiple <= 1.0 + 1.0E-9) {
            return Double.POSITIVE_INFINITY;
        }
        return timeDial * 0.14 / (Math.pow(multiple, 0.02) - 1.0) * 20.0;
    }

    // A fuse's heat this tick at a multiple of its rating (none at or below it).
    public static double fuseHeat(double multiple) {
        return multiple <= 1.0 ? 0.0 : multiple * multiple - 1.0;
    }

    // Ticks a fuse takes to melt at a steady multiple of its rating, for a melting rating of `melt`.
    public static double fuseTicks(double multiple, double melt) {
        double heat = fuseHeat(multiple);
        return heat <= 0 ? Double.POSITIVE_INFINITY : Math.ceil(melt / heat);
    }
}
