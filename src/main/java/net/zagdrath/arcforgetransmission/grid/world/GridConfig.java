/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.world;

import java.util.EnumMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

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
    private static final Map<VoltageTier, ModConfigSpec.DoubleValue> REACTANCE = new EnumMap<>(VoltageTier.class);
    // What a live bare wire of each tier does to whatever touches it (WireShock): flat half-hearts plus a share of its max
    // health. Both 0 is harmless.
    public static final Map<VoltageTier, ModConfigSpec.DoubleValue> SHOCK_DAMAGE = new EnumMap<>(VoltageTier.class);
    public static final Map<VoltageTier, ModConfigSpec.DoubleValue> SHOCK_FRACTION = new EnumMap<>(VoltageTier.class);

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
            REACTANCE.put(tier, BUILDER.comment("Ohms of reactance per block of conductor: what reactive current (capacitor banks, reactors) drops",
                    "voltage across.").defineInRange("reactance", tier.defaultReactance(), 0.0, 100.0));
            SHOCK_DAMAGE.put(tier, BUILDER.comment("Flat damage (half-hearts) a live bare wire does to anything touching it, every half",
                    "second, through armour. Insulated LV and MV wire never shocks.").defineInRange("shockDamage", tier.defaultShockDamage(), 0.0, 1000.0));
            SHOCK_FRACTION.put(tier, BUILDER.comment("Plus this share of the victim's max health (1 kills outright).")
                    .defineInRange("shockFraction", tier.defaultShockFraction(), 0.0, 1.0));
            BUILDER.pop();
        }
        BUILDER.pop();
    }

    public static final ModConfigSpec.IntValue BURNOUT_TICKS;
    public static final ModConfigSpec.BooleanValue BURNOUT_FIRE;
    public static final ModConfigSpec.DoubleValue BURNOUT_FIRE_CHANCE;
    public static final ModConfigSpec.EnumValue<Overvoltage> OVERVOLTAGE;
    public static final ModConfigSpec.IntValue POLE_TRANSFORMER_RATING;
    public static final ModConfigSpec.DoubleValue SUBSTATION_RATING;
    public static final ModConfigSpec.DoubleValue FULL_POWER_VOLTAGE, CUTOFF_VOLTAGE, LOAD_POWER_FACTOR, MIN_SHARE;
    public static final ModConfigSpec.IntValue VOLTAGE_ROUNDS;
    public static final ModConfigSpec.DoubleValue TAP_STEP, TAP_BAND;
    public static final ModConfigSpec.IntValue TAP_STEPS, TAP_DELAY;
    public static final ModConfigSpec.DoubleValue FAULT_MULTIPLE;
    public static final ModConfigSpec.IntValue FLASHOVER_TICKS;
    public static final ModConfigSpec.DoubleValue UNBALANCE_THRESHOLD;
    public static final ModConfigSpec.IntValue UNBALANCE_TICKS;
    public static final ModConfigSpec.DoubleValue FUSE_MELT, FUSE_COOLING;
    public static final ModConfigSpec.IntValue GRADING_INTERVAL;
    public static final ModConfigSpec.DoubleValue INSTANT_MULTIPLE, DIFFERENTIAL_PICKUP, DIFFERENTIAL_SLOPE;
    public static final ModConfigSpec.IntValue FAST_SHOTS;
    public static final ModConfigSpec.IntValue SECTIONALIZER_COUNT, SECTIONALIZER_RESET;
    public static final ModConfigSpec.DoubleValue SECTIONALIZER_ACTUATING;
    public static final ModConfigSpec.IntValue TRANSFER_TICKS, RETRANSFER_TICKS;
    public static final ModConfigSpec.DoubleValue CAPACITOR_VARS, REACTOR_VARS, SWITCH_LOW, SWITCH_HIGH;
    public static final ModConfigSpec.IntValue YARD_RADIUS, GROUND_GRID_RADIUS;
    public static final ModConfigSpec.DoubleValue STEP_FRACTION;
    public static final ModConfigSpec.DoubleValue FUSE_FACTOR;
    public static final ModConfigSpec.IntValue BREAKER_TRIP_TICKS;
    public static final ModConfigSpec.IntValue RECLOSE_DELAY;
    public static final ModConfigSpec.IntValue RECLOSE_SHOTS;
    public static final ModConfigSpec.DoubleValue LIGHTNING_CHANCE;
    public static final ModConfigSpec.BooleanValue LIGHTNING_DAMAGE;
    public static final ModConfigSpec.IntValue ARRESTER_RANGE;

    static {
        BUILDER.comment("Faults.").push("faults");
        BURNOUT_TICKS = BUILDER.comment("Ticks in a row over its rating before a wire burns out (it drops Burnt Wire and is gone).")
                .defineInRange("burnoutTicks", 40, 1, 72_000);
        BURNOUT_FIRE = BUILDER.comment("Whether a wire burning out can set a fire under its middle.").define("burnoutFire", true);
        BURNOUT_FIRE_CHANCE = BUILDER.comment("The chance (0 to 1) that it does.").defineInRange("burnoutFireChance", 0.5, 0.0, 1.0);
        OVERVOLTAGE = BUILDER.comment("What an MV, HV or EHV pin insulator does to a foreign FE block (one not from this addon) it's mounted on",
                "while switched to input or output: BREAK (drops it), DESTROY, EXPLODE (a small blast that breaks no blocks) or NONE.")
                .defineEnum("overvoltage", Overvoltage.BREAK);
        BUILDER.pop();
        BUILDER.comment("Transformers: electrical links between tiers, carrying power either way, holding none.").push("transformers");
        POLE_TRANSFORMER_RATING = BUILDER.comment("The most power (FE/t) a Pole-Mount or MV Distribution Transformer passes.")
                .defineInRange("poleRating", 1_024, 1, 1_000_000_000);
        SUBSTATION_RATING = BUILDER.comment("The most power a Substation Transformer passes, as a share of its high side's rated power.")
                .defineInRange("substationRating", 1.0, 0.01, 100.0);
        BUILDER.pop();
        BUILDER.comment("Voltage: each wire drops R I + X I; loads below fullPower get less, none at cutoff (per unit of nominal).").push("voltage");
        FULL_POWER_VOLTAGE = BUILDER.comment("At and above this voltage a load gets all it asks for.").defineInRange("fullPower", 0.9, 0.0, 2.0);
        CUTOFF_VOLTAGE = BUILDER.comment("At and below this, nothing (a share rising straight between the two).").defineInRange("cutoff", 0.7, 0.0, 2.0);
        MIN_SHARE = BUILDER.comment("The least share of what it asks a sagging load keeps asking for, so it stays a load and its voltage",
                "can recover.").defineInRange("minShare", 0.01, 0.0, 1.0);
        LOAD_POWER_FACTOR = BUILDER.comment("Loads' power factor (1: no reactive current; lower draws more current for the same power).")
                .defineInRange("loadPowerFactor", 1.0, 0.1, 1.0);
        VOLTAGE_ROUNDS = BUILDER.comment("Rounds a tick of matching loads to their voltage (it carries over, so it settles over a few ticks).")
                .defineInRange("rounds", 3, 1, 10);
        TAP_STEP = BUILDER.comment("A voltage regulator's or load tap changer's step (per unit).").defineInRange("tapStep", 0.00625, 0.0001, 0.1);
        TAP_STEPS = BUILDER.comment("Its steps either way from neutral.").defineInRange("tapSteps", 16, 1, 64);
        TAP_BAND = BUILDER.comment("The band (per unit, round 1) it holds the voltage it watches within.").defineInRange("tapBand", 0.04, 0.001, 0.5);
        TAP_DELAY = BUILDER.comment("Ticks out of band before it steps.").defineInRange("tapDelay", 30, 1, 72_000);
        CAPACITOR_VARS = BUILDER.comment("A Capacitor Bank's reactive power, as a share of its tier's rated power.").defineInRange("capacitorVars", 0.4, 0.0, 10.0);
        REACTOR_VARS = BUILDER.comment("A Shunt Reactor's, likewise.").defineInRange("reactorVars", 0.4, 0.0, 10.0);
        SWITCH_LOW = BUILDER.comment("Under a Relay Panel, a capacitor bank switches in below this voltage (a reactor out)...")
                .defineInRange("switchLow", 0.96, 0.0, 2.0);
        SWITCH_HIGH = BUILDER.comment("...and out above this (a reactor in).").defineInRange("switchHigh", 1.04, 0.0, 2.0);
        BUILDER.pop();
        BUILDER.comment("Protection: fuse cutouts, reclosers and circuit breakers.").push("protection");
        FUSE_FACTOR = BUILDER.comment("A fuse's rating, times its tier's wire rating: over it, its element heats (its melting curve).")
                .defineInRange("fuseFactor", 1.0, 0.1, 10.0);
        FUSE_MELT = BUILDER.comment("Its melting rating: the heat (each tick, (I / rating)^2 - 1) it melts at. Ten times its rating melts it in",
                "about three ticks; just over, it takes a long time.").defineInRange("fuseMelt", 300.0, 1.0, 1.0E9);
        FUSE_COOLING = BUILDER.comment("The share of its heat it loses each tick at or under its rating.").defineInRange("fuseCooling", 0.05, 0.0, 1.0);
        GRADING_INTERVAL = BUILDER.comment("Ticks each protective device between a breaker and the trouble adds to its time, so the nearest trips first.")
                .defineInRange("gradingInterval", 8, 0, 1_000);
        INSTANT_MULTIPLE = BUILDER.comment("A relay's instantaneous element: trips at once over this times the rating (the nearest device only).")
                .defineInRange("instantMultiple", 8.0, 1.0, 1_000.0);
        FAST_SHOTS = BUILDER.comment("Fuse-saving: how many first shots a relayed recloser trips fast.").defineInRange("fastShots", 1, 0, 10);
        DIFFERENTIAL_PICKUP = BUILDER.comment("A transformer differential's pickup, as a share of the transformer's rating.")
                .defineInRange("differentialPickup", 0.1, 0.0, 10.0);
        DIFFERENTIAL_SLOPE = BUILDER.comment("Its slope: the share of the larger side the two may also differ by.").defineInRange("differentialSlope", 0.25, 0.0, 1.0);
        SECTIONALIZER_COUNT = BUILDER.comment("Recloser operations a sectionalizer counts before it opens (in the dead time).").defineInRange("sectionalizerCount", 2, 1, 10);
        SECTIONALIZER_ACTUATING = BUILDER.comment("Its actuating current, times its rating: fault current over it counts.")
                .defineInRange("sectionalizerActuating", 1.6, 1.0, 100.0);
        SECTIONALIZER_RESET = BUILDER.comment("Ticks of live line with no fault before it forgets its count.").defineInRange("sectionalizerReset", 600, 1, 72_000);
        TRANSFER_TICKS = BUILDER.comment("An automatic transfer switch moves to its alternate after its preferred has been dead this long...")
                .defineInRange("transferTicks", 40, 1, 72_000);
        RETRANSFER_TICKS = BUILDER.comment("...and back after it has been live this long.").defineInRange("retransferTicks", 200, 1, 72_000);
        BREAKER_TRIP_TICKS = BUILDER.comment("Ticks in a row over its trip setting before a recloser or breaker trips (well inside a wire's burnout).")
                .defineInRange("tripTicks", 10, 1, 72_000);
        RECLOSE_DELAY = BUILDER.comment("Ticks a recloser or breaker waits after a trip before it closes again.").defineInRange("recloseDelay", 60, 1, 72_000);
        RECLOSE_SHOTS = BUILDER.comment("How many times it recloses before it locks out (closed again by hand).").defineInRange("recloseShots", 3, 0, 10);
        BUILDER.pop();
        BUILDER.comment("Lightning.").push("lightning");
        LIGHTNING_CHANCE = BUILDER.comment("In a thunderstorm, each loaded span's chance each tick of drawing a strike (0 for none).")
                .defineInRange("strikeChance", 2.0E-6, 0.0, 1.0);
        LIGHTNING_DAMAGE = BUILDER.comment("Whether a strike on a line destroys the transformers on it that have no Surge Arrester near.")
                .define("transformerDamage", true);
        ARRESTER_RANGE = BUILDER.comment("How near (blocks) a Surge Arrester protects a transformer.").defineInRange("arresterRange", 3, 1, 16);
        BUILDER.pop();
        BUILDER.comment("Ground faults: a conductor to ground (a lightning flashover, a grounded line made live).").push("groundFaults");
        FAULT_MULTIPLE = BUILDER.comment("The most fault current a source gives, times its rating (less further out along the line).")
                .defineInRange("faultMultiple", 20.0, 1.0, 1_000.0);
        FLASHOVER_TICKS = BUILDER.comment("How long a flashover lasts if nothing clears it; then the span burns out.").defineInRange("flashoverTicks", 40, 1, 72_000);
        UNBALANCE_THRESHOLD = BUILDER.comment("A device's conductors' currents spreading more than this (share of the largest) is unbalance.")
                .defineInRange("unbalanceThreshold", 0.5, 0.01, 1.0);
        UNBALANCE_TICKS = BUILDER.comment("Ticks of unbalance or a lost phase before a relayed breaker trips on it.").defineInRange("unbalanceTicks", 100, 1, 72_000);
        YARD_RADIUS = BUILDER.comment("A fault with Station Fence within this many blocks is in a yard: step-potential shocks there...")
                .defineInRange("yardRadius", 12, 1, 64);
        GROUND_GRID_RADIUS = BUILDER.comment("...unless Ground Grid lies within this many blocks of it.").defineInRange("groundGridRadius", 4, 1, 32);
        STEP_FRACTION = BUILDER.comment("A step-potential shock: this share of the victim's max health.").defineInRange("stepFraction", 0.5, 0.0, 1.0);
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
            Synced s = synced;
            return s != null ? s.ratedAmps()[tier.ordinal()] : RATED_AMPS.get(tier).getAsDouble();
        }

        @Override
        public double maxSpan(VoltageTier tier) {
            Synced s = synced;
            return s != null ? s.maxSpan()[tier.ordinal()] : MAX_SPAN.get(tier).getAsInt();
        }

        @Override
        public int burnoutTicks() {
            return BURNOUT_TICKS.getAsInt();
        }

        @Override
        public double reactance(VoltageTier tier) {
            return REACTANCE.get(tier).getAsDouble();
        }

        @Override
        public double loadPowerFactor() {
            return LOAD_POWER_FACTOR.getAsDouble();
        }

        @Override
        public double fullPowerVoltage() {
            return FULL_POWER_VOLTAGE.getAsDouble();
        }

        @Override
        public double cutoffVoltage() {
            return CUTOFF_VOLTAGE.getAsDouble();
        }

        @Override
        public double minShare() {
            return MIN_SHARE.getAsDouble();
        }

        @Override
        public int voltageRounds() {
            return VOLTAGE_ROUNDS.getAsInt();
        }

        @Override
        public double faultMultiple() {
            return FAULT_MULTIPLE.getAsDouble();
        }
    };

    // The settings a client shows, as the server it's on has them (GridPayloads.Settings): each tier's wire rating and
    // span, the fuse factor, the tap step and the sectionalizer's count. Null: its own config (single player, or before
    // the server says).
    public record Synced(double[] ratedAmps, int[] maxSpan, double fuseFactor, double tapStep, int sectionalizerCount) {}

    private static volatile @Nullable Synced synced;

    public static void sync(@Nullable Synced settings) {
        synced = settings;
    }

    // This side's own config, to send.
    public static Synced local() {
        int n = VoltageTier.values().length;
        double[] rated = new double[n];
        int[] span = new int[n];
        for (VoltageTier tier : VoltageTier.values()) {
            rated[tier.ordinal()] = RATED_AMPS.get(tier).getAsDouble();
            span[tier.ordinal()] = MAX_SPAN.get(tier).getAsInt();
        }
        return new Synced(rated, span, FUSE_FACTOR.getAsDouble(), TAP_STEP.getAsDouble(), SECTIONALIZER_COUNT.getAsInt());
    }

    public static double fuseFactor() {
        Synced s = synced;
        return s != null ? s.fuseFactor() : FUSE_FACTOR.getAsDouble();
    }

    public static double tapStep() {
        Synced s = synced;
        return s != null ? s.tapStep() : TAP_STEP.getAsDouble();
    }

    public static int sectionalizerCount() {
        Synced s = synced;
        return s != null ? s.sectionalizerCount() : SECTIONALIZER_COUNT.getAsInt();
    }

    private GridConfig() {}
}
