/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.world.Generators;
import net.zagdrath.arcforgetransmission.grid.world.Loads;
import org.jspecify.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;

// An endpoint that moves FE (a Pin Insulator's port, a transformer): what the insulator HUD (InsulatorHud) shows under
// its wires, as the client last heard.
public interface PortHud {
    // How it's set (its mode, limit or direction), or null when it has nothing to show.
    @Nullable Component hudMode();

    FlowSolver.PortStatus status();

    // FE/t through it last tick.
    double lastFlow();

    // Whether the HUD shows its status and FE/t (not for a switch, only its position).
    default boolean hudFlow() {
        return true;
    }

    // The mapped power plant it feeds from (Generators), or one of the wrong tier it refuses; null for none.
    default @Nullable Generator hudGenerator() {
        return null;
    }

    // The mapped machine it feeds (Loads), or one of another tier it refuses; null for none.
    default @Nullable Load hudLoad() {
        return null;
    }

    // A machine as its HUD is sent: its name (a translation key), the tier it takes, its rating and what all its feeds
    // gave it last tick (FE/t), and whether it's refused (another tier: WRONG_TIER_LOAD).
    record Load(String name, VoltageTier tier, int rating, long draw, boolean wrong) {
        public static @Nullable Load of(Level level, Loads.@Nullable Machine machine, boolean wrong) {
            return machine == null ? null : new Load(Generators.key(machine.name()), machine.tier(), machine.rating(), Loads.draw(level, machine), wrong);
        }

        // Its comparator signal: its draw as a share of its rating, 0 to 15.
        public int signal() {
            return wrong || rating <= 0 ? 0 : (int) Math.max(0, Math.min(15, Math.round(15.0 * draw / rating)));
        }

        public static void write(CompoundTag tag, @Nullable Load load) {
            tag.putString("hud_load", load == null ? "" : load.name);
            if (load != null) {
                tag.putInt("hud_load_tier", load.tier.ordinal());
                tag.putInt("hud_load_rating", load.rating);
                tag.putLong("hud_load_draw", load.draw);
                tag.putBoolean("hud_load_wrong", load.wrong);
            }
        }

        // From a HUD update: the machine it names, null for none, or `current` if the update says nothing.
        public static @Nullable Load read(ValueInput input, @Nullable Load current) {
            var name = input.read("hud_load", Codec.STRING);
            if (name.isEmpty()) {
                return current;
            }
            if (name.get().isEmpty()) {
                return null;
            }
            return new Load(name.get(), VoltageTier.values()[input.read("hud_load_tier", Codec.INT).orElse(0)], input.read("hud_load_rating", Codec.INT).orElse(0),
                    input.read("hud_load_draw", Codec.LONG).orElse(0L), input.read("hud_load_wrong", Codec.BOOL).orElse(false));
        }
    }

    // A plant as its HUD is sent: its name (a translation key), the tier it connects at, its nameplate and the FE/t it
    // gives now, and whether it's refused (the wrong tier: WRONG_TIER_GENERATOR).
    record Generator(String name, VoltageTier tier, int nameplate, int output, boolean wrong) {
        public static @Nullable Generator of(Generators.@Nullable Plant plant, boolean wrong) {
            return plant == null ? null : new Generator(Generators.key(plant.name()), plant.tier(), plant.nameplate(), plant.output(), wrong);
        }

        public void write(CompoundTag tag) {
            tag.putString("hud_gen", name);
            tag.putInt("hud_gen_tier", tier.ordinal());
            tag.putInt("hud_gen_nameplate", nameplate);
            tag.putInt("hud_gen_output", output);
            tag.putBoolean("hud_gen_wrong", wrong);
        }

        // From a HUD update: the plant it names, null for none (an empty name), or `current` if the update says nothing.
        public static @Nullable Generator read(ValueInput input, @Nullable Generator current) {
            var name = input.read("hud_gen", Codec.STRING);
            if (name.isEmpty()) {
                return current;
            }
            if (name.get().isEmpty()) {
                return null;
            }
            return new Generator(name.get(), VoltageTier.values()[input.read("hud_gen_tier", Codec.INT).orElse(0)],
                    input.read("hud_gen_nameplate", Codec.INT).orElse(0), input.read("hud_gen_output", Codec.INT).orElse(0),
                    input.read("hud_gen_wrong", Codec.BOOL).orElse(false));
        }

        public static void write(CompoundTag tag, @Nullable Generator generator) {
            if (generator == null) {
                tag.putString("hud_gen", "");
            } else {
                generator.write(tag);
            }
        }
    }
}
