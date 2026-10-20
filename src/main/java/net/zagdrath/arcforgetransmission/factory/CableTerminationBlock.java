/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridInteractions;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.tower.TowerPlacement;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh;

// A Cable Termination of one tier: a whole circuit's conductors (A, B and C) where a tray cable ends, mounted on any face
// of the block it serves (FACING points away from that block) as a Pin Insulator is. On top of a block it stands on it;
// on a block's side it stands upright on a wall bracket beside it; under one it hangs. LV and MV fit their block; an HV
// termination stands two blocks tall and an EHV one three, the cells above it (below, hanging) its parts
// (CableTerminationPartBlock). See CableTerminationBlockEntity.
public class CableTerminationBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    private final VoltageTier tier;
    // Each facing's shape, by the cell (0 its own, 1 and 2 its parts').
    private final Map<Direction, Map<Integer, VoxelShape>> shapes = new EnumMap<>(Direction.class);

    public CableTerminationBlock(VoltageTier tier, BlockBehaviour.Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
    }

    public VoltageTier tier() {
        return tier;
    }

    // How it's mounted, pointing that way.
    public static FactoryMesh.Mount mount(Direction facing) {
        return facing == Direction.UP ? FactoryMesh.Mount.TOP : facing == Direction.DOWN ? FactoryMesh.Mount.BOTTOM : FactoryMesh.Mount.SIDE;
    }

    // The quarter turns its model takes (a wall bracket's back to the wall).
    public static int turns(Direction facing) {
        return facing.getAxis().isHorizontal() ? TowerPlacement.turns(facing) : 0;
    }

    // How many blocks it takes up, and which way its parts go from it (up, or down when hanging).
    public int height() {
        return FactoryMesh.terminationHeight(tier.ordinal());
    }

    public static Direction partsWay(Direction facing) {
        return facing == Direction.DOWN ? Direction.DOWN : Direction.UP;
    }

    // Its shape in one of its cells (step blocks from it, the way its parts go).
    public VoxelShape shape(Direction facing, int step) {
        return shapes.computeIfAbsent(facing, f -> new HashMap<>()).computeIfAbsent(step, s -> {
            int dy = partsWay(facing) == Direction.UP ? s : -s;
            VoxelShape shape = FactoryShapes.cell(FactoryMesh.termination(tier.ordinal(), mount(facing)).shapes(), turns(facing), 0, dy, 0);
            return shape.isEmpty() ? Block.box(7, 7, 7, 9, 9, 9) : shape;
        });
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    // Pointing away from the face it's put on, with room for its parts.
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace();
        BlockState state = defaultBlockState().setValue(FACING, facing);
        if (!state.canSurvive(context.getLevel(), context.getClickedPos())) {
            return null;
        }
        for (int step = 1; step < height(); step++) {
            BlockPos at = context.getClickedPos().relative(partsWay(facing), step);
            if (!context.getLevel().getBlockState(at).canBeReplaced(context)) {
                return null;
            }
        }
        return state;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        Direction way = partsWay(state.getValue(FACING));
        for (int step = 1; step < height(); step++) {
            level.setBlock(pos.relative(way, step), FactoryContent.TERMINATION_PART.get().defaultBlockState()
                    .setValue(CableTerminationPartBlock.STEP, step).setValue(CableTerminationPartBlock.HANGING, way == Direction.DOWN), Block.UPDATE_ALL);
        }
    }

    // Its parts go with it.
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        Direction way = partsWay(state.getValue(FACING));
        for (int step = 1; step < height(); step++) {
            BlockPos at = pos.relative(way, step);
            if (level.getBlockState(at).getBlock() instanceof CableTerminationPartBlock) {
                level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return !level.getBlockState(pos.relative(state.getValue(FACING).getOpposite())).isAir();
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
            BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        return direction == state.getValue(FACING).getOpposite() && !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    // A comparator reads the machine it feeds: its draw as a share of its rating (Loads).
    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof CableTerminationBlockEntity port ? port.loadSignal() : 0;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape(state.getValue(FACING), 0);
    }

    // Drawn by its block entity (CableTerminationRenderer).
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof CableTerminationBlockEntity termination) {
            InteractionResult result = GridInteractions.wrench(stack, level, termination, player, hit.getLocation(), true);
            if (result != null) {
                return result;
            }
        }
        return stack.isEmpty() ? InteractionResult.TRY_WITH_EMPTY_HAND : InteractionResult.PASS;
    }

    // Empty-handed: what it's doing.
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof CableTerminationBlockEntity termination) {
            player.sendOverlayMessage(termination.statusLine(server));
        }
        return InteractionResult.SUCCESS;
    }

    // Broken by a creative player: its cables don't drop.
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        GridManager.breaking(level, pos, player);
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CableTerminationBlockEntity(pos, state);
    }

    // Its name as a status line names it.
    static Component name(VoltageTier tier) {
        return Component.translatable("block." + ArcforgeTransmission.MODID + "." + tier.id() + "_cable_termination");
    }
}
