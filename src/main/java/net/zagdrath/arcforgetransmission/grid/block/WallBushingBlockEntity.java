/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.model.data.ModelData;
import net.neoforged.neoforge.model.data.ModelProperty;

// A Wall Bushing's Arcforge Conduit Facade, if it has one: blank, or dressed in a block whose look it copies. The model
// (WallBushingModel) reads it from the model data; it's saved and synced with the block.
public class WallBushingBlockEntity extends BlockEntity {
    // The facade as the model sees it: absent (no facade), or present with its target (null: a blank facade).
    public record Facade(@Nullable BlockState target) {}

    public static final ModelProperty<Facade> FACADE = new ModelProperty<>();

    private boolean facade;
    private @Nullable BlockState target;

    public WallBushingBlockEntity(BlockPos pos, BlockState state) {
        super(GridContent.WALL_BUSHING_ENTITY.get(), pos, state);
    }

    public boolean hasFacade() {
        return facade;
    }

    public @Nullable BlockState target() {
        return target;
    }

    void setFacade(boolean facade, @Nullable BlockState target) {
        this.facade = facade;
        this.target = facade ? target : null;
        setChanged();
        requestModelDataUpdate();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    public ModelData getModelData() {
        return facade ? ModelData.builder().with(FACADE, new Facade(target)).build() : ModelData.EMPTY;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("facade", Codec.BOOL, facade);
        if (target != null) {
            output.store("facade_target", BlockState.CODEC, target);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        boolean had = facade;
        BlockState was = target;
        facade = input.read("facade", Codec.BOOL).orElse(false);
        Optional<BlockState> read = input.read("facade_target", BlockState.CODEC);
        target = facade ? read.orElse(null) : null;
        if (level != null && level.isClientSide() && (had != facade || was != target)) {
            requestModelDataUpdate();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
