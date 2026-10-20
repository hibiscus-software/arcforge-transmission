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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.OpState;

// A busway tap-off: its breaker's line side joined to the busway it clips onto, its load side to a Cable Termination
// mounted on it (TerminationHost), where a drop cable leaves for a machine. Its frame (1W to 8W at its tier: the Wrench on
// Configure) is graded with the feeder upstream.
public class BuswayTapOffBlockEntity extends BusBreakerBlockEntity implements TerminationHost {
    public BuswayTapOffBlockEntity(BlockPos pos, BlockState state) {
        super(FactoryContent.TAP_OFF_ENTITY.get(), pos, state);
    }

    @Override
    public VoltageTier tier() {
        return ((BuswayTapOffBlock) getBlockState().getBlock()).tier();
    }

    public Direction facing() {
        return getBlockState().getValue(BuswayTapOffBlock.FACING);
    }

    // No joints: it clips on.
    @Override
    public int groupToward(Direction side) {
        return -1;
    }

    @Override
    public int sizeToward(Direction side) {
        return 0;
    }

    @Override
    public double frame() {
        return LineupRatings.feederFrame(GridConfig.SETTINGS, tier(), breaker.frame());
    }

    // Its line side to the busway it clips onto, its load side to each termination on it.
    @Override
    public void rejoin(ServerLevel level) {
        super.rejoin(level);
        GridData.Dimension dimension = GridData.of(level);
        if (level.getBlockEntity(worldPosition.relative(facing())) instanceof BuswayBlockEntity run && !run.damaged()) {
            join(level, nodes(dimension, 0), run.nodes(dimension, 0));
        }
        for (Direction side : Direction.values()) {
            if (side != facing() && level.getBlockEntity(worldPosition.relative(side)) instanceof CableTerminationBlockEntity termination
                    && termination.mountPos().equals(worldPosition)) {
                join(level, nodes(dimension, 1), termination.nodes(dimension));
            }
        }
    }

    private static void join(ServerLevel level, List<Long> a, List<Long> b) {
        for (int k = 0; k < Math.min(a.size(), b.size()); k++) {
            GridManager.jumper(level, a.get(k), b.get(k));
        }
    }

    // Its flag shows its breaker's state.
    @Override
    protected void changed(ServerLevel level) {
        super.changed(level);
        BlockState state = getBlockState();
        level.setBlock(worldPosition, state.setValue(BuswayTapOffBlock.CLOSED, breaker.closed()).setValue(BuswayTapOffBlock.TRIPPED,
                breaker.state() == OpState.TRIPPED), Block.UPDATE_CLIENTS);
    }
}
