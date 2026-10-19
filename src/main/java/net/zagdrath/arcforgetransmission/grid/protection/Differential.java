/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.protection;

// A transformer differential element (87T): trips when what goes into the transformer and what comes out differ by more
// than pickup plus slope times the larger (a fault inside it; load and faults beyond it pass straight through).
public final class Differential {
    private Differential() {}

    public static boolean trips(double in, double out, double pickup, double slope) {
        return Math.abs(Math.abs(in) - Math.abs(out)) > pickup + slope * Math.max(Math.abs(in), Math.abs(out));
    }
}
