/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.protection;

// An automatic transfer switch: its load on the preferred feeder, moved to the alternate once the preferred has been dead
// `transferTicks` in a row with the alternate live, and back once the preferred has been live again `retransferTicks`.
// Break before make: it's only ever on one.
public final class TransferSwitch {
    public enum Position {
        PREFERRED, ALTERNATE
    }

    private Position position = Position.PREFERRED;
    private int waited;

    public Position position() {
        return position;
    }

    public void restore(Position position) {
        this.position = position;
        waited = 0;
    }

    // One tick; true when it changes over.
    public boolean tick(boolean preferredLive, boolean alternateLive, int transferTicks, int retransferTicks) {
        boolean wants = position == Position.PREFERRED ? !preferredLive && alternateLive : preferredLive;
        if (!wants) {
            waited = 0;
            return false;
        }
        if (++waited >= (position == Position.PREFERRED ? transferTicks : retransferTicks)) {
            position = position == Position.PREFERRED ? Position.ALTERNATE : Position.PREFERRED;
            waited = 0;
            return true;
        }
        return false;
    }
}
