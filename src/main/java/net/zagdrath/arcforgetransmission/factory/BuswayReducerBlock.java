/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridInteractions;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;

// A busway reducer: where a run steps down a size (8W to 4W, 4W to 2W), its larger end behind it and its smaller toward
// FACING, carrying a breaker at the smaller size (closed as placed; an empty hand opens, closes or resets it). Placed out
// from the end of the larger run, facing away from it.
public class BuswayReducerBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    private final VoltageTier tier;
    private final int big, small;

    public BuswayReducerBlock(VoltageTier tier, int big, int small, BlockBehaviour.Properties properties) {
        super(properties);
        this.tier = tier;
        this.big = big;
        this.small = small;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public VoltageTier tier() {
        return tier;
    }

    public int big() {
        return big;
    }

    public int small() {
        return small;
    }

    public String id() {
        return tier.id() + "_busway_reducer_" + big + "w_" + small + "w";
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof BuswayReducerBlockEntity reducer) {
            reducer.neighbourChanged(server);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!GridInteractions.mayChange(player, level, pos)) {
            return InteractionResult.FAIL;
        }
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof BuswayReducerBlockEntity reducer) {
            player.sendOverlayMessage(reducer.operate(server));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return BlockShapes.of(id(), state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        GridManager.breaking(level, pos, player);
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BuswayReducerBlockEntity(pos, state);
    }
}
