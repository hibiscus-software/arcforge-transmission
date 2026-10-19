/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.Locale;

// The four wire tiers: their line voltage, conductor and current rating are fixed (the design's numbers); resistance and
// the longest span are defaults that GridSettings may override from config. The default resistances lose about 2.5% of a
// tier's rated power in one longest span at its rated current (three conductors), so a long line is worth stepping up;
// the superconductor loses next to nothing. 1 V x 1 A = 1 FE/t, so a tier's rated power
// is volts x amps. A network is one tier only: transformers (later) are the only bridge between tiers.
public enum VoltageTier {
    LV(128, "copper", 8, 0.008, 16, 1.0F),
    MV(512, "aluminium", 16, 0.011, 24, 1.25F),
    HV(2_048, "silver_alloy", 32, 0.017, 32, 1.5F),
    EHV(8_192, "superconductor", 64, 0.00001, 64, 2.0F);

    private final int volts, ratedAmps, defaultMaxSpan;
    private final String conductor;
    private final double defaultResistance;
    private final float wireWidthPx;

    VoltageTier(int volts, String conductor, int ratedAmps, double defaultResistance, int defaultMaxSpan, float wireWidthPx) {
        this.volts = volts;
        this.conductor = conductor;
        this.ratedAmps = ratedAmps;
        this.defaultResistance = defaultResistance;
        this.defaultMaxSpan = defaultMaxSpan;
        this.wireWidthPx = wireWidthPx;
    }

    public int volts() {
        return volts;
    }

    // The conductor's material, as a lang suffix (copper, aluminium, silver_alloy, superconductor).
    public String conductor() {
        return conductor;
    }

    public int defaultRatedAmps() {
        return ratedAmps;
    }

    // Ohms per block of conductor (one conductor; a circuit has two or three).
    public double defaultResistance() {
        return defaultResistance;
    }

    public int defaultMaxSpan() {
        return defaultMaxSpan;
    }

    // What touching a live bare wire of the tier does: a flat amount (half-hearts) plus a share of the victim's max
    // health. LV stings (1.5 hearts); MV takes a quarter of your health, HV half, and EHV all of it.
    public double defaultShockDamage() {
        return this == LV ? 3.0 : 0.0;
    }

    public double defaultShockFraction() {
        return switch (this) {
            case LV -> 0.0;
            case MV -> 0.25;
            case HV -> 0.5;
            case EHV -> 1.0;
        };
    }

    // Whether its wire comes rubber-insulated too (LV and MV; HV and EHV lines are always bare).
    public boolean insulatable() {
        return this == LV || this == MV;
    }

    // How wide each conductor is drawn, in px.
    public float wireWidthPx() {
        return wireWidthPx;
    }

    // Rated power at the rated current: volts x amps, in FE/t.
    public long defaultMaxPower() {
        return (long) volts * ratedAmps;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    // By id; "ev", EHV's name before it was renamed, still reads as EHV (grids saved then).
    public static VoltageTier byId(String id) {
        return id.equals("ev") ? EHV : valueOf(id.toUpperCase(Locale.ROOT));
    }
}
