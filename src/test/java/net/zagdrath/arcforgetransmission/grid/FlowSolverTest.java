/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

// The flow solver: complete circuits only, phasor currents, I squared R losses, on small grids, outside the game.
class FlowSolverTest {
    private static final GridSettings SETTINGS = GridSettings.DEFAULTS;
    private static final double INF = Double.POSITIVE_INFINITY;

    private static Port source(long id, VoltageTier tier, List<Long> nodes, double offer) {
        return new Port(id, tier, nodes, offer, 0, INF);
    }

    private static Port sink(long id, VoltageTier tier, List<Long> nodes, double demand) {
        return new Port(id, tier, nodes, 0, demand, INF);
    }

    // A line of towers with a source port at one end and a sink port at the other.
    private static FlowSolver.Result line(VoltageTier tier, int towers, double span, double offer, double demand) {
        GridGraph graph = GridFixtures.line(tier, towers, span);
        return FlowSolver.solve(graph, List.of(source(1, tier, GridFixtures.at(1), offer), sink(2, tier, GridFixtures.at(towers + 2), demand)), SETTINGS);
    }

    @Test
    void deliversDemandAndPaysLossesFromTheSource() {
        FlowSolver.Result result = line(VoltageTier.LV, 2, 16, 2000, 1000);
        assertEquals(1000.0, result.delivered(2), 1.0E-6);
        double amps = 1000.0 / VoltageTier.LV.volts();
        // Three spans, three conductors each.
        double expectedLoss = 3 * (3 * amps * amps * 0.008 * 16);
        assertEquals(expectedLoss, result.totalLoss(), 1.0E-6);
        assertEquals(result.totalDelivered() + result.totalLoss(), result.totalDrawn(), 1.0E-6);
        for (int k = 0; k < 3; k++) {
            assertEquals(amps, result.amps(GridFixtures.edge(k, 1)), 1.0E-9);
        }
        assertEquals(FlowSolver.PortStatus.OK, result.status(1));
    }

    @Test
    void lossGrowsWithTheSquareOfTheCurrent() {
        double full = line(VoltageTier.LV, 1, 16, 5000, 1000).totalLoss();
        double half = line(VoltageTier.LV, 1, 16, 5000, 500).totalLoss();
        assertEquals(4.0, full / half, 1.0E-9);
    }

    @Test
    void lossGrowsWithWireLength() {
        double short_ = line(VoltageTier.LV, 1, 8, 5000, 1000).totalLoss();
        double long_ = line(VoltageTier.LV, 1, 16, 5000, 1000).totalLoss();
        assertEquals(2.0, long_ / short_, 1.0E-9);
    }

    @Test
    void aShortSourceDeliversWhatItHasLessLosses() {
        FlowSolver.Result result = line(VoltageTier.LV, 1, 16, 500, 1000);
        assertEquals(500.0, result.totalDrawn(), 1.0E-6);
        assertTrue(result.delivered(2) < 500.0 && result.delivered(2) > 480.0, "delivered " + result.delivered(2));
        assertEquals(result.totalDrawn(), result.totalDelivered() + result.totalLoss(), 1.0E-6);
    }

    @Test
    void sourcesShareInProportionToWhatTheyOffer() {
        GridGraph graph = GridFixtures.line(VoltageTier.MV, 1, 10);
        // A second source at the far end's tower, beside the sink.
        FlowSolver.Result result = FlowSolver.solve(graph, List.of(source(1, VoltageTier.MV, GridFixtures.at(1), 1000),
                source(3, VoltageTier.MV, GridFixtures.at(2), 3000), sink(2, VoltageTier.MV, GridFixtures.at(3), 2000)), SETTINGS);
        assertEquals(2000.0, result.delivered(2), 1.0E-6);
        double fromOne = result.drawn(1), fromThree = result.drawn(3);
        assertTrue(fromOne > 500 && fromThree > 1500, fromOne + " / " + fromThree);
        assertEquals(result.totalDrawn(), result.totalDelivered() + result.totalLoss(), 1.0E-6);
    }

    @Test
    void aCurrentLimitCapsEverythingThroughIt() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 1, 10);
        FlowSolver.Result result = FlowSolver.solve(graph, List.of(new Port(1, VoltageTier.LV, GridFixtures.at(1), 5000, 0, 4.0),
                sink(2, VoltageTier.LV, GridFixtures.at(3), 2000)), SETTINGS);
        assertEquals(4.0, result.amps(GridFixtures.edge(0, 1)), 1.0E-9);
        assertEquals(4.0 * VoltageTier.LV.volts(), result.delivered(2), 1.0E-6);
    }

    @Test
    void opposingFlowsCancelInASharedWire() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 1, 10);
        // Each end both gives and takes the same: the two flows cancel on every wire.
        FlowSolver.Result result = FlowSolver.solve(graph, List.of(new Port(1, VoltageTier.LV, GridFixtures.at(1), 500, 500, INF),
                new Port(2, VoltageTier.LV, GridFixtures.at(3), 500, 500, INF)), SETTINGS);
        assertEquals(1000.0, result.totalDelivered(), 1.0E-6);
        assertEquals(0.0, result.amps(GridFixtures.edge(0, 1)), 1.0E-9);
        assertEquals(0.0, result.totalLoss(), 1.0E-9);
    }

    @Test
    void superconductorLosesAlmostNothing() {
        FlowSolver.Result result = line(VoltageTier.EHV, 3, 32, 2_400_000, 2_097_152);
        assertEquals(2_097_152.0, result.delivered(2), 1.0E-6);
        assertTrue(result.totalLoss() < 0.01 * 2_097_152, "EHV lost " + result.totalLoss());
    }

    @Test
    void nothingFlowsWithoutBothEnds() {
        assertEquals(0.0, line(VoltageTier.LV, 1, 10, 500, 0).totalDrawn(), 0.0);
        assertEquals(0.0, line(VoltageTier.LV, 1, 10, 0, 500).totalDrawn(), 0.0);
    }

    @Test
    void aMissingPhaseMovesNothing() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 1, 10);
        // The sink has only A and B.
        FlowSolver.Result result = FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, GridFixtures.at(1), 1000),
                sink(2, VoltageTier.LV, GridFixtures.at(3).subList(0, 2), 1000)), SETTINGS);
        assertEquals(FlowSolver.PortStatus.INCOMPLETE, result.status(2));
        assertEquals(0.0, result.totalDrawn(), 0.0);
    }

    @Test
    void aBrokenConductorMovesNothing() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 1, 10);
        graph.removeEdge(GridFixtures.edge(1, 2));
        FlowSolver.Result result = FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, GridFixtures.at(1), 1000),
                sink(2, VoltageTier.LV, GridFixtures.at(3), 1000)), SETTINGS);
        assertEquals(0.0, result.totalDelivered(), 0.0);
        assertEquals(0.0, result.amps(GridFixtures.edge(0, 1)), 0.0);
    }

    @Test
    void twoPhasesOnOneNetworkAreShorted() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 1, 10);
        graph.addEdge(new GridEdge(9_000, GridFixtures.node(0, 3), GridFixtures.node(1, 3), VoltageTier.LV, CircuitType.THREE_PHASE, 1));
        FlowSolver.Result result = FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, GridFixtures.at(1), 1000),
                sink(2, VoltageTier.LV, GridFixtures.at(3), 1000)), SETTINGS);
        assertEquals(FlowSolver.PortStatus.SHORTED, result.status(1));
        assertEquals(0.0, result.totalDrawn(), 0.0);
    }

    @Test
    void aTranspositionStillCarriesPower() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 1, 10);
        // ACB at the far end: B and C swapped.
        graph.setPhase(GridFixtures.node(1, 3), Phase.C);
        graph.setPhase(GridFixtures.node(2, 3), Phase.B);
        FlowSolver.Result result = FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, GridFixtures.at(1), 2000),
                sink(2, VoltageTier.LV, GridFixtures.at(3), 1000)), SETTINGS);
        assertEquals(1000.0, result.delivered(2), 1.0E-6);
    }

    @Test
    void theTiersMustMatch() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 1, 10);
        FlowSolver.Result result = FlowSolver.solve(graph, List.of(source(1, VoltageTier.MV, GridFixtures.at(1), 1000),
                sink(2, VoltageTier.LV, GridFixtures.at(3), 1000)), SETTINGS);
        assertEquals(FlowSolver.PortStatus.WRONG_TIER, result.status(1));
        assertEquals(0.0, result.totalDelivered(), 0.0);
    }

    // Split phase: two single-phase circuits (L1 + N, L2 + N) sharing one neutral, which carries only the difference.
    @Test
    void aSharedNeutralCarriesTheDifference() {
        GridGraph graph = new GridGraph();
        Phase[] labels = { Phase.L1, Phase.L2, Phase.N };
        for (int k = 0; k < 3; k++) {
            graph.addNode(new GridNode(1 + k, VoltageTier.LV, labels[k]));
            graph.addNode(new GridNode(11 + k, VoltageTier.LV, labels[k]));
            graph.addEdge(new GridEdge(100 + k, 1 + k, 11 + k, VoltageTier.LV, CircuitType.SINGLE_PHASE, 10));
        }
        // 10 A on L1, 5 A on L2.
        FlowSolver.Result result = FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, List.of(1L, 3L), 5000),
                source(2, VoltageTier.LV, List.of(2L, 3L), 5000), sink(3, VoltageTier.LV, List.of(11L, 13L), 2560),
                sink(4, VoltageTier.LV, List.of(12L, 13L), 1280)), SETTINGS);
        assertEquals(2560.0, result.delivered(3), 1.0E-6);
        assertEquals(1280.0, result.delivered(4), 1.0E-6);
        assertEquals(10.0, result.amps(100), 1.0E-9);
        assertEquals(5.0, result.amps(101), 1.0E-9);
        assertEquals(5.0, result.amps(102), 1.0E-9);
        // Balanced, the neutral carries nothing.
        FlowSolver.Result balanced = FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, List.of(1L, 3L), 5000),
                source(2, VoltageTier.LV, List.of(2L, 3L), 5000), sink(3, VoltageTier.LV, List.of(11L, 13L), 1280),
                sink(4, VoltageTier.LV, List.of(12L, 13L), 1280)), SETTINGS);
        assertEquals(0.0, balanced.amps(102), 1.0E-9);
        assertEquals(balanced.totalDrawn(), balanced.totalDelivered() + balanced.totalLoss(), 1.0E-6);
    }
}
