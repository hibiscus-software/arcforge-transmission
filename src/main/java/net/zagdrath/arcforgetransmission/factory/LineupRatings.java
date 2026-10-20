/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import net.zagdrath.arcforgetransmission.grid.GridSettings;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// A lineup's numbers, every one from its tier's wire (W, its rated amps) and the config. Power is FE/t as a long (an EHV
// lineup's run to past 16 million); currents are A per conductor. Pure, unit tested.
public final class LineupRatings {
    private LineupRatings() {}

    // A tier's wire rating, A.
    public static double wire(GridSettings settings, VoltageTier tier) {
        return settings.ratedAmps(tier);
    }

    // One full circuit at a tier, FE/t: what an incomer of that tier takes, and a transformer section's rating at `share`
    // of it (its primary's).
    public static long circuit(GridSettings settings, VoltageTier tier) {
        return Math.round(tier.volts() * settings.ratedAmps(tier));
    }

    public static long transformerRating(GridSettings settings, VoltageTier primary, double share) {
        return Math.round(circuit(settings, primary) * share);
    }

    // A transformer section's rated current on its secondary (the lower tier), A.
    public static double secondaryAmps(GridSettings settings, VoltageTier primary, double share) {
        return transformerRating(settings, primary, share) / (double) lower(primary).volts();
    }

    // A feeder's frame: its bus's wire times 1, 2, 4 or 8.
    public static double feederFrame(GridSettings settings, VoltageTier bus, int frame) {
        return wire(settings, bus) * SectionBreaker.FRAMES[Math.floorMod(frame, SectionBreaker.FRAMES.length)];
    }

    // A main breaker's frame, sized to what feeds it: a transformer's fan-cooled current on its secondary, or (no
    // transformer) one incoming circuit. A bus tie's is the same.
    public static double mainFrame(GridSettings settings, VoltageTier lineup, boolean transformer, double share, double fanRating) {
        return transformer ? secondaryAmps(settings, lineup, share) * fanRating : wire(settings, lineup);
    }

    // An incomer's pickup, A at the lineup's tier: past the most its transformer passes (or its main), by the margin.
    public static double incomerPickup(GridSettings settings, VoltageTier lineup, boolean transformer, double share, double limit, double fanRating,
            double margin) {
        double most = transformer ? transformerRating(settings, lineup, share) * limit / lineup.volts() : mainFrame(settings, lineup, false, share, fanRating);
        return most * margin;
    }

    // A transformer winding's ohms per conductor on its secondary: its resistance r (per unit) loses r of its rating at
    // full load (3 I^2 R = r P), its reactance x drops x of its voltage (I X / V = x).
    public static double windingOhms(VoltageTier secondary, long rating, double r) {
        double v = secondary.volts();
        return r * v * v / (3.0 * rating);
    }

    public static double windingReactance(VoltageTier secondary, long rating, double z, double r) {
        double v = secondary.volts(), x = Math.sqrt(Math.max(0, z * z - r * r));
        return x * v * v / rating;
    }

    // The tier one below (a transformer section's secondary); LV stays LV.
    public static VoltageTier lower(VoltageTier tier) {
        return VoltageTier.values()[Math.max(0, tier.ordinal() - 1)];
    }

    // The tier `drop` below.
    public static VoltageTier down(VoltageTier tier, int drop) {
        return VoltageTier.values()[Math.max(0, tier.ordinal() - drop)];
    }

    // FE/t as the HUD shows it: whole up to 10,000, then kFE/t and MFE/t (a long, so an EHV lineup's 16,777,216 shows).
    public static String power(long fe) {
        long a = Math.abs(fe);
        if (a < 10_000) {
            return fe + " FE/t";
        }
        if (a < 10_000_000) {
            return String.format(java.util.Locale.ROOT, "%.1f kFE/t", fe / 1_000.0);
        }
        return String.format(java.util.Locale.ROOT, "%.2f MFE/t", fe / 1_000_000.0);
    }

    // A comparator's reading of a load fraction: 0 for none (or open), 1 to 15 with the load (15 at its rating and over).
    public static int comparator(double fraction) {
        if (!(fraction > 0)) {
            return 0;
        }
        return (int) Math.max(1, Math.min(15, Math.ceil(fraction * 15.0 - 1.0E-9)));
    }
}
