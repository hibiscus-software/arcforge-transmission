/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.HashSet;

import org.junit.jupiter.api.Test;

// Routes through zero-length jumpers (a Wall Bushing's, a protection device's): a ring of them, with ties everywhere,
// must still give every route, each a simple path from its source to its target (a tie once let two jumpered nodes
// point at each other and the route building ran out of memory).
class GridNetworkTest {
    @Test
    void zeroLengthJumpersRouteWithoutLooping() {
        GridGraph graph = new GridGraph();
        for (long id = 1; id <= 5; id++) {
            graph.addNode(new GridNode(id, VoltageTier.LV, Phase.A));
        }
        // A ring of jumpers 1-2-3-4-1 (ids against their order), and a wire out to 5.
        graph.addEdge(new GridEdge(40, 1, 2, VoltageTier.LV, CircuitType.THREE_PHASE, 0.0, GridEdge.Kind.JUMPER));
        graph.addEdge(new GridEdge(10, 2, 3, VoltageTier.LV, CircuitType.THREE_PHASE, 0.0, GridEdge.Kind.JUMPER));
        graph.addEdge(new GridEdge(30, 3, 4, VoltageTier.LV, CircuitType.THREE_PHASE, 0.0, GridEdge.Kind.JUMPER));
        graph.addEdge(new GridEdge(20, 4, 1, VoltageTier.LV, CircuitType.THREE_PHASE, 0.0, GridEdge.Kind.JUMPER));
        graph.addEdge(new GridEdge(50, 3, 5, VoltageTier.LV, CircuitType.THREE_PHASE, 12.0, GridEdge.Kind.BARE));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (long from = 1; from <= 5; from++) {
                GridNetwork network = graph.networkOf(from);
                assertNotNull(network);
                for (long to = 1; to <= 5; to++) {
                    GridNetwork.Route route = network.route(from, to);
                    assertNotNull(route, from + " to " + to);
                    assertEquals(from, (long) route.nodes().get(0));
                    assertEquals(to, (long) route.nodes().get(route.nodes().size() - 1));
                    assertEquals(route.nodes().size(), new HashSet<>(route.nodes()).size(), "a simple path " + from + " to " + to);
                }
            }
        });
    }
}
