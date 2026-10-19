/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.Locale;

// What one conductor carries. Every insulator (a tower's, a pin insulator's, a transformer's bushing) has one. A, B and C
// are the three phases of a three-phase circuit (120 degrees apart); L1, L2 and N are single-phase (split-phase: L1 and
// L2 opposite each other, N the neutral both return on). Only labels of the same circuit type may be wired together;
// two different letters of one type is a transposition, and allowed.
public enum Phase {
    A(CircuitType.THREE_PHASE, 0.0),
    B(CircuitType.THREE_PHASE, -120.0),
    C(CircuitType.THREE_PHASE, 120.0),
    L1(CircuitType.SINGLE_PHASE, 0.0),
    L2(CircuitType.SINGLE_PHASE, 180.0),
    N(CircuitType.SINGLE_PHASE, Double.NaN);

    private final CircuitType circuit;
    private final double degrees;

    Phase(CircuitType circuit, double degrees) {
        this.circuit = circuit;
        this.degrees = degrees;
    }

    public CircuitType circuit() {
        return circuit;
    }

    public boolean isNeutral() {
        return this == N;
    }

    // The phase angle of the current a line conductor carries (radians); the neutral has none of its own: it carries
    // its line's current back (CircuitMath.angle).
    public double radians() {
        return Math.toRadians(degrees);
    }

    // The next label of the same circuit type (A -> B -> C -> A; L1 -> L2 -> N -> L1).
    public Phase next() {
        return switch (this) {
            case A -> B;
            case B -> C;
            case C -> A;
            case L1 -> L2;
            case L2 -> N;
            case N -> L1;
        };
    }

    // The same label in the other circuit type (A <-> L1, B <-> L2, C <-> N), for switching an unwired insulator.
    public Phase otherCircuit() {
        return values()[(ordinal() + 3) % 6];
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Phase byId(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }
}
