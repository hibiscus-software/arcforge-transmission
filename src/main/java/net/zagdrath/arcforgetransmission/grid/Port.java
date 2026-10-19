/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.List;

// Where FE enters or leaves the grid: a whole circuit's set of insulators on one thing (the pin insulators mounted on one
// block, or one side of a transformer), one node per conductor. offer and demand are FE/t this tick; limitAmps caps the
// current in each of its conductors (infinity for none).
public record Port(long id, VoltageTier tier, List<Long> nodes, double offer, double demand, double limitAmps) {
    public Port {
        nodes = List.copyOf(nodes);
    }
}
