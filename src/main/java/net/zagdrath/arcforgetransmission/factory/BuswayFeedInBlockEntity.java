/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;

// A busway feed-in: one circuit, joined to the busway on its facing side at that run's size, and to what it sits on: a
// lineup feeder joins it from below (Hosted), and on a Cable Termination it joins the termination's conductors itself.
public class BuswayFeedInBlockEntity extends BusPieceBlockEntity implements Hosted {
    public BuswayFeedInBlockEntity(BlockPos pos, BlockState state) {
        super(FactoryContent.FEED_IN_ENTITY.get(), pos, state);
    }

    @Override
    public VoltageTier tier() {
        return ((BuswayFeedInBlock) getBlockState().getBlock()).tier();
    }

    public Direction facing() {
        return getBlockState().getValue(BuswayFeedInBlock.FACING);
    }

    @Override
    public int groupToward(Direction side) {
        return side == facing() ? 0 : -1;
    }

    // The size of the run it feeds (its joint's).
    @Override
    public int sizeToward(Direction side) {
        if (side != facing() || level == null) {
            return 0;
        }
        return level.getBlockState(worldPosition.relative(side)).getBlock() instanceof BuswayBlock run ? run.size() : 0;
    }

    @Override
    public BlockPos mountPos() {
        return worldPosition.below();
    }

    @Override
    public List<Long> nodes(GridData.Dimension dimension) {
        return nodes(dimension, 0);
    }

    // Also joins a Cable Termination it stands on (its top block, an HV or EHV termination's part included).
    @Override
    public void rejoin(ServerLevel level) {
        super.rejoin(level);
        BlockPos below = mountPos();
        BlockPos termination = level.getBlockState(below).getBlock() instanceof CableTerminationPartBlock
                ? CableTerminationPartBlock.mainOf(below, level.getBlockState(below)) : below;
        if (level.getBlockEntity(termination) instanceof CableTerminationBlockEntity cable) {
            GridData.Dimension dimension = GridData.of(level);
            List<Long> a = nodes(dimension, 0), b = cable.nodes(dimension);
            for (int k = 0; k < Math.min(a.size(), b.size()); k++) {
                GridManager.jumper(level, a.get(k), b.get(k));
            }
        }
    }
}
