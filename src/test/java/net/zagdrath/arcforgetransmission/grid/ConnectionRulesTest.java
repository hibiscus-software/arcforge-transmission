/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// What may be wired to what: one tier and one circuit type per network (a transposition is fine), the right wire,
// spans within reach.
class ConnectionRulesTest {
    private static final GridSettings SETTINGS = GridSettings.DEFAULTS;

    private static GridGraph graph() {
        GridGraph graph = new GridGraph();
        graph.addNode(new GridNode(1, VoltageTier.MV, Phase.A));
        graph.addNode(new GridNode(2, VoltageTier.MV, Phase.A));
        graph.addNode(new GridNode(3, VoltageTier.HV, Phase.A));
        graph.addNode(new GridNode(4, VoltageTier.MV, Phase.N));
        graph.addNode(new GridNode(5, VoltageTier.MV, Phase.C));
        return graph;
    }

    @Test
    void sameTierAndCircuitWithinReachConnects() {
        assertNull(ConnectionRules.check(graph(), 1, 2, VoltageTier.MV, 20, SETTINGS));
    }

    @Test
    void mixedTiersAreRefused() {
        assertEquals(ConnectionRules.Refusal.MIXED_TIER, ConnectionRules.check(graph(), 1, 3, VoltageTier.MV, 5, SETTINGS));
    }

    @Test
    void mixedCircuitsAreRefused() {
        assertEquals(ConnectionRules.Refusal.MIXED_CIRCUIT, ConnectionRules.check(graph(), 1, 4, VoltageTier.MV, 5, SETTINGS));
    }

    @Test
    void theWireMustMatchTheTier() {
        assertEquals(ConnectionRules.Refusal.WRONG_WIRE, ConnectionRules.check(graph(), 1, 2, VoltageTier.HV, 5, SETTINGS));
    }

    @Test
    void spansPastTheTiersReachAreRefused() {
        assertEquals(ConnectionRules.Refusal.TOO_LONG, ConnectionRules.check(graph(), 1, 2, VoltageTier.MV, 24.5, SETTINGS));
        assertNull(ConnectionRules.check(graph(), 1, 2, VoltageTier.MV, 24.0, SETTINGS));
    }

    @Test
    void oneWirePerPairAndNotToItself() {
        GridGraph graph = graph();
        graph.addEdge(new GridEdge(9, 1, 2, VoltageTier.MV, CircuitType.THREE_PHASE, 10));
        assertEquals(ConnectionRules.Refusal.ALREADY_CONNECTED, ConnectionRules.check(graph, 2, 1, VoltageTier.MV, 10, SETTINGS));
        assertEquals(ConnectionRules.Refusal.SAME_ENDPOINT, ConnectionRules.check(graph, 1, 1, VoltageTier.MV, 0, SETTINGS));
    }

    @Test
    void aTranspositionIsAllowed() {
        assertNull(ConnectionRules.check(graph(), 1, 5, VoltageTier.MV, 10, SETTINGS));
    }

    @Test
    void aLabelOnlyChangesCircuitTypeWithNoWires() {
        GridGraph graph = graph();
        assertTrue(graph.setPhase(4, Phase.B));
        graph.addEdge(new GridEdge(9, 1, 4, VoltageTier.MV, CircuitType.THREE_PHASE, 10));
        // ABC <-> ACB is fine while wired; three-phase to single-phase isn't.
        assertTrue(graph.setPhase(4, Phase.C));
        assertFalse(graph.setPhase(4, Phase.N));
        assertEquals(Phase.C, graph.node(4).phase());
    }

    @Test
    void networksSplitAndMergeWithTheirWires() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 2, 4);
        // One network per phase.
        assertEquals(3, graph.networks().size());
        graph.removeEdge(GridFixtures.edge(0, 2));
        assertEquals(4, graph.networks().size());
        graph.addEdge(new GridEdge(GridFixtures.edge(0, 2), GridFixtures.node(0, 2), GridFixtures.node(0, 3), VoltageTier.LV, CircuitType.THREE_PHASE, 4));
        assertEquals(3, graph.networks().size());
        assertEquals(2, graph.removeNode(GridFixtures.node(0, 3)).size());
        // Phase A's line is cut short; its far end, left with no wires, is in no network.
        assertEquals(3, graph.networks().size());
        assertEquals(null, graph.networkOf(GridFixtures.node(0, 4)));
        assertEquals(GridFixtures.node(0, 1), graph.networkOf(GridFixtures.node(0, 2)).id());
    }
}
