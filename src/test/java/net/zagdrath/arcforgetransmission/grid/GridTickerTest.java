/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

// Whole grid ticks with buffered terminals: energy is counted, overloads burn wires out after the configured ticks, and
// a current limit upstream stops that.
class GridTickerTest {
    private static final GridSettings SETTINGS = GridSettings.DEFAULTS;

    @Test
    void energyIsNeverMade() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 2, 16);
        GridFixtures.Buffers terminals = new GridFixtures.Buffers(VoltageTier.LV).source(1, GridFixtures.at(1), 1_000_000, 1024)
                .sink(2, GridFixtures.at(4), 900);
        long before = terminals.stored.values().stream().mapToLong(Long::longValue).sum();
        double lost = 0;
        for (int tick = 0; tick < 200; tick++) {
            lost += GridTicker.tick(graph, terminals, SETTINGS).result().totalLoss();
        }
        long after = terminals.stored.values().stream().mapToLong(Long::longValue).sum();
        assertTrue(after <= before - lost + 1.0E-6, "made energy: " + before + " -> " + after + " with " + lost + " lost");
        // Rounding loses at most a couple of FE a tick.
        assertTrue(before - after - lost <= 2 * 200, "lost too much to rounding: " + (before - after - lost));
        assertEquals(900L * 200, terminals.deliveredTotal);
    }

    @Test
    void anOverloadedWireBurnsOutAfterTheConfiguredTicks() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 1, 8);
        // 2048 FE/t at 128 V is 16 A in each conductor of 8 A wire.
        GridFixtures.Buffers terminals = new GridFixtures.Buffers(VoltageTier.LV).source(1, GridFixtures.at(1), 10_000_000, 4096)
                .sink(2, GridFixtures.at(3), 2048);
        List<FaultEvent> faults = new ArrayList<>();
        Consumer<FaultEvent> listener = faults::add;
        GridFaults.listen(listener);
        try {
            for (int tick = 1; tick < SETTINGS.burnoutTicks(); tick++) {
                assertTrue(GridTicker.tick(graph, terminals, SETTINGS).burnt().isEmpty(), "burnt early at tick " + tick);
            }
            GridTicker.Report report = GridTicker.tick(graph, terminals, SETTINGS);
            // Both spans of all three conductors carry the full 16 A, so all six go.
            assertEquals(6, report.burnt().size(), "burnt");
            assertNull(graph.edge(GridFixtures.edge(0, 1)));
            assertNull(graph.edge(GridFixtures.edge(2, 2)));
        } finally {
            GridFaults.unlisten(listener);
        }
        long overcurrents = faults.stream().filter(f -> f.cause() == FaultEvent.Cause.OVERCURRENT).count();
        long burnouts = faults.stream().filter(f -> f.cause() == FaultEvent.Cause.OVERCURRENT_BURNOUT).count();
        assertTrue(overcurrents >= SETTINGS.burnoutTicks(), "overcurrent events " + overcurrents);
        assertEquals(6L, burnouts);
        assertEquals(16.0, faults.get(faults.size() - 1).magnitude(), 0.5);
        // With its wires gone, nothing more moves.
        long delivered = terminals.deliveredTotal;
        GridTicker.tick(graph, terminals, SETTINGS);
        assertEquals(delivered, terminals.deliveredTotal);
    }

    @Test
    void aBriefOverloadDoesNotBurn() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 1, 8);
        GridFixtures.Buffers terminals = new GridFixtures.Buffers(VoltageTier.LV).source(1, GridFixtures.at(1), 10_000_000, 4096)
                .sink(2, GridFixtures.at(3), 2048);
        for (int tick = 0; tick < SETTINGS.burnoutTicks() - 1; tick++) {
            GridTicker.tick(graph, terminals, SETTINGS);
        }
        terminals.demand.put(2L, 500.0);
        GridTicker.tick(graph, terminals, SETTINGS);
        terminals.demand.put(2L, 2048.0);
        for (int tick = 0; tick < SETTINGS.burnoutTicks() - 1; tick++) {
            assertTrue(GridTicker.tick(graph, terminals, SETTINGS).burnt().isEmpty());
        }
        assertNotNull(graph.edge(GridFixtures.edge(0, 1)));
    }

    @Test
    void anInductorsCurrentLimitPreventsTheBurnout() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 1, 8);
        // The source's inductors are set to the wire's 8 A rating.
        GridFixtures.Buffers terminals = new GridFixtures.Buffers(VoltageTier.LV).source(1, GridFixtures.at(1), 10_000_000, 4096)
                .sink(2, GridFixtures.at(3), 2048).limit(1, 8.0);
        for (int tick = 0; tick < SETTINGS.burnoutTicks() * 3; tick++) {
            assertTrue(GridTicker.tick(graph, terminals, SETTINGS).burnt().isEmpty(), "burnt at tick " + tick);
        }
        assertEquals(6, graph.edges().size());
        assertTrue(terminals.deliveredTotal > 0);
    }

    @Test
    void anUnloadedPortNeitherGivesNorTakes() {
        GridGraph graph = GridFixtures.line(VoltageTier.MV, 2, 12);
        // The sink's chunk isn't loaded: it isn't offered at all, so it wants nothing.
        GridFixtures.Buffers terminals = new GridFixtures.Buffers(VoltageTier.MV).source(1, GridFixtures.at(1), 1000, 1000)
                .sink(2, GridFixtures.at(4), 1000).unload(2);
        GridTicker.tick(graph, terminals, SETTINGS);
        assertEquals(1000L, terminals.stored.get(1L).longValue());
        assertEquals(0L, terminals.drawnTotal);
    }

    @Test
    void separateCircuitsRunApart() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 1, 4);
        for (int k = 0; k < 3; k++) {
            graph.addNode(new GridNode(50 + k, VoltageTier.LV, GridFixtures.ABC[k]));
            graph.addNode(new GridNode(60 + k, VoltageTier.LV, GridFixtures.ABC[k]));
            graph.addEdge(new GridEdge(500 + k, 50 + k, 60 + k, VoltageTier.LV, CircuitType.THREE_PHASE, 4));
        }
        assertEquals(6, graph.networks().size());
        GridFixtures.Buffers terminals = new GridFixtures.Buffers(VoltageTier.LV).source(1, GridFixtures.at(1), 100_000, 1000)
                .sink(2, List.of(60L, 61L, 62L), 1000);
        GridTicker.tick(graph, terminals, SETTINGS);
        assertEquals(0L, terminals.deliveredTotal);
        assertFalse(graph.networkOf(1) == graph.networkOf(60));
    }
}
