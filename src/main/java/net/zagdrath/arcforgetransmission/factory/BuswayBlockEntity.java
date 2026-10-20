/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// A block of busway: one circuit (A, B and C) through it, joined to each piece it connects to (BusPieceBlockEntity) at its
// size. Burnt out (its joint over its rating too long), it's damaged: dull and smoking, joined to nothing.
public class BuswayBlockEntity extends BusPieceBlockEntity {
    public BuswayBlockEntity(BlockPos pos, BlockState state) {
        super(FactoryContent.BUSWAY_ENTITY.get(), pos, state);
    }

    private BuswayBlock block() {
        return (BuswayBlock) getBlockState().getBlock();
    }

    @Override
    public VoltageTier tier() {
        return block().tier();
    }

    @Override
    public int groupToward(Direction side) {
        return getBlockState().getValue(BuswayBlock.SIDES.get(side)) ? 0 : -1;
    }

    @Override
    public int sizeToward(Direction side) {
        return groupToward(side) >= 0 ? block().size() : 0;
    }

    @Override
    public boolean damaged() {
        return getBlockState().getValue(BuswayBlock.DAMAGED);
    }

    // Burnt out: dull and smoking, its joints gone (and its neighbours' toward it).
    public void damage(ServerLevel level) {
        if (damaged()) {
            return;
        }
        level.setBlock(worldPosition, getBlockState().setValue(BuswayBlock.DAMAGED, true), Block.UPDATE_ALL);
        rejoin(level);
        for (Direction side : Direction.values()) {
            BusPieceBlockEntity other = at(level, worldPosition.relative(side));
            if (other != null) {
                other.rejoin(level);
            }
        }
    }
}
