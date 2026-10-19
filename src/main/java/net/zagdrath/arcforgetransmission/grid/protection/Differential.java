/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.protection;

// A transformer differential element (87T): with both sides' currents counted into the transformer (what leaves it is
// negative), it trips when their sum, |I_in + I_out|, passes pickup plus slope times the larger. A fault inside it trips
// it; load and faults beyond it pass straight through and cancel.
public final class Differential {
    private Differential() {}

    public static boolean trips(double inA, double inB, double pickup, double slope) {
        return Math.abs(inA + inB) > pickup + slope * Math.max(Math.abs(inA), Math.abs(inB));
    }
}
