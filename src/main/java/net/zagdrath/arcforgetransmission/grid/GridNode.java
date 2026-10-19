/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

// One insulator wires attach to: one conductor's attach point on a tower, a pin insulator or a transformer. Its phase label
// can change (the wrench); between circuit types only while it has no wires (GridGraph checks).
public final class GridNode {
    private final long id;
    private final VoltageTier tier;
    private Phase phase;

    public GridNode(long id, VoltageTier tier, Phase phase) {
        this.id = id;
        this.tier = tier;
        this.phase = phase;
    }

    public long id() {
        return id;
    }

    public VoltageTier tier() {
        return tier;
    }

    public Phase phase() {
        return phase;
    }

    public CircuitType circuit() {
        return phase.circuit();
    }

    void setPhase(Phase phase) {
        this.phase = phase;
    }
}
