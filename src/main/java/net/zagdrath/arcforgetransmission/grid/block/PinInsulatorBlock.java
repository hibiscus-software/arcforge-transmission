/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import java.util.EnumMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// A Pin Insulator of one tier: one pole-style insulator mounted on any face of a block (FACING is the way it points, away
// from that block). It needs its block: when the block goes, it drops. See PinInsulatorBlockEntity.
public class PinInsulatorBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    private final VoltageTier tier;
    private final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);

    public PinInsulatorBlock(VoltageTier tier, BlockBehaviour.Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
        double half = switch (tier) {
            case LV -> 3.5;
            case MV -> 3.75;
            case HV -> 4.0;
            case EHV -> 5.0;
        };
        double height = PinInsulatorBlockEntity.height(tier);
        for (Direction facing : Direction.values()) {
            shapes.put(facing, standing(facing, half, height));
        }
    }

    public VoltageTier tier() {
        return tier;
    }

    // A box `half` px each way across, standing `height` px off the face opposite `facing`.
    static VoxelShape standing(Direction facing, double half, double height) {
        double lo = 8 - half, hi = 8 + half;
        return switch (facing) {
            case UP -> Block.box(lo, 0, lo, hi, height, hi);
            case DOWN -> Block.box(lo, 16 - height, lo, hi, 16, hi);
            case NORTH -> Block.box(lo, lo, 16 - height, hi, hi, 16);
            case SOUTH -> Block.box(lo, lo, 0, hi, hi, height);
            case WEST -> Block.box(16 - height, lo, lo, 16, hi, hi);
            case EAST -> Block.box(0, lo, lo, height, hi, hi);
        };
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    // Pointing away from the face it's put on.
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(FACING, context.getClickedFace());
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
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
        return level.getBlockEntity(pos) instanceof PinInsulatorBlockEntity port ? port.loadSignal() : 0;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state.getValue(FACING));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof PinInsulatorBlockEntity pinInsulator) {
            InteractionResult result = GridInteractions.wrench(stack, level, pinInsulator, player, hit.getLocation(), true);
            if (result != null) {
                return result;
            }
        }
        // Anything else in hand (a wire coil, a block to place against it) is the item's to use.
        return stack.isEmpty() ? InteractionResult.TRY_WITH_EMPTY_HAND : InteractionResult.PASS;
    }

    // Empty-handed: what it's doing.
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof PinInsulatorBlockEntity pinInsulator) {
            String m = ArcforgeTransmission.MODID;
            player.sendOverlayMessage(Component.translatable("message." + m + ".pin_insulator.status",
                    GridInteractions.labels(server, pinInsulator, 0),
                    Component.translatable("pin_insulator_mode." + m + "." + pinInsulator.mode().id()),
                    GridInteractions.format(pinInsulator.limitAmps()),
                    Component.translatable("port_status." + m + "." + pinInsulator.status().name().toLowerCase(java.util.Locale.ROOT)),
                    GridInteractions.format(Math.round(pinInsulator.lastFlow()))));
        }
        return InteractionResult.SUCCESS;
    }

    // Broken by a creative player: its wires' coils don't drop.
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        GridManager.breaking(level, pos, player);
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PinInsulatorBlockEntity(pos, state);
    }
}
