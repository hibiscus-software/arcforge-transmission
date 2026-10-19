/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

// Something went wrong on the grid: what (cause), where (the edge, or -1 for a node fault), and how badly (amps for an
// overcurrent, volts for an overvoltage). Breakers and grounding will listen for these (GridFaults).
public record FaultEvent(long edgeId, long nodeId, Cause cause, double magnitude) {
    public enum Cause {
        // A wire ran over its rating too long and burnt out (magnitude: its current, A).
        OVERCURRENT_BURNOUT,
        // A wire is over its rating this tick (magnitude: its current, A); sent every tick it is, before it burns.
        OVERCURRENT,
        // A block was fed (or fed from) the wrong tier (magnitude: the line voltage, V).
        OVERVOLTAGE,
        // Lightning struck a conductor (magnitude: 0); a flashover to ground follows unless a shield wire took it.
        LIGHTNING,
        // A conductor to ground on a live line (magnitude: the fault current, A).
        GROUND_FAULT,
        // A whole three-phase port with one conductor dead beside live ones (magnitude: the conductors still live).
        PHASE_LOSS,
        // A device's conductors' currents spread over the threshold (magnitude: the spread, 0 to 1).
        UNBALANCE
    }

    public static FaultEvent edge(long edgeId, Cause cause, double magnitude) {
        return new FaultEvent(edgeId, -1, cause, magnitude);
    }

    public static FaultEvent node(long nodeId, Cause cause, double magnitude) {
        return new FaultEvent(-1, nodeId, cause, magnitude);
    }
}
