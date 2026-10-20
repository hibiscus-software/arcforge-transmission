/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.zagdrath.arcforgetransmission.grid.GridSettings;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// A lineup's numbers match the design's table at every tier, and nothing overflows at EHV's (16,777,216 FE/t for an 8W
// gas-insulated bus, past 2 billion for a comparator's or a percentage's arithmetic in an int).
class LineupRatingsTest {
    private static final GridSettings S = GridSettings.DEFAULTS;

    @Test
    void theTable() {
        assertEquals(4_096L, LineupRatings.circuit(S, VoltageTier.LV));
        assertEquals(32_768L, LineupRatings.transformerRating(S, VoltageTier.MV, 1.0));
        assertEquals(262_144L, LineupRatings.transformerRating(S, VoltageTier.HV, 1.0));
        assertEquals(2_097_152L, LineupRatings.transformerRating(S, VoltageTier.EHV, 1.0));
        // Each transformer's secondary carries its tier's largest busway (8W).
        assertEquals(128.0, LineupRatings.secondaryAmps(S, VoltageTier.MV, 1.0), 1.0E-9);
        assertEquals(256.0, LineupRatings.secondaryAmps(S, VoltageTier.HV, 1.0), 1.0E-9);
        assertEquals(512.0, LineupRatings.secondaryAmps(S, VoltageTier.EHV, 1.0), 1.0E-9);
        // Feeder frames 1W to 8W: EHV's 128 to 1,024 A.
        double[] ehv = { 128, 256, 512, 1024 };
        for (int f = 0; f < 4; f++) {
            assertEquals(ehv[f], LineupRatings.feederFrame(S, VoltageTier.EHV, f), 1.0E-9);
        }
        // A main sized to what feeds it: a transformer's fan-cooled current, or one incoming circuit.
        assertEquals(128 * 1.33, LineupRatings.mainFrame(S, VoltageTier.MV, true, 1.0, 1.33), 1.0E-9);
        assertEquals(128.0, LineupRatings.mainFrame(S, VoltageTier.EHV, false, 1.0, 1.33), 1.0E-9);
    }

    @Test
    void windingsLoseTheirResistance() {
        long rating = LineupRatings.transformerRating(S, VoltageTier.HV, 1.0);
        double r = LineupRatings.windingOhms(VoltageTier.MV, rating, 0.01);
        double amps = rating / (double) VoltageTier.MV.volts();
        assertEquals(0.01 * rating, 3 * amps * amps * r, 1.0E-6 * rating);
    }

    @Test
    void bigNumbersShowAndNothingOverflows() {
        long most = 16_777_216L;
        assertEquals("16.78 MFE/t", LineupRatings.power(most));
        assertEquals("32.8 kFE/t", LineupRatings.power(32_768));
        assertEquals("4096 FE/t", LineupRatings.power(4_096));
        // 133% of the largest, as an int percentage would be, and a comparator at many times its setting.
        assertEquals("22.31 MFE/t", LineupRatings.power(Math.round(most * 1.33)));
        assertEquals(15, LineupRatings.comparator(1000.0));
        assertEquals(1, LineupRatings.comparator(0.001));
        assertEquals(0, LineupRatings.comparator(0));
        assertEquals(most * 15, (long) VoltageTier.EHV.volts() * 1024L * 15L);
    }
}
