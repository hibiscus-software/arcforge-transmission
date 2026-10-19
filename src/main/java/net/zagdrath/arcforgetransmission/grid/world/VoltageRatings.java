/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// The voltage an FE block can take straight off a Pin Insulator before it's an overvoltage (PinInsulatorBlockEntity):
// any block takes LV; a block in arcforgetransmission:rated/<tier> takes that tier and every one below it (Arcforge's
// tempered blocks MV, hardened HV, arcforged EHV, out of the box; a datapack can add any mod's); this addon's own blocks
// take every tier.
public final class VoltageRatings {
    private VoltageRatings() {}

    public static TagKey<Block> rated(VoltageTier tier) {
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "rated/" + tier.id()));
    }

    public static boolean accepts(Level level, BlockPos pos, VoltageTier tier) {
        if (tier == VoltageTier.LV) {
            return true;
        }
        BlockState state = level.getBlockState(pos);
        if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals(ArcforgeTransmission.MODID)) {
            return true;
        }
        for (VoltageTier rating : VoltageTier.values()) {
            if (rating.ordinal() >= tier.ordinal() && state.is(rated(rating))) {
                return true;
            }
        }
        return false;
    }
}
