/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

// One wire: a single conductor between two insulators, its length the curve's arc in blocks. overTicks counts the
// ticks in a row it has run over its rating. A tray cable or a busway run is three of these, one a phase (a 3-conductor
// connection: A to A, B to B, C to C), so the solver, protection and burnout treat it as they do three wires.
public final class GridEdge {
    // A bare conductor; one in a rubber sheath (LV and MV only: safe to touch); an underground cable (LV and MV: insulated,
    // never drawn, out of lightning's reach); a jumper, the link a Wall Bushing or a protection device makes through
    // itself (no length, never drawn, dropped or burnt out); or a shield wire, grounded steel between tower peaks that
    // catches lightning and carries no current. Factory distribution: a cable laid along cable tray between two Cable
    // Terminations (insulated, never sagging, shocking or struck; its rating derated by the tray it shares, its length the
    // tray path's), or busway (an enclosed bus duct or gas-insulated bus joint between two busway blocks, rated by its
    // size, failing as damaged rather than dropping).
    public enum Kind {
        BARE, INSULATED, JUMPER, BURIED, SHIELD, CABLE, BUS;

        // Whether it carries a circuit's current (a conductor), not a jumper or a shield.
        public boolean conductor() {
            return this == BARE || this == INSULATED || this == BURIED || this == CABLE || this == BUS;
        }

        // Whether it's drawn as a hanging wire between its ends (a cable is drawn along its tray, busway is its blocks).
        public boolean hangs() {
            return this == BARE || this == INSULATED || this == SHIELD;
        }

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
    // Busway's size: its rating and conductance as a multiple of its tier's wire (2, 4 or 8; 1 for anything else).
    private final int size;
    // A cable's derating (1: none), from the tray it's laid in (the most crowded block on its path).
    private double derate = 1.0;
    int overTicks;
    // A voltage regulator's boost through this link (a jumper), per unit, from a to b (the other way it bucks).
    private double boost;

    public GridEdge(long id, long a, long b, VoltageTier tier, CircuitType circuit, double length) {
        this(id, a, b, tier, circuit, length, Kind.BARE);
    }

    public GridEdge(long id, long a, long b, VoltageTier tier, CircuitType circuit, double length, Kind kind) {
        this(id, a, b, tier, circuit, length, kind, 1);
    }

    public GridEdge(long id, long a, long b, VoltageTier tier, CircuitType circuit, double length, Kind kind, int size) {
        this.id = id;
        this.a = a;
        this.b = b;
        this.tier = tier;
        this.circuit = circuit;
        this.length = length;
        this.kind = kind;
        this.size = Math.max(1, size);
    }

    // The same wire at a new length (re-hung when an end moved), keeping everything else.
    public GridEdge withLength(double length) {
        GridEdge out = new GridEdge(id, a, b, tier, circuit, length, kind, size);
        out.derate = derate;
        out.boost = boost;
        return out;
    }

    public int size() {
        return size;
    }

    public double derate() {
        return derate;
    }

    public void setDerate(double derate) {
        this.derate = Math.max(0.0, Math.min(1.0, derate));
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
        return settings.resistance(this);
    }

    public double boost() {
        return boost;
    }

    public void setBoost(double boost) {
        this.boost = boost;
    }

    public int overTicks() {
        return overTicks;
    }
}
