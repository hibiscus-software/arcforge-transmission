/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

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

// One of a lineup section's other blocks: remembers where its main block is (synced, so the client knows its shape).
// Removed by anything but a player (an explosion, a command), it takes the whole section, which drops once.
public class SectionPartBlockEntity extends BlockEntity implements TerminationHost {
    private BlockPos mainOffset = BlockPos.ZERO;

    public SectionPartBlockEntity(BlockPos pos, BlockState state) {
        super(FactoryContent.SECTION_PART_ENTITY.get(), pos, state);
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

    public @Nullable SectionBlockEntity main() {
        return level != null && level.getBlockEntity(mainPos()) instanceof SectionBlockEntity section ? section : null;
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null && !level.isClientSide() && main() != null && level.getBlockState(mainPos()).getBlock() instanceof SectionBlock) {
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
