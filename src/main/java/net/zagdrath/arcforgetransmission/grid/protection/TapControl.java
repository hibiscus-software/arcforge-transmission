/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.protection;

// A voltage regulator's or load tap changer's control: holds the voltage it watches (per unit, past its own boost) within
// `band` round 1, stepping its tap one `step` at a time (at most `steps` either way), a step once the voltage has been out
// of band `delay` ticks in a row. Its boost, per unit, is its position times the step.
public final class TapControl {
    private int position, waited;

    public int position() {
        return position;
    }

    public void restore(int position) {
        this.position = position;
        waited = 0;
    }

    public double boost(double step) {
        return position * step;
    }

    // One tick watching `volts`. True when it steps.
    public boolean tick(double volts, double band, double step, int steps, int delay) {
        int want = volts < 1.0 - band / 2 ? 1 : volts > 1.0 + band / 2 ? -1 : 0;
        if (want == 0 || Math.abs(position + want) > steps) {
            waited = 0;
            return false;
        }
        if (++waited >= delay) {
            position += want;
            waited = 0;
            return true;
        }
        return false;
    }
}
