/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;

// Galvanized ladder cable tray (standard, wide, or heavy-duty with deeper rails for HV and EHV): placed like pipe, joining
// tray of its kind into straights, elbows, risers, tees and crosses, hung from the ceiling, bracketed off a wall or
// standing on the floor by what's next to it. Tray cables lie in it (CableNetwork); broken, the cables in it come up and
// drop. Its models and hitboxes come from tools/factory_block_model_gen.py.
public class CableTrayBlock extends BaseEntityBlock {
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH, EAST = BlockStateProperties.EAST, SOUTH = BlockStateProperties.SOUTH,
            WEST = BlockStateProperties.WEST, UP = BlockStateProperties.UP, DOWN = BlockStateProperties.DOWN;
    public static final Map<Direction, BooleanProperty> SIDES = new EnumMap<>(Map.of(Direction.NORTH, NORTH, Direction.EAST, EAST, Direction.SOUTH, SOUTH,
            Direction.WEST, WEST, Direction.UP, UP, Direction.DOWN, DOWN));
    public static final EnumProperty<Mount> MOUNT = EnumProperty.create("mount", Mount.class);

    public enum Kind {
        STANDARD("cable_tray"), WIDE("wide_cable_tray"), HEAVY("heavy_duty_cable_tray");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public enum Mount implements StringRepresentable {
        NONE, CEILING, FLOOR, NORTH, EAST, SOUTH, WEST;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final Kind kind;

    public CableTrayBlock(Kind kind, BlockBehaviour.Properties properties) {
        super(properties);
        this.kind = kind;
        BlockState state = stateDefinition.any().setValue(MOUNT, Mount.NONE);
        for (BooleanProperty side : SIDES.values()) {
            state = state.setValue(side, false);
        }
        registerDefaultState(state);
    }

    public Kind kind() {
        return kind;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN, MOUNT);
    }

    // The sides a tray block joins on.
    public static List<Direction> joined(BlockState state) {
        List<Direction> out = new ArrayList<>();
        if (state.getBlock() instanceof CableTrayBlock) {
            for (Map.Entry<Direction, BooleanProperty> side : SIDES.entrySet()) {
                if (state.getValue(side.getValue())) {
                    out.add(side.getKey());
                }
            }
        }
        return out;
    }

    // Its state for its neighbours: an arm to tray of its kind on each side, and what holds it up: a hanger from a
    // ceiling, else a bracket off a wall, else a stand on the floor (never toward a side it joins).
    public BlockState shaped(BlockGetter level, BlockPos pos, BlockState state) {
        for (Map.Entry<Direction, BooleanProperty> side : SIDES.entrySet()) {
            BlockState other = level.getBlockState(pos.relative(side.getKey()));
            state = state.setValue(side.getValue(), other.getBlock() instanceof CableTrayBlock tray && tray.kind == kind);
        }
        Mount mount = Mount.NONE;
        if (!state.getValue(UP) && sturdy(level, pos, Direction.UP)) {
            mount = Mount.CEILING;
        } else {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if (!state.getValue(SIDES.get(side)) && sturdy(level, pos, side)) {
                    mount = Mount.valueOf(side.name());
                    break;
                }
            }
            if (mount == Mount.NONE && !state.getValue(DOWN) && sturdy(level, pos, Direction.DOWN)) {
                mount = Mount.FLOOR;
            }
        }
        return state.setValue(MOUNT, mount);
    }

    private static boolean sturdy(BlockGetter level, BlockPos pos, Direction side) {
        BlockPos at = pos.relative(side);
        BlockState state = level.getBlockState(at);
        return !(state.getBlock() instanceof CableTrayBlock) && state.isFaceSturdy(level, at, side.getOpposite());
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return shaped(context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
            BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        return shaped(level, pos, state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return BlockShapes.of(kind.id(), state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // Broken by a creative player: its cables don't drop.
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        GridManager.breaking(level, pos, player);
        return super.playerWillDestroy(level, pos, state, player);
    }

    // The cables in it come up, dropping here.
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        CableNetwork.trayGone(level, pos);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CableTrayBlockEntity(pos, state);
    }
}
