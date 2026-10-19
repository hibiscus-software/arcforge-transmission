/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.world;

import java.util.EnumMap;
import java.util.Map;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.zagdrath.arcforgetransmission.grid.GridSettings;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// Every number the grid runs on (arcforgetransmission-common.toml). Read each tick, so a change applies at once.
public final class GridConfig {
    // What happens to a foreign FE block (not this addon's) an MV-or-higher pin insulator serves.
    public enum Overvoltage {
        // Broken as if mined: it drops itself.
        BREAK,
        // Gone, nothing dropped.
        DESTROY,
        // Gone in a small explosion that breaks no blocks.
        EXPLODE,
        // Left alone (and fed, at the risk of nothing).
        NONE
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final Map<VoltageTier, ModConfigSpec.DoubleValue> RESISTANCE = new EnumMap<>(VoltageTier.class);
    private static final Map<VoltageTier, ModConfigSpec.DoubleValue> RATED_AMPS = new EnumMap<>(VoltageTier.class);
    private static final Map<VoltageTier, ModConfigSpec.IntValue> MAX_SPAN = new EnumMap<>(VoltageTier.class);

    static {
        BUILDER.comment("Wires, one section per tier. Each wire is one conductor; I = P / V in each conductor of a circuit,",
                "and each loses I^2 R as heat, R being the resistance per block times the wire's arc length.").push("wires");
        for (VoltageTier tier : VoltageTier.values()) {
            BUILDER.comment(tier.name() + " (" + tier.volts() + " V, " + tier.conductor() + ")").push(tier.id());
            RESISTANCE.put(tier, BUILDER.comment("Ohms per block of conductor.").defineInRange("resistance", tier.defaultResistance(), 0.0, 100.0));
            RATED_AMPS.put(tier, BUILDER.comment("The current (A) a wire carries indefinitely; over it, it starts to burn out.")
                    .defineInRange("ratedAmps", (double) tier.defaultRatedAmps(), 0.001, 1_000_000.0));
            MAX_SPAN.put(tier, BUILDER.comment("The longest span (straight distance between insulators), in blocks.")
                    .defineInRange("maxSpan", tier.defaultMaxSpan(), 1, 256));
            BUILDER.pop();
        }
        BUILDER.pop();
    }

    public static final ModConfigSpec.IntValue BURNOUT_TICKS;
    public static final ModConfigSpec.BooleanValue BURNOUT_FIRE;
    public static final ModConfigSpec.DoubleValue BURNOUT_FIRE_CHANCE;
    public static final ModConfigSpec.EnumValue<Overvoltage> OVERVOLTAGE;
    public static final ModConfigSpec.IntValue TRANSFORMER_BUFFER;

    static {
        BUILDER.comment("Faults.").push("faults");
        BURNOUT_TICKS = BUILDER.comment("Ticks in a row over its rating before a wire burns out (it drops Burnt Wire and is gone).")
                .defineInRange("burnoutTicks", 40, 1, 72_000);
        BURNOUT_FIRE = BUILDER.comment("Whether a wire burning out can set a fire under its middle.").define("burnoutFire", true);
        BURNOUT_FIRE_CHANCE = BUILDER.comment("The chance (0 to 1) that it does.").defineInRange("burnoutFireChance", 0.5, 0.0, 1.0);
        OVERVOLTAGE = BUILDER.comment("What an MV, HV or EV pin insulator does to a foreign FE block (one not from this addon) it's mounted on",
                "while switched to input or output: BREAK (drops it), DESTROY, EXPLODE (a small blast that breaks no blocks) or NONE.")
                .defineEnum("overvoltage", Overvoltage.BREAK);
        BUILDER.pop();
        BUILDER.comment("The Pole-Mount Transformer.").push("poleTransformer");
        TRANSFORMER_BUFFER = BUILDER.comment("FE it holds between its three-phase primary and its split-phase secondary.")
                .defineInRange("buffer", 4_096, 1, 1_000_000);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    // The grid's settings, read from the config each time.
    public static final GridSettings SETTINGS = new GridSettings() {
        @Override
        public double resistance(VoltageTier tier) {
            return RESISTANCE.get(tier).getAsDouble();
        }

        @Override
        public double ratedAmps(VoltageTier tier) {
            return RATED_AMPS.get(tier).getAsDouble();
        }

        @Override
        public double maxSpan(VoltageTier tier) {
            return MAX_SPAN.get(tier).getAsInt();
        }

        @Override
        public int burnoutTicks() {
            return BURNOUT_TICKS.getAsInt();
        }
    };

    private GridConfig() {}
}
