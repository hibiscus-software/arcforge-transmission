/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

// One wire: a single conductor between two insulators, its length the curve's arc in blocks. overTicks counts the
// ticks in a row it has run over its rating.
public final class GridEdge {
    private final long id, a, b;
    private final VoltageTier tier;
    private final CircuitType circuit;
    private final double length;
    int overTicks;

    public GridEdge(long id, long a, long b, VoltageTier tier, CircuitType circuit, double length) {
        this.id = id;
        this.a = a;
        this.b = b;
        this.tier = tier;
        this.circuit = circuit;
        this.length = length;
    }

    public long id() {
        return id;
    }

    public long a() {
        return a;
    }

    public long b() {
        return b;
    }

    public long other(long node) {
        return node == a ? b : a;
    }

    public VoltageTier tier() {
        return tier;
    }

    public CircuitType circuit() {
        return circuit;
    }

    public double length() {
        return length;
    }

    // Its ohms.
    public double resistance(GridSettings settings) {
        return settings.resistance(tier) * length;
    }

    public int overTicks() {
        return overTicks;
    }
}
