/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.protection;

// Watches a breaker's conductors for unbalance (a lost phase the worst): unbalanced when the busiest carries over 5% of
// its rating and their spread (Unbalance.of) passes `threshold`. It trips after `tripTicks` unbalanced ticks in a row,
// and says on the tick unbalance starts.
public final class UnbalanceWatch {
    private int ticks;
    private boolean unbalanced, started;

    // One tick of the conductors' currents. True when it trips.
    public boolean tick(double[] amps, double rated, double threshold, int tripTicks) {
        double most = 0;
        for (double a : amps) {
            most = Math.max(most, a);
        }
        boolean now = most > rated * 0.05 && Unbalance.of(amps) > threshold;
        started = now && !unbalanced;
        unbalanced = now;
        ticks = now ? ticks + 1 : 0;
        if (now && ticks >= tripTicks) {
            ticks = 0;
            return true;
        }
        return false;
    }

    // Whether unbalance started this tick.
    public boolean started() {
        return started;
    }

    public void reset() {
        ticks = 0;
        unbalanced = false;
        started = false;
    }
}
