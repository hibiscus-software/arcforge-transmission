/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// Busway and tray cable as the grid sees them: three conductors a run (A to A, B to B, C to C), rated by busway's size
// and a cable's derating, busway losing half what a wire of its rating would and taking longer to burn out; and an EHV
// 8W gas-insulated bus carrying its full 16,777,216 FE/t with nothing overflowing.
class FactoryConductorTest {
    private static final GridSettings SETTINGS = GridSettings.DEFAULTS;

    // A three-phase run of `joints` joints of a kind, each a block long, from position 1 to joints + 1.
    private static GridGraph run(VoltageTier tier, int joints, GridEdge.Kind kind, int size, double derate) {
        GridGraph graph = new GridGraph();
        for (int k = 0; k < 3; k++) {
            for (int i = 1; i <= joints + 1; i++) {
                graph.addNode(new GridNode(GridFixtures.node(k, i), tier, GridFixtures.ABC[k]));
                if (i > 1) {
                    GridEdge edge = new GridEdge(GridFixtures.edge(k, i - 1), GridFixtures.node(k, i - 1), GridFixtures.node(k, i), tier,
                            CircuitType.THREE_PHASE, 1.0, kind, size);
                    edge.setDerate(derate);
                    graph.addEdge(edge);
                }
            }
        }
        return graph;
    }

    @Test
    void aThreeConductorConnectionJoinsPhaseToPhase() {
        GridGraph graph = new GridGraph();
        // One end labelled A B C, the other A C B (its B and C swapped): A meets A, B meets B, C meets C.
        Phase[] far = { Phase.A, Phase.C, Phase.B };
        for (int k = 0; k < 3; k++) {
            graph.addNode(new GridNode(10 + k, VoltageTier.HV, GridFixtures.ABC[k]));
            graph.addNode(new GridNode(20 + k, VoltageTier.HV, far[k]));
            graph.addNode(new GridNode(30 + k, VoltageTier.MV, GridFixtures.ABC[k]));
            graph.addNode(new GridNode(40 + k, VoltageTier.LV, new Phase[] { Phase.L1, Phase.N, Phase.L2 }[k]));
        }
        ConnectionRules.Pairing pairing = ConnectionRules.pairByPhase(graph, java.util.List.of(10L, 11L, 12L), java.util.List.of(20L, 21L, 22L));
        assertNull(pairing.refusal());
        assertEquals(3, pairing.pairs().size());
        assertEquals(20L, pairing.pairs().get(0)[1]);
        assertEquals(22L, pairing.pairs().get(1)[1]);
        assertEquals(21L, pairing.pairs().get(2)[1]);
        // Never across tiers or circuit types, nor twice.
        assertEquals(ConnectionRules.Refusal.MIXED_TIER, ConnectionRules.pairByPhase(graph, java.util.List.of(10L, 11L, 12L),
                java.util.List.of(30L, 31L, 32L)).refusal());
        assertEquals(ConnectionRules.Refusal.MISSING, ConnectionRules.pairByPhase(graph, java.util.List.of(10L, 11L), java.util.List.of(20L, 21L, 22L))
                .refusal());
        for (long[] pair : pairing.pairs()) {
            graph.addEdge(new GridEdge(pair[0] * 100, pair[0], pair[1], VoltageTier.HV, CircuitType.THREE_PHASE, 5.0, GridEdge.Kind.CABLE));
        }
        assertEquals(ConnectionRules.Refusal.ALREADY_CONNECTED, ConnectionRules.pairByPhase(graph, java.util.List.of(10L, 11L, 12L),
                java.util.List.of(20L, 21L, 22L)).refusal());
        // Each phase its own network, as three wires would be.
        assertEquals(3, graph.networks().size());
    }

    @Test
    void buswayIsRatedBySizeAndCableByItsDerating() {
        for (VoltageTier tier : VoltageTier.values()) {
            double w = tier.defaultRatedAmps();
            for (int size : new int[] { 2, 4, 8 }) {
                GridEdge bus = new GridEdge(1, 1, 2, tier, CircuitType.THREE_PHASE, 1.0, GridEdge.Kind.BUS, size);
                assertEquals(w * size, SETTINGS.ratedAmps(bus), 1.0E-9, tier + " " + size + "W");
                // Half a same-rated wire's resistance: a wire rated size x W would be size wires in parallel.
                assertEquals(tier.defaultResistance() / size * 0.5, SETTINGS.resistance(bus), 1.0E-12);
                assertEquals((long) tier.volts() * (long) (w * size), (long) (tier.volts() * SETTINGS.ratedAmps(bus)));
            }
            GridEdge cable = new GridEdge(2, 1, 2, tier, CircuitType.THREE_PHASE, 10.0, GridEdge.Kind.CABLE);
            assertEquals(w, SETTINGS.ratedAmps(cable), 1.0E-9);
            cable.setDerate(0.7);
            assertEquals(0.7 * w, SETTINGS.ratedAmps(cable), 1.0E-9);
        }
        // The largest: EHV 8W, 1,024 A at 16,384 V.
        GridEdge ehv = new GridEdge(1, 1, 2, VoltageTier.EHV, CircuitType.THREE_PHASE, 1.0, GridEdge.Kind.BUS, 8);
        assertEquals(1024.0, SETTINGS.ratedAmps(ehv), 1.0E-9);
        assertEquals(16_777_216L, Math.round(VoltageTier.EHV.volts() * SETTINGS.ratedAmps(ehv)));
    }

    @Test
    void anEhvGasInsulatedBusCarriesSixteenMillionFePerTick() {
        long full = 16_777_216L;
        GridGraph graph = run(VoltageTier.EHV, 8, GridEdge.Kind.BUS, 8, 1.0);
        GridFixtures.Buffers terminals = new GridFixtures.Buffers(VoltageTier.EHV).source(1, GridFixtures.at(1), Long.MAX_VALUE / 4, full)
                .sink(2, GridFixtures.at(9), full);
        int ticks = 300;
        for (int tick = 0; tick < ticks; tick++) {
            GridTicker.Report report = GridTicker.tick(graph, terminals, SETTINGS);
            assertTrue(report.burnt().isEmpty(), "burnt at tick " + tick);
            // Each conductor at its 1,024 A rating, a hair over for its own loss (the superconductor's next to none).
            double amps = report.result().amps(GridFixtures.edge(0, 4));
            assertEquals(1024.0, amps, 1.0, "amps");
            assertTrue(amps <= SETTINGS.ratedAmps(graph.edge(GridFixtures.edge(0, 4))) + 1.0E-6, "over its rating: " + amps);
        }
        // Every tick's 16,777,216 FE arrives (in total well past an int), less the bus's own tiny loss.
        assertTrue(terminals.deliveredTotal > (long) Integer.MAX_VALUE, "total " + terminals.deliveredTotal);
        assertEquals(full * ticks, terminals.deliveredTotal, full * ticks * 1.0E-4);
        assertEquals(terminals.drawnTotal, terminals.deliveredTotal, full * ticks * 1.0E-4);
    }

    @Test
    void buswayTakesLongerToBurnOutThanWire() {
        // 2W LV busway (32 A) carrying 48 A: half again its rating.
        GridGraph graph = run(VoltageTier.LV, 2, GridEdge.Kind.BUS, 2, 1.0);
        GridFixtures.Buffers terminals = new GridFixtures.Buffers(VoltageTier.LV).source(1, GridFixtures.at(1), 1_000_000_000L, 20_000)
                .sink(2, GridFixtures.at(3), 48 * 256);
        int bus = SETTINGS.burnoutTicks(graph.edge(GridFixtures.edge(0, 1)));
        assertEquals(SETTINGS.burnoutTicks() * 4, bus);
        for (int tick = 1; tick < bus; tick++) {
            assertTrue(GridTicker.tick(graph, terminals, SETTINGS).burnt().isEmpty(), "burnt early at tick " + tick);
        }
        assertEquals(6, GridTicker.tick(graph, terminals, SETTINGS).burnt().size());
        assertNull(graph.edge(GridFixtures.edge(1, 2)));
    }

    @Test
    void aDeratedCableBurnsOutAtItsDeratedRating() {
        // LV cable derated to 70% (11.2 A) carrying 14 A: under the wire's 16 A, over its own.
        GridGraph graph = run(VoltageTier.LV, 1, GridEdge.Kind.CABLE, 1, 0.7);
        GridFixtures.Buffers terminals = new GridFixtures.Buffers(VoltageTier.LV).source(1, GridFixtures.at(1), 1_000_000_000L, 8_000)
                .sink(2, GridFixtures.at(2), 14 * 256);
        for (int tick = 1; tick < SETTINGS.burnoutTicks(); tick++) {
            assertTrue(GridTicker.tick(graph, terminals, SETTINGS).burnt().isEmpty(), "burnt early at tick " + tick);
        }
        assertEquals(3, GridTicker.tick(graph, terminals, SETTINGS).burnt().size());
        // Not derated, the same cable carries it indefinitely.
        GridGraph full = run(VoltageTier.LV, 1, GridEdge.Kind.CABLE, 1, 1.0);
        GridFixtures.Buffers again = new GridFixtures.Buffers(VoltageTier.LV).source(1, GridFixtures.at(1), 1_000_000_000L, 8_000)
                .sink(2, GridFixtures.at(2), 14 * 256);
        for (int tick = 0; tick < 3 * SETTINGS.burnoutTicks(); tick++) {
            assertTrue(GridTicker.tick(full, again, SETTINGS).burnt().isEmpty());
        }
    }
}
