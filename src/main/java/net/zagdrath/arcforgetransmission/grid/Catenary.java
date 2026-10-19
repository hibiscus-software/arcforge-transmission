/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

// A wire's hanging curve between two attach points: it sags 0.05 x the span (the straight distance) at mid-span, as a
// parabola, which at these sags is the catenary to well under a pixel. Its length (for resistance) is the arc, not the
// straight distance.
public final class Catenary {
    public static final double SAG = 0.05;
    private static final int LENGTH_STEPS = 64;

    private Catenary() {}

    // The point a fraction t (0 to 1) along the wire from a to b ({x, y, z}).
    public static double[] point(double[] a, double[] b, double t) {
        double span = distance(a, b);
        double drop = 4.0 * SAG * span * t * (1.0 - t);
        return new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t - drop, a[2] + (b[2] - a[2]) * t };
    }

    // A point on a curve hanging `sag` blocks below the straight line at its middle (a parabola, as a wire's).
    public static double[] point(double[] a, double[] b, double t, double sag) {
        double drop = 4.0 * sag * t * (1.0 - t);
        return new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t - drop, a[2] + (b[2] - a[2]) * t };
    }

    // How far (blocks) a dead-end's jumper hangs below the straight line from the strain's line clamp to where it drops to:
    // a slack loop, clearly deeper than a span's 5%, deeper on the bigger tiers.
    public static double jumperSag(VoltageTier tier) {
        return switch (tier) {
            case LV -> 0.6;
            case MV -> 0.7;
            case HV -> 0.8;
            case EHV -> 1.1;
        };
    }

    // The wire's length along its curve.
    public static double length(double[] a, double[] b) {
        double total = 0;
        double[] last = a;
        for (int i = 1; i <= LENGTH_STEPS; i++) {
            double[] next = point(a, b, i / (double) LENGTH_STEPS);
            total += distance(last, next);
            last = next;
        }
        return total;
    }

    public static double distance(double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
