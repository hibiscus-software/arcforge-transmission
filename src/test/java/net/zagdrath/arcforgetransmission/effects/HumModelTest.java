/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// A transformer's hum follows its load: silent de-energised; energised, from its idle share unloaded to its full volume at
// its rating, its pitch rising about 5% to full load; a change fades over half a second (10 ticks), never jumping.
class HumModelTest {
    private static final double VOLUME = 0.5, IDLE = 0.35, RISE = 0.05;
    private static final int FADE = 10;

    @Test
    void volumeFollowsLoad() {
        assertEquals(0.0, HumModel.volume(0.7, false, VOLUME, IDLE), 1.0E-12, "de-energised, silent");
        assertEquals(VOLUME * IDLE, HumModel.volume(0.0, true, VOLUME, IDLE), 1.0E-12, "unloaded, its idle hum");
        assertEquals(VOLUME, HumModel.volume(1.0, true, VOLUME, IDLE), 1.0E-12, "at its rating, full");
        assertEquals(VOLUME * (IDLE + (1 - IDLE) * 0.5), HumModel.volume(0.5, true, VOLUME, IDLE), 1.0E-12, "half load, half way");
        assertEquals(VOLUME, HumModel.volume(3.0, true, VOLUME, IDLE), 1.0E-12, "overloaded hums as at its rating");
        double last = -1;
        for (int i = 0; i <= 20; i++) {
            double v = HumModel.volume(i / 20.0, true, VOLUME, IDLE);
            assertTrue(v > last, "louder with load at " + i);
            last = v;
        }
    }

    @Test
    void pitchRisesAboutFivePercentAtFullLoad() {
        assertEquals(1.0, HumModel.pitch(0.0, RISE), 1.0E-12);
        assertEquals(1.05, HumModel.pitch(1.0, RISE), 1.0E-12);
        assertEquals(1.025, HumModel.pitch(0.5, RISE), 1.0E-12);
        assertEquals(1.05, HumModel.pitch(2.0, RISE), 1.0E-12, "no higher past its rating");
    }

    @Test
    void itFadesOverHalfASecond() {
        double v = 0;
        for (int tick = 1; tick <= FADE; tick++) {
            double next = HumModel.approach(v, VOLUME, VOLUME, FADE);
            assertEquals(VOLUME / FADE, next - v, 1.0E-12, "a step of the full volume over the fade, tick " + tick);
            v = next;
        }
        assertEquals(VOLUME, v, 1.0E-12, "in after 10 ticks");
        for (int tick = 1; tick <= FADE; tick++) {
            v = HumModel.approach(v, 0, VOLUME, FADE);
        }
        assertEquals(0, v, 1.0E-12, "out after 10 ticks");
        assertEquals(0.3, HumModel.approach(0.29, 0.3, VOLUME, FADE), 1.0E-12, "never past its target");
    }
}
