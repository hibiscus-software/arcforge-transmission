/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.List;

// A shunt capacitor bank (vars positive: it supplies lagging reactive power, raising the voltage near it) or shunt reactor
// (negative: it takes it, lowering the voltage), on a whole circuit of nodes. vars is in FE/t's reactive equivalent (1 V x
// 1 A); switched out, it does nothing.
public record Shunt(long id, VoltageTier tier, List<Long> nodes, double vars, boolean inService) {
    public Shunt {
        nodes = List.copyOf(nodes);
    }
}
