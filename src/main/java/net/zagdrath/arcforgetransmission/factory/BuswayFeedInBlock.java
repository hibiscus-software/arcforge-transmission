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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.arcforge.item.tool.WrenchItem;
import net.zagdrath.arcforge.item.tool.WrenchMode;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridInteractions;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;

// A busway feed-in: where a feeder's circuit enters busway, sitting on a lineup feeder's roof or on a Cable Termination
// (its circuit joined to theirs), the busway leaving it on FACING (up, or a side: the Wrench on Rotate steps it).
public class BuswayFeedInBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = EnumProperty.create("facing", Direction.class, Direction.NORTH, Direction.EAST, Direction.SOUTH,
            Direction.WEST, Direction.UP);

    private final VoltageTier tier;

    public BuswayFeedInBlock(VoltageTier tier, BlockBehaviour.Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
    }

    public VoltageTier tier() {
        return tier;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    // Leaving upward, or toward a side clicked from (placed against a busway run's end).
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        return defaultBlockState().setValue(FACING, face == Direction.DOWN || face == Direction.UP ? Direction.UP : face.getOpposite());
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof BuswayFeedInBlockEntity feed) {
            feed.neighbourChanged(server);
        }
    }

    // The Wrench on Rotate steps the way its busway leaves (up, north, east, south, west).
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (GridInteractions.isWrench(stack) && WrenchItem.mode(stack) == WrenchMode.ROTATE) {
            if (!GridInteractions.mayChange(player, level, pos)) {
                return InteractionResult.FAIL;
            }
            Direction[] order = { Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };
            int at = java.util.Arrays.asList(order).indexOf(state.getValue(FACING));
            level.setBlock(pos, state.setValue(FACING, order[(at + 1) % order.length]), Block.UPDATE_ALL);
            return InteractionResult.SUCCESS;
        }
        return stack.isEmpty() ? InteractionResult.TRY_WITH_EMPTY_HAND : InteractionResult.PASS;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return BlockShapes.of(tier.id() + "_busway_feed_in", state);
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
        return new BuswayFeedInBlockEntity(pos, state);
    }
}
