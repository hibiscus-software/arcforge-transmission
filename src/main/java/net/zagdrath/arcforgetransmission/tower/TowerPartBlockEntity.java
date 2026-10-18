/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import org.jspecify.annotations.Nullable;

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

// One of a transmission tower's parts: remembers where its main block is (synced, so the client knows its shape).
// Removed by anything but a player (an explosion, a command), it takes the whole tower with it and the item drops once.
public class TowerPartBlockEntity extends BlockEntity {
    private BlockPos mainOffset = BlockPos.ZERO;

    public TowerPartBlockEntity(BlockPos pos, BlockState state) {
        super(TowerContent.TOWER_PART_ENTITY.get(), pos, state);
    }

    public void setMain(BlockPos main) {
        mainOffset = main.subtract(worldPosition);
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public BlockPos mainPos() {
        return worldPosition.offset(mainOffset);
    }

    public @Nullable TowerBlockEntity main() {
        return level != null && level.getBlockEntity(mainPos()) instanceof TowerBlockEntity tower ? tower : null;
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        TowerBlockEntity tower = main();
        if (level != null && !level.isClientSide() && tower != null && !tower.isRemoving()
                && level.getBlockState(mainPos()).getBlock() instanceof TowerBlock) {
            level.destroyBlock(mainPos(), true);
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

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        mainOffset = input.read("main", BlockPos.CODEC).orElse(BlockPos.ZERO);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("main", BlockPos.CODEC, mainOffset);
    }
}
