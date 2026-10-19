/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.protection;

// A sectionalizer: no breaking capacity of its own. It counts the upstream recloser's operations (fault current through
// it, then its line going dead) and after `count` of them opens while the line is dead, isolating a permanent fault
// beyond it before the recloser locks out. Its count is forgotten after `resetTicks` of the line live with no fault.
public final class Sectionalizer {
    private int counted, quiet;
    private boolean sawFault, open;

    public int counted() {
        return counted;
    }

    public boolean open() {
        return open;
    }

    public void close() {
        open = false;
        counted = 0;
        sawFault = false;
        quiet = 0;
    }

    public void restore(int counted, boolean open) {
        this.counted = counted;
        this.open = open;
    }

    // One tick: whether fault current (over its actuating current) passed it, and whether its line is live. True on the
    // tick it opens.
    public boolean tick(boolean faultCurrent, boolean live, int count, int resetTicks) {
        if (open) {
            return false;
        }
        if (faultCurrent) {
            sawFault = true;
            quiet = 0;
            return false;
        }
        if (!live) {
            if (sawFault) {
                counted++;
                sawFault = false;
            }
            if (counted >= count) {
                open = true;
                return true;
            }
            return false;
        }
        if (counted > 0 && ++quiet >= resetTicks) {
            counted = 0;
            quiet = 0;
        }
        return false;
    }
}
