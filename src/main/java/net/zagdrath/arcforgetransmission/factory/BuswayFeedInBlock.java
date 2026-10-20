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
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
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
    // Facing up: the way the busway above it runs (its riser's tubes or duct turned to meet it).
    public static final EnumProperty<Direction.Axis> AXIS = EnumProperty.create("axis", Direction.Axis.class, Direction.Axis.X, Direction.Axis.Z);

    private final VoltageTier tier;

    public BuswayFeedInBlock(VoltageTier tier, BlockBehaviour.Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP).setValue(AXIS, Direction.Axis.X));
    }

    public VoltageTier tier() {
        return tier;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, AXIS);
    }

    // Leaving toward a busway of its tier beside it or above (the side clicked from first); with none, upward, or toward
    // the side clicked from (placed against a run's end).
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        Direction way = face == Direction.DOWN || face == Direction.UP ? Direction.UP : face.getOpposite();
        if (!busway(context.getLevel().getBlockState(context.getClickedPos().relative(way)))) {
            for (Direction side : FACING.getPossibleValues()) {
                if (busway(context.getLevel().getBlockState(context.getClickedPos().relative(side)))) {
                    way = side;
                    break;
                }
            }
        }
        BlockState state = defaultBlockState().setValue(FACING, way);
        return way == Direction.UP ? aimed(state, context.getLevel().getBlockState(context.getClickedPos().above())) : state;
    }

    // Facing up, turned to the way the busway above runs (its arms east and west, or north and south).
    private BlockState aimed(BlockState state, BlockState above) {
        if (!busway(above)) {
            return state;
        }
        boolean x = above.getValue(BuswayBlock.SIDES.get(Direction.EAST)) || above.getValue(BuswayBlock.SIDES.get(Direction.WEST));
        boolean z = above.getValue(BuswayBlock.SIDES.get(Direction.NORTH)) || above.getValue(BuswayBlock.SIDES.get(Direction.SOUTH));
        return x == z ? state : state.setValue(AXIS, x ? Direction.Axis.X : Direction.Axis.Z);
    }

    private boolean busway(BlockState state) {
        return state.getBlock() instanceof BuswayBlock busway && busway.tier() == tier;
    }

    // Busway of its tier laid beside it (or above) while none is where it faces: it turns to it.
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
            BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        if (direction != Direction.DOWN && direction != state.getValue(FACING) && busway(neighborState)
                && !busway(level.getBlockState(pos.relative(state.getValue(FACING))))) {
            return direction == Direction.UP ? aimed(state.setValue(FACING, direction), neighborState) : state.setValue(FACING, direction);
        }
        if (direction == Direction.UP && state.getValue(FACING) == Direction.UP) {
            return aimed(state, neighborState);
        }
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random);
    }

    // Turned (by the Wrench, or toward busway laid beside it): it and the busway now before it join again.
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean movedByPiston) {
        super.onPlace(state, level, pos, old, movedByPiston);
        if (level instanceof ServerLevel server && old.is(this) && old.getValue(FACING) != state.getValue(FACING)) {
            if (level.getBlockEntity(pos) instanceof BuswayFeedInBlockEntity feed) {
                feed.rejoin(server);
            }
            for (Direction way : new Direction[] { old.getValue(FACING), state.getValue(FACING) }) {
                if (level.getBlockEntity(pos.relative(way)) instanceof BusPieceBlockEntity piece) {
                    piece.rejoin(server);
                }
            }
        }
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
