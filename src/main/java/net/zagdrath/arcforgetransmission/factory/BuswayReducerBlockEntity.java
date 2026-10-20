/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.OpState;

// A busway reducer: its larger end (group 0) joined to the larger run behind it, its smaller (group 1) to the smaller run
// ahead, through its breaker at the smaller run's rating.
public class BuswayReducerBlockEntity extends BusBreakerBlockEntity {
    public BuswayReducerBlockEntity(BlockPos pos, BlockState state) {
        super(FactoryContent.REDUCER_ENTITY.get(), pos, state);
        breaker.setState(OpState.CLOSED);
    }

    private BuswayReducerBlock block() {
        return (BuswayReducerBlock) getBlockState().getBlock();
    }

    @Override
    public VoltageTier tier() {
        return block().tier();
    }

    public Direction facing() {
        return getBlockState().getValue(BuswayReducerBlock.FACING);
    }

    @Override
    public int groupToward(Direction side) {
        return side == facing() ? 1 : side == facing().getOpposite() ? 0 : -1;
    }

    @Override
    public int sizeToward(Direction side) {
        return side == facing() ? block().small() : side == facing().getOpposite() ? block().big() : 0;
    }

    // Its breaker's frame: the smaller run's rating.
    @Override
    public double frame() {
        return GridConfig.SETTINGS.ratedAmps(tier()) * block().small();
    }
}
