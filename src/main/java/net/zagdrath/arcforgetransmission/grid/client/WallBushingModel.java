/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.client;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.DelegateBlockStateModel;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.block.WallBushingBlock;
import net.zagdrath.arcforgetransmission.grid.block.WallBushingBlockEntity;

// A Wall Bushing with a Conduit Facade: the block it's dressed in, drawn whole in its place from that block's own model
// (every part and layer, so it reads as the real block in a wall), or the blank facade (Arcforge's facade_blank) while
// undressed. Without a facade, its own model. The dressed block's tints come through (Tints): its tint layers keep their
// indices, and the bushing's tint sources look the facade up and ask the target's own source.
public final class WallBushingModel extends DelegateBlockStateModel {
    public static final int TINTS = 4;
    private static final StandaloneModelKey<BlockStateModelPart> BLANK = new StandaloneModelKey<>(() -> ArcforgeTransmission.MODID + ":wall_bushing_blank");

    private final BlockStateModelPart blank;

    private WallBushingModel(BlockStateModel delegate, BlockStateModelPart blank) {
        super(delegate);
        this.blank = blank;
    }

    public static void registerStandalone(ModelEvent.RegisterStandalone event) {
        event.register(BLANK, SimpleUnbakedStandaloneModel.simpleModelWrapper(Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID,
                "block/wall_bushing_blank"), BlockModelRotation.IDENTITY));
    }

    public static void wrap(ModelEvent.ModifyBakingResult event) {
        var result = event.getBakingResult();
        BlockStateModelPart blank = result.standaloneModels().get(BLANK);
        if (blank == null) {
            return;
        }
        result.blockStateModels().replaceAll((state, model) -> state.getBlock() instanceof WallBushingBlock ? new WallBushingModel(model, blank) : model);
    }

    private static WallBushingBlockEntity.@Nullable Facade facade(BlockAndTintGetter level, BlockPos pos) {
        return level.getModelData(pos).get(WallBushingBlockEntity.FACADE);
    }

    private static BlockStateModel model(BlockState state) {
        return Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
        WallBushingBlockEntity.Facade facade = facade(level, pos);
        if (facade == null) {
            super.collectParts(level, pos, state, random, parts);
        } else if (facade.target() == null) {
            parts.add(blank);
        } else {
            model(facade.target()).collectParts(level, pos, facade.target(), random, parts);
        }
    }

    // The look changes with the block entity, not the block state: never share a cached mesh while it has a facade.
    @Override
    public @Nullable Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        return facade(level, pos) == null ? super.createGeometryKey(level, pos, state, random) : null;
    }

    @Override
    @BakedQuad.MaterialFlags
    public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        WallBushingBlockEntity.Facade facade = facade(level, pos);
        if (facade == null) {
            return super.materialFlags(level, pos, state);
        }
        return facade.target() == null ? blank.materialFlags() : model(facade.target()).materialFlags(level, pos, facade.target());
    }

    @Override
    public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        WallBushingBlockEntity.Facade facade = facade(level, pos);
        return facade != null && facade.target() != null ? model(facade.target()).particleMaterial(level, pos, facade.target())
                : super.particleMaterial(level, pos, state);
    }

    // The bushing's tint sources: tint layer i is the dressed block's layer i.
    public static List<BlockTintSource> tints(BlockColors colors) {
        List<BlockTintSource> sources = new ArrayList<>(TINTS);
        for (int layer = 0; layer < TINTS; layer++) {
            sources.add(new Tint(colors, layer));
        }
        return sources;
    }

    private record Tint(BlockColors colors, int layer) implements BlockTintSource {
        @Override
        public int color(BlockState state) {
            return -1;
        }

        @Override
        public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
            WallBushingBlockEntity.Facade facade = level.getModelData(pos).get(WallBushingBlockEntity.FACADE);
            BlockState target = facade != null ? facade.target() : null;
            BlockTintSource source = target != null ? colors.getTintSource(target, layer) : null;
            return source != null ? source.colorInWorld(target, level, pos) : -1;
        }
    }
}
