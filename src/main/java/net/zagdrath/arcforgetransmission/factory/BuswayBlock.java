/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.EnumMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;

// A block of busway: LV bus duct (plug-in openings every block), MV metal-enclosed bus (access covers every few blocks),
// HV gas-insulated bus and EHV gas-insulated line (three aluminium tubes, flanged, an SF6 gauge every few blocks, on
// floor stands or ceiling hangers). Placed like pipe: it joins busway of its tier and size on any side (straights,
// elbows, tees and crosses), a feed-in that faces it, or a reducer's matching end; a size change needs a reducer, and tiers
// never join. Its models and hitboxes come from tools/factory_block_model_gen.py; damaged (burnt out), it's dull and
// smokes, and joins nothing until it's replaced.
public class BuswayBlock extends BaseEntityBlock {
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH, EAST = BlockStateProperties.EAST, SOUTH = BlockStateProperties.SOUTH,
            WEST = BlockStateProperties.WEST, UP = BlockStateProperties.UP, DOWN = BlockStateProperties.DOWN;
    public static final Map<Direction, BooleanProperty> SIDES = new EnumMap<>(Map.of(Direction.NORTH, NORTH, Direction.EAST, EAST, Direction.SOUTH, SOUTH,
            Direction.WEST, WEST, Direction.UP, UP, Direction.DOWN, DOWN));
    public static final EnumProperty<Direction.Axis> VAXIS = EnumProperty.create("vaxis", Direction.Axis.class, Direction.Axis.X, Direction.Axis.Z);
    public static final BooleanProperty FEATURE = BooleanProperty.create("feature"), DAMAGED = BooleanProperty.create("damaged");
    public static final EnumProperty<Support> SUPPORT = EnumProperty.create("support", Support.class);

    public enum Support implements StringRepresentable {
        NONE, FLOOR, CEILING;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final VoltageTier tier;
    private final int size;

    public BuswayBlock(VoltageTier tier, int size, BlockBehaviour.Properties properties) {
        super(properties);
        this.tier = tier;
        this.size = size;
        BlockState state = stateDefinition.any().setValue(VAXIS, Direction.Axis.X).setValue(FEATURE, false).setValue(DAMAGED, false)
                .setValue(SUPPORT, Support.NONE);
        for (BooleanProperty side : SIDES.values()) {
            state = state.setValue(side, false);
        }
        registerDefaultState(state);
    }

    public VoltageTier tier() {
        return tier;
    }

    // Its size, as a multiple of its tier's wire: 2, 4 or 8.
    public int size() {
        return size;
    }

    public String id() {
        return tier.id() + "_busway_" + size + "w";
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN, VAXIS, FEATURE, DAMAGED, SUPPORT);
    }

    // Whether it joins what's on a side: busway of its tier and size, a feed-in of its tier facing it, a reducer of its
    // tier whose end of this size is toward it.
    public boolean joins(BlockGetter level, BlockPos pos, Direction side) {
        BlockState other = level.getBlockState(pos.relative(side));
        if (other.getBlock() instanceof BuswayBlock run) {
            return run.tier == tier && run.size == size;
        }
        if (other.getBlock() instanceof BuswayFeedInBlock feed) {
            return feed.tier() == tier && other.getValue(BuswayFeedInBlock.FACING) == side.getOpposite();
        }
        if (other.getBlock() instanceof BuswayReducerBlock reducer && reducer.tier() == tier) {
            Direction facing = other.getValue(BuswayReducerBlock.FACING);
            return size == reducer.big() && facing == side || size == reducer.small() && facing == side.getOpposite();
        }
        return false;
    }

    // Its state for its neighbours: its arms, a vertical run's width turned square to the arm it bends to, an access
    // cover or gauge every fourth block of a straight run, and what holds a horizontal straight up.
    public BlockState shaped(BlockGetter level, BlockPos pos, BlockState state) {
        for (Map.Entry<Direction, BooleanProperty> side : SIDES.entrySet()) {
            state = state.setValue(side.getValue(), joins(level, pos, side.getKey()));
        }
        boolean vertical = state.getValue(UP) || state.getValue(DOWN);
        if (vertical) {
            if (state.getValue(EAST) || state.getValue(WEST)) {
                state = state.setValue(VAXIS, Direction.Axis.Z);
            } else if (state.getValue(NORTH) || state.getValue(SOUTH)) {
                state = state.setValue(VAXIS, Direction.Axis.X);
            }
        }
        boolean straightX = state.getValue(EAST) || state.getValue(WEST), straightZ = state.getValue(NORTH) || state.getValue(SOUTH);
        boolean straight = !vertical && !(straightX && straightZ);
        int along = straightX ? pos.getX() : straightZ ? pos.getZ() : pos.getY();
        state = state.setValue(FEATURE, Math.floorMod(along, 4) == 2);
        Support support = Support.NONE;
        if (straight) {
            if (level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
                support = Support.FLOOR;
            } else if (level.getBlockState(pos.above()).isFaceSturdy(level, pos.above(), Direction.DOWN)) {
                support = Support.CEILING;
            }
        }
        return state.setValue(SUPPORT, support);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(VAXIS, context.getHorizontalDirection().getAxis() == Direction.Axis.X ? Direction.Axis.Z
                : Direction.Axis.X);
        return shaped(context.getLevel(), context.getClickedPos(), state);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
            BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        return shaped(level, pos, state);
    }

    // Its joints follow its shape.
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof BusPieceBlockEntity piece) {
            piece.neighbourChanged(server);
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return BlockShapes.of(id(), state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // Damaged, it smokes.
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(DAMAGED) && random.nextInt(3) == 0) {
            level.addParticle(ParticleTypes.SMOKE, pos.getX() + 0.3 + random.nextDouble() * 0.4, pos.getY() + 0.6, pos.getZ() + 0.3 + random.nextDouble() * 0.4,
                    0, 0.03, 0);
        }
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        GridManager.breaking(level, pos, player);
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BuswayBlockEntity(pos, state);
    }
}
