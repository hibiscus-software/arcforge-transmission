/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.protection;

// A fuse's element: heats each tick it's over its rating (Curves.fuseHeat), cools when it isn't, and melts at `melt`.
public final class FuseElement {
    private double heat;

    public double heat() {
        return heat;
    }

    public void reset() {
        heat = 0;
    }

    // One tick at a multiple of its rating; true when it melts. At or below its rating it loses `cooling` of its heat.
    public boolean tick(double multiple, double melt, double cooling) {
        double h = Curves.fuseHeat(multiple);
        heat = h > 0 ? heat + h : heat * (1.0 - cooling);
        if (heat >= melt) {
            heat = 0;
            return true;
        }
        return false;
    }
}
