/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

// Single-phase AC (a line, L1 or L2, and the neutral N) or three-phase AC (A, B and C). There is no DC. A wire is one
// conductor; a circuit is a port's whole set of them (Port).
public enum CircuitType {
    SINGLE_PHASE,
    THREE_PHASE;

    public Set<Phase> phases() {
        Set<Phase> phases = EnumSet.noneOf(Phase.class);
        for (Phase phase : Phase.values()) {
            if (phase.circuit() == this) {
                phases.add(phase);
            }
        }
        return phases;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CircuitType byId(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }
}
