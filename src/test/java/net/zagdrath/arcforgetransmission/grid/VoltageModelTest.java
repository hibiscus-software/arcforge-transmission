/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

// The voltage model (FlowSolver): drop along a feeder and loads served less as it sags, a regulator's boost, capacitor
// banks and reactors, transformers either way with their taps, batteries on surplus and shortfall, grounded lines and
// lost phases.
class VoltageModelTest {
    private static final GridSettings S = GridSettings.DEFAULTS;
    private static final double INF = Double.POSITIVE_INFINITY;

    private static Port source(long id, VoltageTier tier, List<Long> nodes, double offer) {
        return new Port(id, tier, nodes, offer, 0, INF);
    }

    private static Port sink(long id, VoltageTier tier, List<Long> nodes, double demand) {
        return new Port(id, tier, nodes, 0, demand, INF);
    }

    // A full MV load (rated power) down a feeder of `spans` longest spans; the solve settled over several ticks.
    private static FlowSolver.Result feeder(int spans, List<Shunt> shunts) {
        GridGraph graph = GridFixtures.line(VoltageTier.MV, spans - 1, VoltageTier.MV.defaultMaxSpan());
        double rated = VoltageTier.MV.defaultMaxPower();
        List<Port> ports = List.of(source(1, VoltageTier.MV, GridFixtures.at(1), 2 * rated), sink(2, VoltageTier.MV, GridFixtures.at(spans + 1), rated));
        FlowSolver.State state = new FlowSolver.State();
        FlowSolver.Result result = null;
        for (int tick = 0; tick < 10; tick++) {
            result = FlowSolver.solve(graph, ports, List.of(), shunts, Set.of(), S, state);
        }
        return result;
    }

    @Test
    void voltageSagsAlongAFeederAndLoadsGetLess() {
        FlowSolver.Result shortLine = feeder(2, List.of()), longLine = feeder(16, List.of());
        assertEquals(1.0, shortLine.volts(1), 1.0E-9, "the source holds its setpoint");
        assertTrue(shortLine.volts(2) > 0.97, "a short feeder hardly sags: " + shortLine.volts(2));
        assertTrue(longLine.volts(2) < shortLine.volts(2), "a long one sags more");
        assertTrue(longLine.volts(2) < S.fullPowerVoltage() + 0.02, "sixteen full spans sag to about the full-power line: " + longLine.volts(2));
        // Each node down the line is lower than the one before.
        double previous = 2;
        for (int i = 1; i <= 17; i++) {
            double v = longLine.nodeVolts(GridFixtures.node(0, i));
            assertTrue(v <= previous + 1.0E-9, "node " + i + " " + v + " above " + previous);
            previous = v;
        }
        // Far enough that the voltage falls under the full-power line, the load gets less than it asks.
        FlowSolver.Result veryLong = feeder(30, List.of());
        assertTrue(veryLong.volts(2) < S.fullPowerVoltage(), "thirty spans: " + veryLong.volts(2));
        assertTrue(veryLong.delivered(2) < VoltageTier.MV.defaultMaxPower() * 0.99, "the sagging load is cut back: " + veryLong.delivered(2));
        assertTrue(veryLong.delivered(2) > 0, "but not off");
        assertTrue(veryLong.drawn(1) >= veryLong.delivered(2), "never makes energy");
    }

    @Test
    void theShareFollowsTheVoltage() {
        assertEquals(1.0, FlowSolver.share(0.95, S), 1.0E-9);
        assertEquals(0.0, FlowSolver.share(0.6, S), 1.0E-9);
        assertEquals(0.5, FlowSolver.share((S.fullPowerVoltage() + S.cutoffVoltage()) / 2, S), 1.0E-9);
    }

    @Test
    void aRegulatorBoostsWhatsPastIt() {
        int spans = 16;
        GridGraph graph = GridFixtures.line(VoltageTier.MV, spans - 1, VoltageTier.MV.defaultMaxSpan());
        // Halfway, each phase's span becomes a regulator: a jumper boosting 10% toward the load.
        int mid = spans / 2;
        for (int k = 0; k < 3; k++) {
            graph.removeEdge(GridFixtures.edge(k, mid));
            GridEdge link = new GridEdge(GridFixtures.edge(k, mid), GridFixtures.node(k, mid), GridFixtures.node(k, mid + 1), VoltageTier.MV,
                    CircuitType.THREE_PHASE, 0.0, GridEdge.Kind.JUMPER);
            link.setBoost(0.1);
            graph.addEdge(link);
        }
        double rated = VoltageTier.MV.defaultMaxPower();
        List<Port> ports = List.of(source(1, VoltageTier.MV, GridFixtures.at(1), 2 * rated), sink(2, VoltageTier.MV, GridFixtures.at(spans + 1), rated));
        FlowSolver.Result result = FlowSolver.solve(graph, ports, S);
        FlowSolver.Result plain = feeder(spans - 1, List.of());
        assertTrue(result.volts(2) > plain.volts(2) + 0.05, "boosted " + result.volts(2) + " against " + plain.volts(2));
        assertTrue(result.nodeVolts(GridFixtures.node(0, mid + 1)) > result.nodeVolts(GridFixtures.node(0, mid)) + 0.09,
                "the step is at the regulator");
    }

    @Test
    void capacitorBanksRaiseAndReactorsLower() {
        int spans = 16;
        double vars = VoltageTier.MV.defaultMaxPower() * 0.6;
        FlowSolver.Result none = feeder(spans, List.of());
        FlowSolver.Result capacitor = feeder(spans, List.of(new Shunt(9, VoltageTier.MV, GridFixtures.at(spans + 1), vars, true)));
        FlowSolver.Result reactor = feeder(spans, List.of(new Shunt(9, VoltageTier.MV, GridFixtures.at(spans + 1), -vars, true)));
        FlowSolver.Result switchedOut = feeder(spans, List.of(new Shunt(9, VoltageTier.MV, GridFixtures.at(spans + 1), vars, false)));
        assertTrue(capacitor.volts(2) > none.volts(2) + 0.02, "a capacitor bank raises: " + capacitor.volts(2) + " against " + none.volts(2));
        assertTrue(reactor.volts(2) < none.volts(2) - 0.02, "a reactor lowers: " + reactor.volts(2) + " against " + none.volts(2));
        assertEquals(none.volts(2), switchedOut.volts(2), 1.0E-9, "switched out, nothing");
        assertTrue(capacitor.drawn(1) >= capacitor.delivered(2), "never makes energy");
    }

    // An MV line (ports at 1 and 4) joined by a transformer at its far end to an LV line (nodes 10 000 +).
    private static GridGraph twoTiers() {
        GridGraph graph = GridFixtures.line(VoltageTier.MV, 2, 16);
        for (int k = 0; k < 3; k++) {
            for (int i = 1; i <= 4; i++) {
                graph.addNode(new GridNode(10_000 + GridFixtures.node(k, i), VoltageTier.LV, GridFixtures.ABC[k]));
                if (i > 1) {
                    graph.addEdge(new GridEdge(10_000 + GridFixtures.edge(k, i - 1), 10_000 + GridFixtures.node(k, i - 1),
                            10_000 + GridFixtures.node(k, i), VoltageTier.LV, CircuitType.THREE_PHASE, 8));
                }
            }
        }
        return graph;
    }

    private static List<Long> lvAt(int i) {
        return GridFixtures.at(i).stream().map(n -> 10_000 + n).toList();
    }

    private static Coupling transformer(double tap) {
        return new Coupling(77, VoltageTier.MV, GridFixtures.at(4), VoltageTier.LV, List.of(lvAt(1)), VoltageTier.MV.defaultMaxPower(), tap, true);
    }

    @Test
    void transformersCarryPowerEitherWay() {
        GridGraph graph = twoTiers();
        FlowSolver.Result down = FlowSolver.solve(graph, List.of(source(1, VoltageTier.MV, GridFixtures.at(1), 1000), sink(2, VoltageTier.LV, lvAt(4), 500)),
                List.of(transformer(0)), List.of(), Set.of(), S, new FlowSolver.State());
        assertEquals(500, down.delivered(2), 1.0E-6, "stepped down");
        assertTrue(down.drawn(1) > 500, "with losses on both lines");
        assertEquals(500, down.couplingFlow().get(77L), 1.0E-6);
        FlowSolver.Result up = FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, lvAt(4), 1000), sink(2, VoltageTier.MV, GridFixtures.at(1), 500)),
                List.of(transformer(0)), List.of(), Set.of(), S, new FlowSolver.State());
        assertEquals(500, up.delivered(2), 1.0E-6, "stepped up, the same transformer");
        // The LV side's current is the MV side's times four (1024 V / 256 V).
        double mvAmps = down.amps(GridFixtures.edge(0, 1)), lvAmps = down.amps(10_000 + GridFixtures.edge(0, 3));
        assertEquals(4.0, lvAmps / mvAmps, 1.0E-6);
        FlowSolver.Result out = FlowSolver.solve(graph, List.of(source(1, VoltageTier.MV, GridFixtures.at(1), 1000), sink(2, VoltageTier.LV, lvAt(4), 500)),
                List.of(new Coupling(77, VoltageTier.MV, GridFixtures.at(4), VoltageTier.LV, List.of(lvAt(1)), 1.0E9, 0, false)), List.of(), Set.of(), S,
                new FlowSolver.State());
        assertEquals(0, out.delivered(2), 1.0E-9, "out of service, nothing crosses");
    }

    @Test
    void aTapRaisesTheSecondary() {
        GridGraph graph = twoTiers();
        List<Port> ports = List.of(source(1, VoltageTier.MV, GridFixtures.at(1), 4000), sink(2, VoltageTier.LV, lvAt(4), 600));
        FlowSolver.Result flat = FlowSolver.solve(graph, ports, List.of(transformer(0)), List.of(), Set.of(), S, new FlowSolver.State());
        FlowSolver.Result tapped = FlowSolver.solve(graph, ports, List.of(transformer(0.05)), List.of(), Set.of(), S, new FlowSolver.State());
        assertEquals(flat.volts(2) * 1.05, tapped.volts(2), 0.01);
    }

    @Test
    void transformerRatingCapsWhatPasses() {
        GridGraph graph = twoTiers();
        Coupling small = new Coupling(77, VoltageTier.MV, GridFixtures.at(4), VoltageTier.LV, List.of(lvAt(1)), 300, 0, true);
        FlowSolver.Result result = FlowSolver.solve(graph, List.of(source(1, VoltageTier.MV, GridFixtures.at(1), 1000), sink(2, VoltageTier.LV, lvAt(4), 500)),
                List.of(small), List.of(), Set.of(), S, new FlowSolver.State());
        assertEquals(300, result.couplingFlow().get(77L), 1.0E-6);
    }

    @Test
    void batteriesTakeTheSurplusAndCoverTheShortfall() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 1, 4);
        Port battery = new Port(3, VoltageTier.LV, GridFixtures.at(2), 1000, 1000, INF, true, 1.0);
        FlowSolver.Result surplus = FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, GridFixtures.at(1), 1000), sink(2, VoltageTier.LV,
                GridFixtures.at(3), 600), battery), S);
        assertEquals(600, surplus.delivered(2), 1.0E-6);
        assertTrue(surplus.delivered(3) > 300 && surplus.delivered(3) <= 400 + 1.0E-6, "the battery charges from what's spare: " + surplus.delivered(3));
        assertEquals(0, surplus.drawn(3), 1.0E-9, "and gives nothing");
        FlowSolver.Result shortfall = FlowSolver.solve(graph, List.of(source(1, VoltageTier.LV, GridFixtures.at(1), 200), sink(2, VoltageTier.LV,
                GridFixtures.at(3), 600), battery), S);
        assertTrue(shortfall.delivered(2) > 595, "the load is covered: " + shortfall.delivered(2));
        assertTrue(shortfall.drawn(3) > 390 && shortfall.drawn(3) < 420, "the battery gives the rest: " + shortfall.drawn(3));
        assertEquals(0, shortfall.delivered(3), 1.0E-9, "and takes nothing");
    }

    @Test
    void groundedLinesAndLostPhases() {
        GridGraph graph = GridFixtures.line(VoltageTier.LV, 3, 4);
        List<Port> ports = List.of(source(1, VoltageTier.LV, GridFixtures.at(1), 1000), sink(2, VoltageTier.LV, GridFixtures.at(5), 500));
        // A ground switch closed on phase A at tower 3, with the line live: a fault, and nothing flows to the load.
        FlowSolver.Result grounded = FlowSolver.solve(graph, ports, List.of(), List.of(), Set.of(GridFixtures.node(0, 3)), S, new FlowSolver.State());
        assertEquals(FlowSolver.PortStatus.GROUNDED, grounded.status(2));
        assertTrue(!grounded.groundFaults().isEmpty(), "grounding a live line is a fault");
        assertEquals(0, grounded.delivered(2), 1.0E-9);
        // Phase C cut between towers 2 and 3: the load is whole but has lost a phase.
        graph.removeEdge(GridFixtures.edge(2, 3));
        FlowSolver.Result lost = FlowSolver.solve(graph, ports, S);
        assertTrue(lost.phaseLoss().contains(2L), "phase loss at the load");
        assertTrue(lost.liveNetworks().contains(graph.networkOf(GridFixtures.node(0, 5)).id()), "A is still live there");
        assertTrue(!lost.liveNetworks().contains(graph.networkOf(GridFixtures.node(2, 5)).id()), "C isn't");
    }
}
