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

    // Ohms of reactance per block of one conductor (what reactive current drops voltage across).
    default double reactance(VoltageTier tier) {
        return tier.defaultReactance();
    }

    // A load's power factor (1: none of its current reactive).
    default double loadPowerFactor() {
        return 1.0;
    }

    // At and above this voltage (per unit) a load gets all it asks for; at and below cutoff, nothing; between, a share
    // rising straight from one to the other.
    default double fullPowerVoltage() {
        return 0.9;
    }

    default double cutoffVoltage() {
        return 0.7;
    }

    // The most fault current a source gives, as a multiple of its tier's rating (at its own terminals; the line's
    // resistance lowers it further out).
    default double faultMultiple() {
        return 20.0;
    }

    // A source's own impedance, ohms: what limits a fault at its terminals to faultMultiple times the rating.
    default double sourceImpedance(VoltageTier tier) {
        return tier.volts() / (faultMultiple() * ratedAmps(tier));
    }

    // The least share of its demand a load is asked for, however low its voltage (so it stays a load, and recovers).
    default double minShare() {
        return 0.01;
    }

    // Rounds per tick of matching loads to their voltage (each tick starts from the last one's, so it settles over a few).
    default int voltageRounds() {
        return 3;
    }

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
