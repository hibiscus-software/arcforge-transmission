/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.effects.client;

import net.neoforged.neoforge.common.ModConfigSpec;

// The client's sounds and particles (arcforgetransmission-client.toml): each player's own, never the server's. Every
// volume, budget and toggle of the transformers' hum, EHV corona in rain and the arcs of interruptions.
public final class EffectsConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue HUM;
    public static final ModConfigSpec.DoubleValue HUM_VOLUME, HUM_IDLE, HUM_PITCH_RISE, HUM_RANGE;
    public static final ModConfigSpec.IntValue HUM_MAX, HUM_FADE_TICKS;
    public static final ModConfigSpec.BooleanValue CORONA, CORONA_HV;
    public static final ModConfigSpec.DoubleValue CORONA_VOLUME, CORONA_RANGE, CORONA_HV_STRENGTH, CORONA_DAYLIGHT;
    public static final ModConfigSpec.IntValue CORONA_PARTICLES, CORONA_SPANS;
    public static final ModConfigSpec.BooleanValue ARCS;
    public static final ModConfigSpec.DoubleValue ARC_VOLUME, ARC_PARTICLES;

    static {
        BUILDER.comment("Transformer hum: substation, unit, pole-mount and MV distribution transformers hum while energised, louder (and a",
                "little higher) with their load.").push("hum");
        HUM = BUILDER.comment("Whether transformers hum.").define("enabled", true);
        HUM_VOLUME = BUILDER.comment("Its volume at full load.").defineInRange("volume", 0.5, 0.0, 2.0);
        HUM_IDLE = BUILDER.comment("Unloaded (but energised), this share of that.").defineInRange("idle", 0.35, 0.0, 1.0);
        HUM_PITCH_RISE = BUILDER.comment("How much higher it hums at full load (0.05: 5%).").defineInRange("pitchRise", 0.05, 0.0, 0.5);
        HUM_RANGE = BUILDER.comment("Transformers within this many blocks hum.").defineInRange("range", 16.0, 1.0, 64.0);
        HUM_MAX = BUILDER.comment("At most this many hum at once (the nearest).").defineInRange("maxInstances", 6, 0, 32);
        HUM_FADE_TICKS = BUILDER.comment("Ticks a hum takes to fade fully in or out (10: half a second).").defineInRange("fadeTicks", 10, 0, 200);
        BUILDER.pop();
        BUILDER.comment("Corona: in rain or thunder, energised EHV lines near you buzz softly and glow faintly blue-violet, more at night.")
                .push("corona");
        CORONA = BUILDER.comment("Whether wet EHV lines buzz and glow.").define("enabled", true);
        CORONA_VOLUME = BUILDER.comment("The buzz's volume, at its loudest.").defineInRange("volume", 0.4, 0.0, 2.0);
        CORONA_RANGE = BUILDER.comment("Spans within this many blocks of you buzz and glow.").defineInRange("range", 24.0, 1.0, 64.0);
        CORONA_PARTICLES = BUILDER.comment("The glow's particles each tick, spread over the nearest spans (the budget).")
                .defineInRange("particlesPerTick", 4, 0, 64);
        CORONA_SPANS = BUILDER.comment("The nearest spans that share them.").defineInRange("spans", 6, 1, 64);
        CORONA_DAYLIGHT = BUILDER.comment("In full daylight the glow is this share of what it is at night.").defineInRange("daylight", 0.3, 0.0, 1.0);
        CORONA_HV = BUILDER.comment("Whether wet HV lines do too, weaker.").define("hv", false);
        CORONA_HV_STRENGTH = BUILDER.comment("How much weaker: this share of EHV's.").defineInRange("hvStrength", 0.35, 0.0, 1.0);
        BUILDER.pop();
        BUILDER.comment("Arcs: a breaker or recloser opening on a fault, a fuse blowing, a disconnect opened under load or a flashover",
                "flashes, sparks and cracks, bigger with the fault current and the tier.").push("arcs");
        ARCS = BUILDER.comment("Whether interruptions flash, spark and crack.").define("enabled", true);
        ARC_VOLUME = BUILDER.comment("Their sounds' volume.").defineInRange("volume", 1.0, 0.0, 2.0);
        ARC_PARTICLES = BUILDER.comment("Their sparks, times this.").defineInRange("particles", 1.0, 0.0, 4.0);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private EffectsConfig() {}
}
