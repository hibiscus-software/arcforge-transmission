/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.world;

import java.util.OptionalInt;

import org.jspecify.annotations.Nullable;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;
import net.zagdrath.arcforge.blockentity.machine.ArcQuarryBoundingBlockEntity;
import net.zagdrath.arcforge.blockentity.machine.CombustionPlantBlockEntity;
import net.zagdrath.arcforge.blockentity.machine.ThermoelectricPlantBlockEntity;
import net.zagdrath.arcforge.blockentity.multiblock.DieselGeneratorBlockEntity;
import net.zagdrath.arcforge.blockentity.multiblock.GasTurbineArrayBlockEntity;
import net.zagdrath.arcforge.blockentity.multiblock.ShellMultiblockBlockEntity;
import net.zagdrath.arcforge.blockentity.multiblock.SteamTurbineArrayBlockEntity;
import net.zagdrath.arcforge.multiblock.MultiblockPart;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// Power plants as grid generators: the data map arcforgetransmission:generator (on blocks) gives a plant the tier it
// connects at and its nameplate rating (FE/t), or "auto" to read the plant's own max output (a Steam Turbine Array's
// grows with its sections). A mapped plant feeds the grid only through a Unit Transformer of its own tier (its generator
// step-up), at no more than its nameplate and the transformer's rating (and what it has to give: a plant with a full
// buffer makes nothing, so what it makes this tick is no cap on what it can give); a pin
// insulator takes FE from a mapped plant only at LV. A block without an entry is as it always was. Arcforge's plants are
// mapped out of the box (data/arcforgetransmission/data_maps/block/generator.json); a datapack can map any mod's.
public final class Generators {
    // A plant's entry: its tier, and its nameplate (empty: "auto").
    public record Rating(VoltageTier tier, OptionalInt nameplate) {
        private static final Codec<VoltageTier> TIER = Codec.STRING.comapFlatMap(id -> {
            try {
                return DataResult.success(VoltageTier.byId(id));
            } catch (IllegalArgumentException e) {
                return DataResult.error(() -> "Not a tier: " + id);
            }
        }, VoltageTier::id);
        private static final Codec<OptionalInt> NAMEPLATE = Codec.either(Codec.intRange(1, Integer.MAX_VALUE),
                Codec.STRING.comapFlatMap(s -> s.equals("auto") ? DataResult.success(s) : DataResult.error(() -> "Not a rating: " + s), s -> s))
                .xmap(either -> either.map(OptionalInt::of, auto -> OptionalInt.empty()),
                        rating -> rating.isPresent() ? Either.left(rating.getAsInt()) : Either.right("auto"));
        public static final Codec<Rating> CODEC = RecordCodecBuilder.create(i -> i.group(
                TIER.fieldOf("tier").forGetter(Rating::tier),
                NAMEPLATE.optionalFieldOf("rating", OptionalInt.empty()).forGetter(Rating::nameplate)).apply(i, Rating::new));
    }

    public static final DataMapType<Block, Rating> GENERATOR = DataMapType.builder(Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "generator"),
            Registries.BLOCK, Rating.CODEC).build();

    // A mapped plant at a block: its name, tier, nameplate (FE/t), what it makes now (FE/t), and its block entity (the
    // multiblock's master; null if it has none), which tells two blocks of one plant apart.
    public record Plant(Component name, VoltageTier tier, int nameplate, int output, @Nullable BlockEntity entity) {}

    private Generators() {}

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener((RegisterDataMapTypesEvent event) -> event.register(GENERATOR));
    }

    public static @Nullable Rating rating(BlockState state) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.wrapAsHolder(state.getBlock()).getData(GENERATOR);
    }

    // The mapped plant a block is part of, or null.
    public static @Nullable Plant at(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Rating rating = rating(state);
        if (rating == null) {
            return null;
        }
        BlockEntity entity = entityOf(level, pos, state);
        int nameplate = rating.nameplate().isPresent() ? rating.nameplate().getAsInt() : maxOutput(entity);
        return new Plant(name(entity, state.getBlock()), rating.tier(), nameplate, output(entity, nameplate), entity);
    }

    // The block entity that runs a plant or machine (Loads too): a shell multiblock's master, a controller (from any of
    // its casing), an Arc Quarry (from its bounding blocks), or the block's own.
    public static @Nullable BlockEntity entityOf(Level level, BlockPos pos, BlockState state) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof ShellMultiblockBlockEntity shell) {
            return shell.getMaster();
        }
        if (entity instanceof ArcQuarryBoundingBlockEntity bounding) {
            return bounding.main();
        }
        if (entity == null && state.getBlock() instanceof MultiblockPart part && part.findController(level, pos) instanceof BlockEntity controller) {
            return controller;
        }
        return entity;
    }

    // "auto": the plant's own max output (unknown, no cap but the transformer's).
    private static int maxOutput(@Nullable BlockEntity entity) {
        if (entity instanceof DieselGeneratorBlockEntity diesel) {
            return diesel.maxOutput();
        }
        if (entity instanceof GasTurbineArrayBlockEntity gas) {
            return gas.maxOutput();
        }
        if (entity instanceof SteamTurbineArrayBlockEntity steam) {
            return steam.maxOutput();
        }
        return Integer.MAX_VALUE;
    }

    // What it makes this tick (a plant this addon can't read: its nameplate).
    private static int output(@Nullable BlockEntity entity, int nameplate) {
        if (entity instanceof DieselGeneratorBlockEntity diesel) {
            return diesel.getMade();
        }
        if (entity instanceof GasTurbineArrayBlockEntity gas) {
            return gas.getFePerTick();
        }
        if (entity instanceof SteamTurbineArrayBlockEntity steam) {
            return steam.getFePerTick();
        }
        if (entity instanceof CombustionPlantBlockEntity combustion) {
            return combustion.getOutputPerTick();
        }
        if (entity instanceof ThermoelectricPlantBlockEntity thermo) {
            return thermo.getFePerTick();
        }
        return nameplate;
    }

    // Its name as its screen calls it (a Steam Turbine Array, not its casing), else its block's.
    public static Component name(@Nullable BlockEntity entity, Block block) {
        return entity instanceof MenuProvider menu ? menu.getDisplayName() : (entity != null ? entity.getBlockState().getBlock() : block).getName();
    }

    // A name sent to the client: its translation key, or its text.
    public static String key(Component name) {
        return name.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : name.getString();
    }
}
