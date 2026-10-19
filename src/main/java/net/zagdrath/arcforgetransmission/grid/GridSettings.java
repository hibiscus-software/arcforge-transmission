/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

// The numbers the grid runs on, read each tick (so config changes apply at once). GridConfig supplies them in game;
// DEFAULTS is what tests use.
public interface GridSettings {
    // Ohms per block of one conductor.
    double resistance(VoltageTier tier);

    // The current (A per conductor) a wire carries indefinitely.
    double ratedAmps(VoltageTier tier);

    // The longest span a wire may make (straight distance between its attach points), in blocks.
    double maxSpan(VoltageTier tier);

    // Ticks in a row over its rating before a wire burns out.
    int burnoutTicks();

    GridSettings DEFAULTS = new GridSettings() {
        @Override
        public double resistance(VoltageTier tier) {
            return tier.defaultResistance();
        }

        @Override
        public double ratedAmps(VoltageTier tier) {
            return tier.defaultRatedAmps();
        }

        @Override
        public double maxSpan(VoltageTier tier) {
            return tier.defaultMaxSpan();
        }

        @Override
        public int burnoutTicks() {
            return 40;
        }
    };
}
