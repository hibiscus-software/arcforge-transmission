/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

// A transmission tower's main block entity: what TowerRenderer draws the tower from, and what clears its parts away when
// it goes. It holds no data: the tier is its block, the facing its block state.
public class TowerBlockEntity extends BlockEntity {
    private boolean removing;
    // The box the tower is drawn in, worked out once (its tier and facing never change).
    private @Nullable AABB renderBox;

    public TowerBlockEntity(BlockPos pos, BlockState state) {
        super(TowerContent.TOWER_ENTITY.get(), pos, state);
    }

    public TowerTier tier() {
        return getBlockState().getBlock() instanceof TowerBlock tower ? tower.tier() : TowerTier.LV;
    }

    public Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(TowerBlock.FACING) ? state.getValue(TowerBlock.FACING) : Direction.NORTH;
    }

    public boolean isRemoving() {
        return removing;
    }

    public AABB getRenderBox() {
        if (renderBox == null) {
            renderBox = TowerPlacement.renderBox(tier(), facing(), worldPosition);
        }
        return renderBox;
    }

    // The whole tower goes with its main block; its parts drop nothing.
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        removing = true;
        super.preRemoveSideEffects(pos, state);
        if (level == null || level.isClientSide() || !(state.getBlock() instanceof TowerBlock tower)) {
            return;
        }
        Direction facing = state.getValue(TowerBlock.FACING);
        for (BlockPos part : TowerPlacement.positions(tower.tier(), facing, TowerPlacement.centre(tower.tier(), facing, pos))) {
            if (!part.equals(pos) && level.getBlockState(part).getBlock() instanceof TowerPartBlock
                    && pos.equals(TowerPartBlock.mainOf(level, part))) {
                level.setBlock(part, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }
}
