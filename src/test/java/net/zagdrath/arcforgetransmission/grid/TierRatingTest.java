/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

// The tiers' ratings: each tier's volts and amps were both doubled (4x the power), with resistance and reactance per block
// left as they were, so a circuit at its rating loses the same share of its power and drops the same per unit.
class TierRatingTest {
    private static final GridSettings S = GridSettings.DEFAULTS;
    private static final double INF = Double.POSITIVE_INFINITY;

    // Each tier's rated FE/t for one three-phase circuit before the change (128 V x 8 A, 512 x 16, 2048 x 32, 8192 x 64).
    private static final long[] OLD_RATED = { 1_024, 8_192, 65_536, 524_288 };
    // What a circuit at its rating lost (share of its power) and dropped (per unit) over SPANS longest spans then,
    // measured with the solver before the change.
    private static final double[] OLD_LOSS = { 0.096, 0.099, 0.102, 6.0E-5 };
    private static final double[] OLD_DROP = { 0.032, 0.033, 0.034, 2.0E-5 };
    private static final int SPANS = 4;

    @Test
    void ratingsAreTheNewVoltsAndAmps() {
        int[] volts = { 256, 1_024, 4_096, 16_384 }, amps = { 16, 32, 64, 128 };
        for (VoltageTier tier : VoltageTier.values()) {
            assertEquals(volts[tier.ordinal()], tier.volts(), tier.name());
            assertEquals(amps[tier.ordinal()], tier.defaultRatedAmps(), tier.name());
            assertEquals(4 * OLD_RATED[tier.ordinal()], tier.defaultMaxPower(), tier.name() + " carries 4x the old FE/t");
        }
    }

    // A circuit at its rating (4x the old FE/t) runs indefinitely; at twice its rating it burns out on time.
    @Test
    void aCircuitCarriesItsRatingAndBurnsAtTwice() {
        for (VoltageTier tier : VoltageTier.values()) {
            double rated = tier.defaultMaxPower();
            GridGraph graph = GridFixtures.line(tier, 1, tier.defaultMaxSpan());
            GridFixtures.Buffers terminals = new GridFixtures.Buffers(tier).source(1, GridFixtures.at(1), 1_000_000_000_000L, (int) (3 * rated))
                    .sink(2, GridFixtures.at(3), (int) rated);
            for (int tick = 0; tick < 3 * S.burnoutTicks(); tick++) {
                assertTrue(GridTicker.tick(graph, terminals, S).burnt().isEmpty(), tier + " burnt at its rating, tick " + tick);
            }
            assertEquals((long) rated * 3 * S.burnoutTicks(), terminals.deliveredTotal, tier + " delivered its rating every tick");

            terminals.demand.put(2L, 2 * rated);
            boolean burnt = false;
            for (int tick = 0; tick < S.burnoutTicks() + 1 && !burnt; tick++) {
                burnt = !GridTicker.tick(graph, terminals, S).burnt().isEmpty();
            }
            assertTrue(burnt, tier + " at twice its rating burns out");
        }
    }

    // Loss as a share of power is 3 I R L / V and the drop per unit I Z L / V: doubling I and V leaves both alone.
    @Test
    void lossAndDropAtRatingMatchTheOldTiers() {
        for (VoltageTier tier : VoltageTier.values()) {
            double[] measured = atRating(tier);
            int i = tier.ordinal();
            assertEquals(OLD_LOSS[i], measured[0], 0.05 * OLD_LOSS[i] + 1.0E-12, tier + " loss share");
            assertEquals(OLD_DROP[i], measured[1], 0.05 * OLD_DROP[i] + 1.0E-12, tier + " drop per unit");
        }
        // About 2.5% of the power in one longest span at rating (the superconductor next to nothing).
        assertEquals(0.024, 3 * VoltageTier.LV.defaultRatedAmps() * VoltageTier.LV.defaultResistance() * VoltageTier.LV.defaultMaxSpan()
                / VoltageTier.LV.volts(), 1.0E-9);
    }

    // A circuit's rated power down SPANS longest spans, settled: { loss / delivered, 1 - the load's voltage per unit }.
    static double[] atRating(VoltageTier tier) {
        double rated = tier.defaultMaxPower();
        GridGraph graph = GridFixtures.line(tier, SPANS - 1, tier.defaultMaxSpan());
        List<Port> ports = List.of(new Port(1, tier, GridFixtures.at(1), 2 * rated, 0, INF), new Port(2, tier, GridFixtures.at(SPANS + 1), 0, rated, INF));
        FlowSolver.State state = new FlowSolver.State();
        FlowSolver.Result result = null;
        for (int tick = 0; tick < 10; tick++) {
            result = FlowSolver.solve(graph, ports, List.of(), List.of(), Set.of(), S, state);
        }
        assertFalse(result.delivered(2) <= 0, tier + " delivered nothing");
        return new double[] { result.totalLoss() / result.delivered(2), 1 - result.volts(2) };
    }
}
