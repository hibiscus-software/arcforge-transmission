/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

// The Phase 4 audit's solver fixes, each pinned by a test: honest port statuses, voltage shares that keep their port
// and recover, port limits only on their own traffic, reactive losses billed per island, parallel transformers and line
// loops sharing load, fault currents capped wire by wire with reactance, capacitor banks not skewing node voltages, ids
// that can't collide or overflow, and energy conserved through transformers, shunts and storage.
class SolverAuditTest {
    private static final GridSettings S = GridSettings.DEFAULTS;
    private static final double INF = Double.POSITIVE_INFINITY;

    private static Port source(long id, VoltageTier tier, List<Long> nodes, double offer) {
        return new Port(id, tier, nodes, offer, 0, INF);
    }

    private static Port sink(long id, VoltageTier tier, List<Long> nodes, double demand) {
        return new Port(id, tier, nodes, 0, demand, INF);
    }

    private static FlowSolver.Result solve(GridGraph graph, List<Port> ports, List<Coupling> couplings, List<Shunt> shunts, FlowSolver.State state) {
        return FlowSolver.solve(graph, ports, couplings, shunts, Set.of(), S, state);
    }

    // --- 2. Honest port status ----------------------------------------------------------------------------------------

    @Test
    void portStatusSaysWhyNothingMoves() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 2, 4);
        // Paired: complete.
        FlowSolver.Result paired = FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, GridFixtures.at(1), 500), sink(2, VoltageTier.LV,
                GridFixtures.at(4), 200)), S);
        assertEquals(FlowSolver.PortStatus.OK, paired.status(1));
        assertEquals(FlowSolver.PortStatus.OK, paired.status(2));
        // A source with nothing to feed: wired fine, no partner.
        FlowSolver.Result alone = FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, GridFixtures.at(1), 500)), S);
        assertEquals(FlowSolver.PortStatus.NO_PARTNER, alone.status(1));
        // A load on a line no source feeds: dead.
        FlowSolver.Result dead = FlowSolver.solve(graph, List.of(sink(2, VoltageTier.LV, GridFixtures.at(4), 200)), S);
        assertEquals(FlowSolver.PortStatus.DEAD, dead.status(2));
        // An idle port (neither gives nor takes) on a live line: no partner, not "complete".
        FlowSolver.Result idle = FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, GridFixtures.at(1), 500), sink(2, VoltageTier.LV,
                GridFixtures.at(4), 200), new Port(3, VoltageTier.LV, GridFixtures.at(3), 0, 0, INF)), S);
        assertEquals(FlowSolver.PortStatus.NO_PARTNER, idle.status(3));
    }

    // --- 3. Voltage share ---------------------------------------------------------------------------------------------

    // Lossy settings: a short line sags deeply.
    private static final GridSettings LOSSY = new GridSettings() {
        @Override
        public double resistance(VoltageTier tier) {
            return 2.0;
        }

        @Override
        public double ratedAmps(VoltageTier tier) {
            return 1000;
        }

        @Override
        public double maxSpan(VoltageTier tier) {
            return 64;
        }

        @Override
        public int burnoutTicks() {
            return 40;
        }
    };

    @Test
    void aStarvedLoadKeepsItsShareByItsNodesAndRecovers() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 3, 16);
        FlowSolver.State state = new FlowSolver.State();
        // A deep sag: the load's share falls (but never to nothing: it stays a load).
        for (int tick = 0; tick < 20; tick++) {
            // The ports are numbered afresh each tick (as GridManager does): the share must follow the load, not the number.
            long sinkId = tick % 2 == 0 ? 2 : 7;
            FlowSolver.solve(graph, List.of(source(tick % 2 == 0 ? 1 : 3, VoltageTier.LV, GridFixtures.at(1), 20000), sink(sinkId, VoltageTier.LV,
                    GridFixtures.at(5), 8000)), List.of(), List.of(), Set.of(), LOSSY, state);
        }
        long key = GridFixtures.at(5).get(0);
        assertTrue(state.share(key) < 0.5, "starved: " + state.share(key));
        assertTrue(state.share(key) >= S.minShare(), "but never below the floor");
        // The sag ends (the line made good): within a few ticks it has its full share back.
        for (int tick = 0; tick < 8; tick++) {
            FlowSolver.solve(graph, List.of(source(9, VoltageTier.LV, GridFixtures.at(1), 20000), sink(8, VoltageTier.LV, GridFixtures.at(5), 500)),
                    List.of(), List.of(), Set.of(), S, state);
        }
        assertTrue(state.share(key) > 0.95, "recovered: " + state.share(key));
    }

    @Test
    void aLoadWithNoReadingRecovers() {
        FlowSolver.State state = new FlowSolver.State();
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 3, 16);
        for (int tick = 0; tick < 20; tick++) {
            FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, GridFixtures.at(1), 20000), sink(2, VoltageTier.LV, GridFixtures.at(5), 8000)),
                    List.of(), List.of(), Set.of(), LOSSY, state);
        }
        long key = GridFixtures.at(5).get(0);
        // Its source gone (no reading at all): the share comes back rather than stay stuck low.
        for (int tick = 0; tick < 8; tick++) {
            FlowSolver.solve(graph, List.of(sink(2, VoltageTier.LV, GridFixtures.at(5), 8000)), List.of(), List.of(), Set.of(), S, state);
        }
        assertTrue(state.share(key) > 0.95, "recovered with no reading: " + state.share(key));
    }

    // --- 4. Port limits -----------------------------------------------------------------------------------------------

    @Test
    void anIdleLimitedPortDoesNotCapThroughTraffic() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 3, 4);
        FlowSolver.Result result = FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, GridFixtures.at(1), 2000), sink(2, VoltageTier.LV,
                GridFixtures.at(5), 600), new Port(3, VoltageTier.LV, GridFixtures.at(3), 0, 0, 1.0)), S);
        assertEquals(600, result.delivered(2), 1.0E-6, "the 1 A port on the middle pole doesn't cap the line");
        // But a limited port still caps its own traffic.
        FlowSolver.Result own = FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, GridFixtures.at(1), 2000), new Port(2, VoltageTier.LV,
                GridFixtures.at(5), 0, 600, 1.0)), S);
        assertEquals(VoltageTier.LV.volts() * 1.0, own.delivered(2), 1.0E-6);
    }

    // --- 5. Reactive losses per island ------------------------------------------------------------------------------

    @Test
    void anUnconnectedSourcePaysNoOtherIslandsLosses() {
        GridGraph graph = GridFixtures.line(VoltageTier.MV, 8, 24);
        // A second, separate line (nodes 50 000 +) with only a source on it.
        for (int k = 0; k < 3; k++) {
            graph.addNode(new GridNode(50_000 + k * 10 + 1, VoltageTier.MV, GridFixtures.ABC[k]));
            graph.addNode(new GridNode(50_000 + k * 10 + 2, VoltageTier.MV, GridFixtures.ABC[k]));
            graph.addEdge(new GridEdge(60_000 + k, 50_000 + k * 10 + 1, 50_000 + k * 10 + 2, VoltageTier.MV, CircuitType.THREE_PHASE, 8));
        }
        List<Port> ports = List.of(source(1, VoltageTier.MV, GridFixtures.at(1), 9000), sink(2, VoltageTier.MV, GridFixtures.at(10), 3000),
                source(3, VoltageTier.MV, List.of(50_001L, 50_011L, 50_021L), 9000));
        FlowSolver.Result result = solve(graph, ports, List.of(), List.of(new Shunt(9001, VoltageTier.MV, GridFixtures.at(10), 4000, true)),
                new FlowSolver.State());
        assertEquals(0, result.drawn(3), 1.0E-9, "the other island's source pays nothing");
        assertTrue(result.drawn(1) > 3000, "its own source pays its losses");
    }

    // --- 6. Parallel paths ------------------------------------------------------------------------------------------

    // An MV line (ports at 1 and 4) and an LV line (nodes 10 000 +) joined at both ends by `couplings` transformers.
    private static GridGraph twoTiers() {
        GridGraph graph = GridFixtures.line(VoltageTier.MV, 2, 8);
        for (int k = 0; k < 3; k++) {
            for (int i = 1; i <= 4; i++) {
                graph.addNode(new GridNode(10_000 + GridFixtures.node(k, i), VoltageTier.LV, GridFixtures.ABC[k]));
                if (i > 1) {
                    graph.addEdge(new GridEdge(10_000 + GridFixtures.edge(k, i - 1), 10_000 + GridFixtures.node(k, i - 1),
                            10_000 + GridFixtures.node(k, i), VoltageTier.LV, CircuitType.THREE_PHASE, 4));
                }
            }
        }
        return graph;
    }

    private static List<Long> lvAt(int i) {
        return GridFixtures.at(i).stream().map(n -> 10_000 + n).toList();
    }

    @Test
    void parallelTransformersBothCarry() {
        GridGraph graph = twoTiers();
        List<Coupling> both = List.of(new Coupling(77, VoltageTier.MV, GridFixtures.at(4), VoltageTier.LV, List.of(lvAt(1)), 300, 0, true),
                new Coupling(78, VoltageTier.MV, GridFixtures.at(3), VoltageTier.LV, List.of(lvAt(2)), 300, 0, true));
        FlowSolver.Result result = solve(graph, List.of(source(1, VoltageTier.MV, GridFixtures.at(1), 5000), sink(2, VoltageTier.LV, lvAt(4), 550)),
                both, List.of(), new FlowSolver.State());
        assertEquals(550, result.delivered(2), 1.0E-6, "two transformers deliver more than one's rating");
        assertTrue(Math.abs(result.couplingFlow().get(77L)) <= 300 + 1.0E-6 && Math.abs(result.couplingFlow().get(78L)) <= 300 + 1.0E-6,
                "neither over its rating: " + result.couplingFlow());
        FlowSolver.Result full = solve(graph, List.of(source(1, VoltageTier.MV, GridFixtures.at(1), 5000), sink(2, VoltageTier.LV, lvAt(4), 2000)),
                both, List.of(), new FlowSolver.State());
        assertEquals(600, full.delivered(2), 1.0E-6, "both ratings together");
    }

    @Test
    void aLoopSplitsAndNeitherLegBurns() {
        // Phase conductors 1 -> 2 directly (8 blocks) and 1 -> 3 -> 2 (4 + 4): a loop, the two legs alike.
        GridGraph graph = new GridGraph();
        for (int k = 0; k < 3; k++) {
            for (int i = 1; i <= 4; i++) {
                graph.addNode(new GridNode(GridFixtures.node(k, i), VoltageTier.LV, GridFixtures.ABC[k]));
            }
            graph.addEdge(new GridEdge(GridFixtures.edge(k, 1), GridFixtures.node(k, 1), GridFixtures.node(k, 2), VoltageTier.LV, CircuitType.THREE_PHASE, 8));
            graph.addEdge(new GridEdge(GridFixtures.edge(k, 2), GridFixtures.node(k, 1), GridFixtures.node(k, 3), VoltageTier.LV, CircuitType.THREE_PHASE, 4));
            graph.addEdge(new GridEdge(GridFixtures.edge(k, 3), GridFixtures.node(k, 3), GridFixtures.node(k, 2), VoltageTier.LV, CircuitType.THREE_PHASE, 4));
            graph.addEdge(new GridEdge(GridFixtures.edge(k, 4), GridFixtures.node(k, 2), GridFixtures.node(k, 4), VoltageTier.LV, CircuitType.THREE_PHASE, 1));
        }
        double rated = S.ratedAmps(VoltageTier.LV), load = 1.5 * rated * VoltageTier.LV.volts();
        FlowSolver.Result result = FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, GridFixtures.at(1), 3 * load), sink(2, VoltageTier.LV,
                GridFixtures.at(4), load)), S);
        double direct = result.amps(GridFixtures.edge(0, 1)), around = result.amps(GridFixtures.edge(0, 2));
        assertEquals(direct, around, 0.05 * direct, "split by impedance: " + direct + " and " + around);
        assertTrue(direct <= rated && around <= rated, "neither leg over its rating");
        assertEquals(1.5 * rated, direct + around, 0.01 * rated, "together they carry it all");
    }

    // --- 8. Fault study -----------------------------------------------------------------------------------------------

    @Test
    void twoSourcesFeedAFaultCappedWireByWire() {
        GridGraph graph = GridFixtures.line(VoltageTier.MV, 4, 1);
        List<Port> ports = List.of(source(1, VoltageTier.MV, GridFixtures.at(1), 1000), source(2, VoltageTier.MV, GridFixtures.at(6), 1000));
        FlowSolver.FaultFlow flow = FlowSolver.fault(graph, ports, List.of(), GridFixtures.node(0, 3), Set.of(), S);
        double cap = S.faultMultiple() * S.ratedAmps(VoltageTier.MV);
        assertEquals(cap, flow.amps(), 1.0E-6, "the total is capped");
        double west = flow.amps(GridFixtures.edge(0, 1)), east = flow.amps(GridFixtures.edge(0, 5));
        assertEquals(cap, west + east, 1.0E-6, "each source's share scaled with the total: " + west + " + " + east);
        assertTrue(west > 0 && east > 0, "both feed it");
        // Reactance counts: a long, reactive line gives less than its resistance alone would.
        GridGraph longLine = GridFixtures.line(VoltageTier.EHV, 20, 64);
        FlowSolver.FaultFlow far = FlowSolver.fault(longLine, List.of(source(1, VoltageTier.EHV, GridFixtures.at(1), 1000)), List.of(),
                GridFixtures.node(0, 22), Set.of(), S);
        double resistiveOnly = VoltageTier.EHV.volts() / (S.sourceImpedance(VoltageTier.EHV) + S.resistance(VoltageTier.EHV) * 64 * 21);
        assertTrue(far.amps() < resistiveOnly * 0.9, "reactance limits it: " + far.amps() + " against " + resistiveOnly);
    }

    // --- 9. Capacitor banks don't skew node voltages ------------------------------------------------------------------

    @Test
    void aBankDoesNotPullItsNodeTowardNominal() {
        GridGraph graph = GridFixtures.line(VoltageTier.MV, 14, 24);
        double rated = VoltageTier.MV.defaultMaxPower();
        FlowSolver.State state = new FlowSolver.State();
        FlowSolver.Result result = null;
        for (int tick = 0; tick < 6; tick++) {
            result = solve(graph, List.of(source(1, VoltageTier.MV, GridFixtures.at(1), 2 * rated), sink(2, VoltageTier.MV, GridFixtures.at(16), rated)),
                    List.of(), List.of(new Shunt(9, VoltageTier.MV, GridFixtures.at(16), rated * 0.3, true)), state);
        }
        double node = result.nodeVolts(GridFixtures.node(0, 16)), port = result.volts(2);
        assertEquals(port, node, 0.002, "the bank's node reads the real flow's voltage");
    }

    // --- 10. Ids ------------------------------------------------------------------------------------------------------

    @Test
    void hugeCouplingIdsAndShuntIdsLikePortIds() {
        GridGraph graph = twoTiers();
        Coupling huge = new Coupling(Long.MAX_VALUE / 3, VoltageTier.MV, GridFixtures.at(4), VoltageTier.LV, List.of(lvAt(1)), 5000, 0, true);
        FlowSolver.Result result = solve(graph, List.of(source(1, VoltageTier.MV, GridFixtures.at(1), 5000), sink(2, VoltageTier.LV, lvAt(4), 500)),
                List.of(huge), List.of(new Shunt(2, VoltageTier.LV, lvAt(4), 100, true)), new FlowSolver.State());
        assertEquals(500, result.delivered(2), 1.0E-6, "a coupling id near the top of long, and a shunt numbered like a port");
        assertEquals(FlowSolver.PortStatus.OK, result.status(2));
    }

    // --- 25. Energy conservation and several islands ---------------------------------------------------------------

    @Test
    void energyIsConservedThroughTransformersShuntsAndStorage() {
        GridGraph graph = twoTiers();
        List<Port> ports = new ArrayList<>(List.of(source(1, VoltageTier.MV, GridFixtures.at(1), 900), sink(2, VoltageTier.LV, lvAt(4), 500),
                new Port(3, VoltageTier.LV, lvAt(3), 600, 600, INF, true, 1.0)));
        FlowSolver.Result result = solve(graph, ports, List.of(new Coupling(77, VoltageTier.MV, GridFixtures.at(4), VoltageTier.LV, List.of(lvAt(1)), 5000,
                0, true)), List.of(new Shunt(9001, VoltageTier.LV, lvAt(4), 200, true)), new FlowSolver.State());
        double drawn = result.totalDrawn(), delivered = result.totalDelivered();
        assertTrue(drawn >= delivered - 1.0E-6, "never made: " + drawn + " drawn, " + delivered + " delivered");
        assertEquals(result.totalLoss(), drawn - delivered, 1.0E-3 * Math.max(1, drawn), "what's drawn is what's delivered plus the losses");
        for (Port port : ports) {
            assertTrue(result.drawn(port.id()) <= port.offer() + 1.0E-6, "nothing drawn past its offer");
        }
    }

    @Test
    void separateIslandsAreServedApart() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 1, 4);
        for (int k = 0; k < 3; k++) {
            for (int i = 1; i <= 3; i++) {
                graph.addNode(new GridNode(70_000 + GridFixtures.node(k, i), VoltageTier.LV, GridFixtures.ABC[k]));
                if (i > 1) {
                    graph.addEdge(new GridEdge(70_000 + GridFixtures.edge(k, i - 1), 70_000 + GridFixtures.node(k, i - 1), 70_000 + GridFixtures.node(k, i),
                            VoltageTier.LV, CircuitType.THREE_PHASE, 4));
                }
            }
        }
        List<Long> other1 = GridFixtures.at(1).stream().map(n -> 70_000 + n).toList(), other3 = GridFixtures.at(3).stream().map(n -> 70_000 + n).toList();
        FlowSolver.Result result = FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, GridFixtures.at(1), 300), sink(2, VoltageTier.LV,
                GridFixtures.at(3), 800), source(3, VoltageTier.LV, other1, 900), sink(4, VoltageTier.LV, other3, 100)), S);
        assertTrue(result.delivered(2) <= 300, "the first island's load gets only its own source's");
        assertEquals(100, result.delivered(4), 1.0E-6);
        assertTrue(result.drawn(3) < 110, "the second source serves only its own island's load");
    }

    // --- 11. Speed ----------------------------------------------------------------------------------------------------

    @Test
    void aLongBusyLineSolvesQuickly() {
        int towers = 200;
        GridGraph graph = GridFixtures.line(VoltageTier.MV, towers, 24);
        List<Port> ports = new ArrayList<>();
        long id = 1;
        // Ten generators along it, and a load at nearly every other tower (about 200 ports in all).
        for (int i = 1; i <= towers + 2; i++) {
            if (i % 20 == 1) {
                ports.add(source(id++, VoltageTier.MV, GridFixtures.at(i), 6000));
            } else {
                ports.add(sink(id++, VoltageTier.MV, GridFixtures.at(i), 250));
            }
        }
        FlowSolver.State state = new FlowSolver.State();
        for (int warm = 0; warm < 40; warm++) {
            FlowSolver.solve(graph, ports, List.of(), List.of(), Set.of(), S, state);
        }
        int ticks = 20;
        long start = System.nanoTime();
        for (int tick = 0; tick < ticks; tick++) {
            FlowSolver.solve(graph, ports, List.of(), List.of(), Set.of(), S, state);
        }
        double ms = (System.nanoTime() - start) / 1.0E6 / ticks;
        System.out.println("200-tower line, " + ports.size() + " ports: " + String.format("%.2f", ms) + " ms a tick");
        // The target is under 5 ms on a dev machine; the bound is generous so only a real regression fails the build.
        assertTrue(ms < 40, "too slow: " + ms + " ms a tick");
    }

    @SuppressWarnings("unused")
    private static Map<Long, FlowSolver.Protective> none() {
        return Map.of();
    }
}
