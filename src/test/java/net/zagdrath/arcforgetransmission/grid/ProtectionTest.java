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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.zagdrath.arcforgetransmission.grid.protection.Curves;
import net.zagdrath.arcforgetransmission.grid.protection.Differential;
import net.zagdrath.arcforgetransmission.grid.protection.FuseElement;
import net.zagdrath.arcforgetransmission.grid.protection.Interlocks;
import net.zagdrath.arcforgetransmission.grid.protection.Overcurrent;
import net.zagdrath.arcforgetransmission.grid.protection.Sectionalizer;
import net.zagdrath.arcforgetransmission.grid.protection.TapControl;
import net.zagdrath.arcforgetransmission.grid.protection.TransferSwitch;
import net.zagdrath.arcforgetransmission.grid.protection.Unbalance;

// Protection: fault currents and coordination (the device nearest a fault trips first), a recloser saving or blowing a
// fuse, the transformer differential, interlocks, the sectionalizer's count, the transfer switch, the regulator holding a
// sagging feeder, and unbalance.
class ProtectionTest {
    private static final GridSettings S = GridSettings.DEFAULTS;
    private static final double INF = Double.POSITIVE_INFINITY;

    // An MV feeder of `spans` spans with a three-phase protective device (a jumper on each phase) in place of each listed
    // span; returns the device links, devices in the given order.
    private static List<long[]> devices(GridGraph graph, int... spans) {
        List<long[]> out = new ArrayList<>();
        for (int i : spans) {
            long[] links = new long[3];
            for (int k = 0; k < 3; k++) {
                graph.removeEdge(GridFixtures.edge(k, i));
                graph.addEdge(new GridEdge(GridFixtures.edge(k, i), GridFixtures.node(k, i), GridFixtures.node(k, i + 1), VoltageTier.MV,
                        CircuitType.THREE_PHASE, 0.0, GridEdge.Kind.JUMPER));
                links[k] = GridFixtures.edge(k, i);
            }
            out.add(links);
        }
        return out;
    }

    private static Set<Long> protective(List<long[]> devices) {
        Set<Long> set = new HashSet<>();
        devices.forEach(d -> {
            for (long l : d) {
                set.add(l);
            }
        });
        return set;
    }

    @Test
    void faultCurrentFallsWithDistanceAndIsCapped() {
        GridGraph graph = GridFixtures.line(VoltageTier.MV, 10, 24);
        List<Port> ports = List.of(new Port(1, VoltageTier.MV, GridFixtures.at(1), 1000, 0, INF));
        FlowSolver.FaultFlow near = FlowSolver.fault(graph, ports, List.of(), GridFixtures.node(0, 1), Set.of(), S);
        FlowSolver.FaultFlow far = FlowSolver.fault(graph, ports, List.of(), GridFixtures.node(0, 12), Set.of(), S);
        assertEquals(S.faultMultiple() * S.ratedAmps(VoltageTier.MV), near.amps(), 1.0E-6, "at the source: the cap");
        assertTrue(far.amps() < near.amps() && far.amps() > S.ratedAmps(VoltageTier.MV), "further out, less, but still a fault: " + far.amps());
        assertEquals(far.amps(), far.amps(GridFixtures.edge(0, 5)), 1.0E-6, "every span on the way carries it");
        assertEquals(0, far.amps(GridFixtures.edge(1, 5)), 1.0E-9, "a phase to ground: only the faulted phase");
        assertEquals(FlowSolver.FaultFlow.NONE, FlowSolver.fault(graph, List.of(), List.of(), GridFixtures.node(0, 12), Set.of(), S), "dead: no fault current");
    }

    @Test
    void theDeviceNearestTheFaultTripsFirst() {
        GridGraph graph = GridFixtures.line(VoltageTier.MV, 10, 24);
        List<long[]> devs = devices(graph, 2, 5, 9);
        List<Port> ports = List.of(new Port(1, VoltageTier.MV, GridFixtures.at(1), 1000, 0, INF));
        // A fault past the last device, on phase B.
        FlowSolver.FaultFlow fault = FlowSolver.fault(graph, ports, List.of(), GridFixtures.node(1, 11), protective(devs), S);
        assertEquals(2, fault.beyond(devs.get(0)[1]));
        assertEquals(1, fault.beyond(devs.get(1)[1]));
        assertEquals(0, fault.beyond(devs.get(2)[1]));
        // All three breakers set alike (definite time, no instantaneous): the nearest goes first; the fault is cleared then.
        Overcurrent.Settings settings = new Overcurrent.Settings(S.ratedAmps(VoltageTier.MV), 0, 0.1, false, 10, 8, false, 0);
        List<Overcurrent> relays = List.of(new Overcurrent(), new Overcurrent(), new Overcurrent());
        int tripped = -1;
        for (int tick = 0; tick < 200 && tripped < 0; tick++) {
            for (int d = 0; d < 3; d++) {
                long link = devs.get(d)[1];
                if (relays.get(d).tick(fault.amps(link), fault.beyond(link), 0, settings)) {
                    tripped = d;
                    break;
                }
            }
        }
        assertEquals(2, tripped, "the breaker nearest the fault");
        // A fault between the first and second: the first trips.
        FlowSolver.FaultFlow upstream = FlowSolver.fault(graph, ports, List.of(), GridFixtures.node(1, 4), protective(devs), S);
        assertEquals(0, upstream.beyond(devs.get(0)[1]));
        assertEquals(0, upstream.amps(devs.get(2)[1]), 1.0E-9, "past the fault, nothing");
        // With inverse curves too: the upstream relay's grading interval puts it behind.
        Overcurrent.Settings inverse = new Overcurrent.Settings(S.ratedAmps(VoltageTier.MV), 0, 0.1, true, 0, 8, false, 0);
        double nearTicks = Curves.inverseTicks(fault.amps(devs.get(2)[1]) / inverse.pickup(), 0.1);
        double upTicks = Curves.inverseTicks(fault.amps(devs.get(0)[1]) / inverse.pickup(), 0.1) + 2 * inverse.interval();
        assertTrue(upTicks > nearTicks + inverse.interval(), "graded: " + upTicks + " against " + nearTicks);
    }

    @Test
    void overloadsAreGradedToo() {
        GridGraph graph = GridFixtures.line(VoltageTier.MV, 10, 24);
        List<long[]> devs = devices(graph, 2, 9);
        List<Port> ports = List.of(new Port(1, VoltageTier.MV, GridFixtures.at(1), 20000, 0, INF), new Port(2, VoltageTier.MV, GridFixtures.at(12), 0, 9000, INF));
        FlowSolver.Result result = FlowSolver.solve(graph, ports, List.of(), List.of(), Set.of(), protective(devs), S, new FlowSolver.State());
        assertEquals(1, result.beyond(devs.get(0)[0]));
        assertEquals(0, result.beyond(devs.get(1)[0]));
    }

    @Test
    void aRecloserSavesOrBlowsTheFuseBeyondIt() {
        double rating = 16, fault = 160, melt = 300;
        Overcurrent.Settings saving = new Overcurrent.Settings(rating, 0, 0.1, true, 0, 8, true, 1);
        Overcurrent.Settings blowing = new Overcurrent.Settings(rating, 0, 0.1, true, 0, 8, false, 1);
        assertTrue(Curves.fuseTicks(fault / rating, melt) > 0, "the fuse takes a moment");
        // Fuse-saving: the recloser's first shot is fast, before the fuse melts.
        Overcurrent recloser = new Overcurrent();
        FuseElement fuse = new FuseElement();
        assertTrue(recloser.tick(fault, 1, 0, saving), "fast trip");
        assertTrue(fuse.tick(fault / rating, melt, 0.1) == false, "the fuse only warmed in that tick");
        // ...and on its next shot it's slow (graded behind the fuse), so the fuse clears a fault that's still there.
        int recloserTick = -1, fuseTick = -1;
        for (int tick = 0; tick < 400 && (recloserTick < 0 && fuseTick < 0); tick++) {
            if (fuse.tick(fault / rating, melt, 0.1)) {
                fuseTick = tick;
            }
            if (recloser.tick(fault, 1, 1, saving)) {
                recloserTick = tick;
            }
        }
        assertTrue(fuseTick >= 0 && recloserTick < 0, "the fuse blows on the second shot");
        // Fuse-blowing: the recloser never goes fast; the fuse blows first.
        Overcurrent slow = new Overcurrent();
        FuseElement fuse2 = new FuseElement();
        assertFalse(slow.tick(fault, 1, 0, blowing));
        boolean blown = false;
        for (int tick = 0; tick < 400 && !blown; tick++) {
            blown = fuse2.tick(fault / rating, melt, 0.1);
            assertFalse(!blown && slow.tick(fault, 1, 0, blowing), "the recloser waited");
        }
        assertTrue(blown);
    }

    @Test
    void theDifferentialSeesOnlyInternalFaults() {
        assertFalse(Differential.trips(8000, 7990, 200, 0.2), "load through it");
        assertFalse(Differential.trips(40000, 40000, 200, 0.2), "a fault beyond it");
        assertTrue(Differential.trips(30000, 2000, 200, 0.2), "a fault inside it");
    }

    @Test
    void interlocks() {
        assertEquals(Interlocks.Refusal.BREAKER_CLOSED, Interlocks.disconnect(true));
        assertNull(Interlocks.disconnect(false));
        assertEquals(Interlocks.Refusal.LINE_LIVE, Interlocks.groundSwitch(true, true));
        assertNull(Interlocks.groundSwitch(true, false));
        assertNull(Interlocks.groundSwitch(false, true), "opening a ground switch is always allowed");
        assertEquals(Interlocks.Refusal.LINE_GROUNDED, Interlocks.closeBreaker(true));
        assertNull(Interlocks.closeBreaker(false));
    }

    @Test
    void theSectionalizerCountsRecloserOperations() {
        Sectionalizer s = new Sectionalizer();
        // First operation: fault current, then the recloser opens (dead).
        assertFalse(s.tick(true, true, 2, 100));
        assertFalse(s.tick(false, false, 2, 100));
        assertEquals(1, s.counted());
        // Reclosed onto the fault: fault current again, then dead: the second count opens it, while dead.
        assertFalse(s.tick(false, true, 2, 100));
        assertFalse(s.tick(true, true, 2, 100));
        assertTrue(s.tick(false, false, 2, 100));
        assertTrue(s.open());
        // A passing fault: one count, then the line stays live long enough to forget it.
        Sectionalizer t = new Sectionalizer();
        t.tick(true, true, 2, 10);
        t.tick(false, false, 2, 10);
        for (int i = 0; i < 10; i++) {
            t.tick(false, true, 2, 10);
        }
        assertEquals(0, t.counted());
        // Never on fault current alone (it can't break it).
        Sectionalizer u = new Sectionalizer();
        for (int i = 0; i < 50; i++) {
            assertFalse(u.tick(true, true, 1, 10));
        }
    }

    @Test
    void theTransferSwitchMovesAndComesBack() {
        TransferSwitch ats = new TransferSwitch();
        for (int i = 0; i < 9; i++) {
            assertFalse(ats.tick(false, true, 10, 40));
        }
        assertTrue(ats.tick(false, true, 10, 40));
        assertEquals(TransferSwitch.Position.ALTERNATE, ats.position());
        // Not back until the preferred has been live long enough.
        for (int i = 0; i < 39; i++) {
            assertFalse(ats.tick(true, true, 10, 40));
        }
        assertTrue(ats.tick(true, true, 10, 40));
        assertEquals(TransferSwitch.Position.PREFERRED, ats.position());
        // Both dead: stays put.
        TransferSwitch idle = new TransferSwitch();
        for (int i = 0; i < 50; i++) {
            assertFalse(idle.tick(false, false, 10, 40));
        }
    }

    @Test
    void aRegulatorHoldsASaggingFeeder() {
        int spans = 16;
        GridGraph graph = GridFixtures.line(VoltageTier.MV, spans - 1, 24);
        int at = spans / 2;
        List<long[]> reg = devices(graph, at);
        double rated = VoltageTier.MV.defaultMaxPower();
        List<Port> ports = List.of(new Port(1, VoltageTier.MV, GridFixtures.at(1), 2 * rated, 0, INF),
                new Port(2, VoltageTier.MV, GridFixtures.at(spans + 1), 0, 0.8 * rated, INF));
        FlowSolver.State state = new FlowSolver.State();
        TapControl control = new TapControl();
        double step = 0.00625, band = 0.04;
        FlowSolver.Result result = FlowSolver.solve(graph, ports, List.of(), List.of(), Set.of(), S, state);
        double before = result.volts(2);
        assertTrue(before < 0.94, "sagging: " + before);
        for (int tick = 0; tick < 400; tick++) {
            // It watches the end of its own feeder section past it (its load side) .
            control.tick(result.volts(2), band, step, 16, 5);
            for (long link : reg.get(0)) {
                graph.edge(link).setBoost(control.boost(step));
            }
            result = FlowSolver.solve(graph, ports, List.of(), List.of(), Set.of(), S, state);
        }
        assertTrue(Math.abs(result.volts(2) - 1.0) <= band / 2 + 1.0E-6, "held within its band: " + result.volts(2) + " at " + control.position() + " from " + before + " regulator node " + result.nodeVolts(GridFixtures.node(0, at)) + " -> " + result.nodeVolts(GridFixtures.node(0, at + 1)) + " delivered " + result.delivered(2));
        assertTrue(control.position() > 0);
        // Out of steps, it stops trying.
        TapControl stuck = new TapControl();
        for (int i = 0; i < 1000; i++) {
            stuck.tick(0.5, band, step, 16, 1);
        }
        assertEquals(16, stuck.position());
    }

    @Test
    void unbalance() {
        assertEquals(0, Unbalance.of(10, 10, 10), 1.0E-9);
        assertEquals(1, Unbalance.of(10, 10, 0), 1.0E-9);
        assertEquals(0.5, Unbalance.of(10, 5), 1.0E-9);
        assertEquals(0, Unbalance.of(0, 0, 0), 1.0E-9);
    }
}
