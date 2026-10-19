/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ValueInput;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.world.Generators;
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

    // A plant as its HUD is sent: its name (a translation key), the tier it connects at, its nameplate and its FE/t now,
    // and whether it's refused (the wrong tier: WRONG_TIER_GENERATOR).
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
