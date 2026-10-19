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

// The wire's curve, its length, and the circuit maths.
class GeometryTest {
    @Test
    void sagIsFivePercentOfTheSpanAtMidSpan() {
        double[] a = { 0, 10, 0 }, b = { 20, 10, 0 };
        double[] mid = Catenary.point(a, b, 0.5);
        assertEquals(10 - 0.05 * 20, mid[1], 1.0E-9);
        assertEquals(10.0, mid[0], 1.0E-9);
    }

    @Test
    void theWireIsLongerThanTheStraightLine() {
        double[] a = { 0, 10, 0 }, b = { 20, 10, 0 };
        double length = Catenary.length(a, b);
        assertTrue(length > 20.0 && length < 20.2, "length " + length);
        // A parabola sagging 1 over 20: about 20 + 8/3 x 1^2 / 20.
        assertEquals(20 + 8.0 / 3.0 / 20.0, length, 0.01);
    }

    @Test
    void currentIsPowerOverVoltage() {
        assertEquals(16.0, CircuitMath.current(4096, VoltageTier.LV), 1.0E-12);
        assertEquals(128.0, CircuitMath.current(2_097_152, VoltageTier.EHV), 1.0E-12);
        assertEquals(4.0 * 0.5, CircuitMath.loss(2, 0.5), 1.0E-12);
    }

    @Test
    void phasesAreAThirdOfATurnApartAndTheNeutralCarriesItsLineBack() {
        List<Phase> split = List.of(Phase.L2, Phase.N);
        assertEquals(Math.PI, CircuitMath.angle(Phase.L2, split), 1.0E-12);
        assertEquals(2 * Math.PI, CircuitMath.angle(Phase.N, split), 1.0E-12);
        // A balanced three-phase set sums to nothing.
        double re = 0, im = 0;
        for (Phase phase : List.of(Phase.A, Phase.B, Phase.C)) {
            re += Math.cos(phase.radians());
            im += Math.sin(phase.radians());
        }
        assertEquals(0.0, Math.hypot(re, im), 1.0E-12);
    }
}
