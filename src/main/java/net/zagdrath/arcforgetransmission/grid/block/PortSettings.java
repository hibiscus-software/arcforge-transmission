/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

// A port's settings as the Wrench on Configure sets them (a Pin Insulator's, a Cable Termination's): its mode (sneaking:
// its current limit in eighths of its tier's rating).
public interface PortSettings {
    PinInsulatorMode mode();

    int limitEighths();

    // The current limit, A per conductor.
    double limitAmps();

    // Sets the mode and limit of the whole port.
    void setForPort(PinInsulatorMode mode, int limitEighths);
}
