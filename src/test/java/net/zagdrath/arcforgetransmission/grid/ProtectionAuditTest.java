/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.zagdrath.arcforgetransmission.grid.protection.Curves;
import net.zagdrath.arcforgetransmission.grid.protection.Differential;
import net.zagdrath.arcforgetransmission.grid.protection.FuseElement;
import net.zagdrath.arcforgetransmission.grid.protection.Overcurrent;
import net.zagdrath.arcforgetransmission.grid.protection.Reclosing;
import net.zagdrath.arcforgetransmission.grid.protection.Sectionalizer;
import net.zagdrath.arcforgetransmission.grid.protection.UnbalanceWatch;

// The Phase 4 audit's protection fixes: grading against a fuse's curve, fuse-saving only over fuses, overload grading on
// the branch actually overloaded, the differential counting both sides into the transformer, the sectionalizer forgetting
// a fault cleared downstream, and the recloser's lockout and the unbalance and lost-phase trips.
class ProtectionAuditTest {
    private static final GridSettings S = GridSettings.DEFAULTS;
    private static final double MELT = 300;

    @Test
    void aRecloserGradesAgainstTheFuseBeyondIt() {
        double rating = 16, amps = 48;
        // Fuse-blowing, its own curve fast: still it waits for the fuse beyond to melt, and a grading interval more.
        Overcurrent.Settings blowing = new Overcurrent.Settings(rating, 0, 0.01, true, 0, 8, false, 0);
        Overcurrent recloser = new Overcurrent();
        FuseElement fuse = new FuseElement();
        FlowSolver.Beyond fuseBeyond = new FlowSolver.Beyond(1, true, rating);
        double fuseTicks = Curves.fuseTicks(amps / rating, MELT);
        assertTrue(Curves.inverseTicks(amps / rating, 0.01) < fuseTicks, "its own curve alone would beat the fuse");
        int melted = -1, tripped = -1;
        for (int tick = 0; tick < 200 && (melted < 0 || tripped < 0); tick++) {
            if (melted < 0 && fuse.tick(amps / rating, MELT, 0.05)) {
                melted = tick;
            }
            if (tripped < 0 && recloser.tick(amps, fuseBeyond, 1, blowing, MELT)) {
                tripped = tick;
            }
        }
        assertTrue(melted >= 0 && tripped > melted, "the fuse first (" + melted + "), the recloser after (" + tripped + ")");
        assertTrue(tripped >= fuseTicks + blowing.interval() - 1, "by a grading interval");
    }

    @Test
    void fuseSavingIsFastOnlyOverFuses() {
        Overcurrent.Settings saving = new Overcurrent.Settings(16, 0, 0.1, true, 0, 8, true, 1);
        assertTrue(new Overcurrent().tick(160, new FlowSolver.Beyond(1, true, 16), 0, saving, MELT), "fast over a fuse");
        assertTrue(new Overcurrent().tick(160, FlowSolver.Beyond.NONE, 0, saving, MELT), "fast with nothing beyond");
        assertFalse(new Overcurrent().tick(160, new FlowSolver.Beyond(1, false, 0), 0, saving, MELT), "not over another breaker");
    }

    @Test
    void overloadGradingCountsTheOverloadedBranch() {
        // A feeder from 1 to a breaker at 2-3 and a junction at 3; a light branch through two breakers (3-4, 4-5) to a
        // light load at 6, and a heavy branch through one breaker (3-7) to a heavy load at 8.
        GridGraph graph = new GridGraph();
        long[][] spans = { { 1, 2 }, { 2, 3 }, { 3, 4 }, { 4, 5 }, { 5, 6 }, { 3, 7 }, { 7, 8 } };
        boolean[] link = { false, true, true, true, false, true, false };
        for (int k = 0; k < 3; k++) {
            for (long i = 1; i <= 8; i++) {
                graph.addNode(new GridNode(k * 100 + i, VoltageTier.MV, GridFixtures.ABC[k]));
            }
            for (int s = 0; s < spans.length; s++) {
                graph.addEdge(new GridEdge(k * 100 + 10 + s, k * 100 + spans[s][0], k * 100 + spans[s][1], VoltageTier.MV, CircuitType.THREE_PHASE,
                        link[s] ? 0 : 8, link[s] ? GridEdge.Kind.JUMPER : GridEdge.Kind.BARE));
            }
        }
        Map<Long, FlowSolver.Protective> protective = new HashMap<>();
        double pickup = 10;
        for (int k = 0; k < 3; k++) {
            for (int s : new int[] { 1, 2, 3, 5 }) {
                protective.put((long) k * 100 + 10 + s, new FlowSolver.Protective(pickup, false));
            }
        }
        List<Port> ports = List.of(new Port(1, VoltageTier.MV, List.of(1L, 101L, 201L), 20000, 0, Double.POSITIVE_INFINITY),
                new Port(2, VoltageTier.MV, List.of(6L, 106L, 206L), 0, 512, Double.POSITIVE_INFINITY),
                new Port(3, VoltageTier.MV, List.of(8L, 108L, 208L), 0, 9000, Double.POSITIVE_INFINITY));
        FlowSolver.Result result = FlowSolver.solve(graph, ports, List.of(), List.of(), Set.of(), protective, S, new FlowSolver.State());
        assertTrue(result.amps(15) > pickup && result.amps(12) < pickup, "the heavy branch is overloaded, the light one isn't");
        assertEquals(1, result.beyond(11), "the feeder breaker counts the one overloaded device on the heavy branch, not the light branch's two");
        assertEquals(0, result.beyond(15));
    }

    @Test
    void theDifferentialCountsBothSidesIntoTheTransformer() {
        double rating = 8192, pickup = 0.1 * rating, slope = 0.25;
        // Load through it: in on the primary, out on the secondary (counted in: negative).
        assertFalse(Differential.trips(6000, -6000, pickup, slope), "load through it");
        // A fault beyond it: a big current straight through.
        assertFalse(Differential.trips(60000, -60000, pickup, slope), "a fault beyond it");
        // A fault inside it (a lightning surge with no arrester): current in from both sides.
        assertTrue(Differential.trips(6000 + 20000, -6000 + 20000, pickup, slope), "a fault inside it");
        assertTrue(Differential.trips(20000, 0, pickup, slope), "an internal fault fed from one side");
    }

    @Test
    void theSectionalizerForgetsAFaultClearedDownstream() {
        Sectionalizer s = new Sectionalizer();
        // Fault current, but a fuse past it clears it: the line stays live.
        assertFalse(s.tick(true, true, 2, 1000));
        assertFalse(s.tick(false, true, 2, 1000));
        // An unrelated outage later: not counted against that fault.
        assertFalse(s.tick(false, false, 2, 1000));
        assertEquals(0, s.counted(), "a fault cleared while the line stayed live isn't a recloser operation");
    }

    @Test
    void aRecloserLocksOutAfterItsLastShot() {
        Reclosing r = new Reclosing();
        long now = 0;
        for (int shot = 1; shot <= 3; shot++) {
            r.trip(now, 3, 60);
            assertFalse(r.lockedOut(), "shot " + shot + " recloses");
            assertFalse(r.due(now + 59));
            assertTrue(r.due(now + 60));
            r.closed(now + 60);
            now += 61;
        }
        r.trip(now, 3, 60);
        assertTrue(r.lockedOut(), "the fourth trip locks it out");
        assertFalse(r.due(now + 1000), "and it never recloses by itself");
        r.reset();
        assertFalse(r.lockedOut(), "closed by hand, it's ready again");
        // Holding closed long enough forgets the shots.
        Reclosing held = new Reclosing();
        held.trip(0, 3, 60);
        held.closed(60);
        held.forget(60 + 601);
        assertEquals(0, held.shots());
    }

    @Test
    void lostPhaseAndUnbalanceTripAfterTheirTime() {
        UnbalanceWatch watch = new UnbalanceWatch();
        double rated = 16;
        for (int i = 0; i < 99; i++) {
            assertFalse(watch.tick(new double[] { 12, 12, 0 }, rated, 0.5, 100), "not yet");
        }
        assertTrue(watch.tick(new double[] { 12, 12, 0 }, rated, 0.5, 100), "a lost phase trips");
        UnbalanceWatch mild = new UnbalanceWatch();
        for (int i = 0; i < 300; i++) {
            assertFalse(mild.tick(new double[] { 12, 11, 10 }, rated, 0.5, 100), "a mild spread never does");
        }
        UnbalanceWatch idle = new UnbalanceWatch();
        for (int i = 0; i < 300; i++) {
            assertFalse(idle.tick(new double[] { 0.1, 0, 0 }, rated, 0.5, 100), "nor a line carrying next to nothing");
        }
        UnbalanceWatch reset = new UnbalanceWatch();
        for (int i = 0; i < 99; i++) {
            reset.tick(new double[] { 12, 2, 2 }, rated, 0.5, 100);
        }
        reset.tick(new double[] { 12, 12, 12 }, rated, 0.5, 100);
        assertFalse(reset.tick(new double[] { 12, 2, 2 }, rated, 0.5, 100), "balanced again resets the count");
        assertTrue(reset.started(), "and it says when unbalance starts (once)");
    }
}
