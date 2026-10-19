/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

// One wire: a single conductor between two insulators, its length the curve's arc in blocks. overTicks counts the
// ticks in a row it has run over its rating.
public final class GridEdge {
    // A bare conductor; one in a rubber sheath (LV and MV only: safe to touch); or a jumper, the link a Wall Bushing
    // makes through itself between its insulators of one label (no length, never drawn, dropped or burnt out).
    public enum Kind {
        BARE, INSULATED, JUMPER;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Kind byId(String id) {
            return valueOf(id.toUpperCase(java.util.Locale.ROOT));
        }
    }

    private final long id, a, b;
    private final VoltageTier tier;
    private final CircuitType circuit;
    private final double length;
    private final Kind kind;
    int overTicks;

    public GridEdge(long id, long a, long b, VoltageTier tier, CircuitType circuit, double length) {
        this(id, a, b, tier, circuit, length, Kind.BARE);
    }

    public GridEdge(long id, long a, long b, VoltageTier tier, CircuitType circuit, double length, Kind kind) {
        this.id = id;
        this.a = a;
        this.b = b;
        this.tier = tier;
        this.circuit = circuit;
        this.length = length;
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isJumper() {
        return kind == Kind.JUMPER;
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
