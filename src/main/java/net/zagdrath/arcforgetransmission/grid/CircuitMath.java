/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.Map;

// The electrical maths, kept in one place so it can be made more real later (three-phase power is really
// sqrt(3) x V x I x power factor; here a circuit carrying P FE/t at a tier's voltage V has I = P / V in each of its
// conductors, so 1 V x 1 A = 1 FE/t).
public final class CircuitMath {
    private CircuitMath() {}

    // The current in each conductor of a circuit carrying power (FE/t) at the tier's voltage.
    public static double current(double power, VoltageTier tier) {
        return power / tier.volts();
    }

    // The power a circuit carries at that current.
    public static double power(double amps, VoltageTier tier) {
        return amps * tier.volts();
    }

    // FE/t lost as heat in one conductor: I squared R.
    public static double loss(double amps, double ohms) {
        return amps * amps * ohms;
    }

    // The phase angle (radians) of the current on one conductor of a source's circuit: a line's own angle; the
    // neutral carries its line's current back, half a turn round.
    public static double angle(Phase phase, Iterable<Phase> circuit) {
        if (!phase.isNeutral()) {
            return phase.radians();
        }
        for (Phase other : circuit) {
            if (!other.isNeutral()) {
                return other.radians() + Math.PI;
            }
        }
        return Math.PI;
    }

    // The size of a sum of currents (phasors, real and imaginary parts).
    public static double magnitude(Map<Long, double[]> phasors, long key) {
        double[] p = phasors.get(key);
        return p == null ? 0.0 : Math.hypot(p[0], p[1]);
    }
}
