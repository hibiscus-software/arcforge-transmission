/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.List;

// Where FE enters or leaves the grid: a whole circuit's set of insulators on one thing (the pin insulators mounted on one
// block, a unit transformer's bushings), one node per conductor. offer and demand are FE/t this tick; limitAmps caps the
// current in each of its conductors (infinity for none). A storage port (a battery behind a unit transformer) gives only
// to what its island's other sources can't cover and takes only what they have spare. setpoint is the voltage a source
// holds at its terminals, per unit of its tier's.
public record Port(long id, VoltageTier tier, List<Long> nodes, double offer, double demand, double limitAmps, boolean storage, double setpoint) {
    public Port {
        nodes = List.copyOf(nodes);
    }

    public Port(long id, VoltageTier tier, List<Long> nodes, double offer, double demand, double limitAmps) {
        this(id, tier, nodes, offer, demand, limitAmps, false, 1.0);
    }
}
