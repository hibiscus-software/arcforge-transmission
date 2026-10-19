/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.protection;

// How unbalanced a set of conductors' currents is: their spread over the largest (0 balanced; 1 one carrying nothing while
// another carries current).
public final class Unbalance {
    private Unbalance() {}

    public static double of(double... amps) {
        double max = 0, min = Double.MAX_VALUE;
        for (double a : amps) {
            max = Math.max(max, a);
            min = Math.min(min, a);
        }
        return max <= 1.0E-9 ? 0.0 : (max - min) / max;
    }
}
