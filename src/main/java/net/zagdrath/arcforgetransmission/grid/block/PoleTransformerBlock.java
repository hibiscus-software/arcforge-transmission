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
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.tower.TowerPlacement;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// The Pole-Mount Transformer: hung on the side of whatever it's put against (a pole), its front (FACING) away from it.
// Drawn by its block entity (PoleTransformerRenderer) from TowerMesh.poleTransformer(); it needs the block behind it.
public class PoleTransformerBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        // The can (its 8 facets' bounds) in the north-facing frame, turned to each facing.
        float r = (float) (4.0 / (2.0 * Math.sin(Math.PI / 8)));
        double x0 = TowerMesh.CAN_X - r, x1 = TowerMesh.CAN_X + r, z0 = TowerMesh.CAN_Z - r, z1 = Math.min(16.0, TowerMesh.CAN_Z + r);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            double ax = x0, az = z0, bx = x1, bz = z1;
            for (int t = 0; t < TowerPlacement.turns(facing); t++) {
                double nax = 16 - bz, nbx = 16 - az;
                az = ax;
                bz = bx;
                ax = nax;
                bx = nbx;
            }
            SHAPES.put(facing, Block.box(ax, TowerMesh.CAN_Y0, az, bx, TowerMesh.CAN_Y1, bz));
        }
    }

    // Its primary's tier: LV (the Pole-Mount Transformer) or MV (the MV Distribution Transformer); the secondary is LV.
    private final VoltageTier primary;

    public PoleTransformerBlock(VoltageTier primary, BlockBehaviour.Properties properties) {
        super(properties);
        this.primary = primary;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public VoltageTier primary() {
        return primary;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    // Against the side clicked, facing out from it; clicked on a top or bottom, facing the player.
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        Direction facing = face.getAxis().isHorizontal() ? face : context.getHorizontalDirection().getOpposite();
        BlockState state = defaultBlockState().setValue(FACING, facing);
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

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    // A comparator reads the can's load.
    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof PoleTransformerBlockEntity can ? can.comparator() : 0;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof PoleTransformerBlockEntity transformer) {
            InteractionResult result = GridInteractions.wrench(stack, level, transformer, player, hit.getLocation(), true);
            if (result != null) {
                return result;
            }
        }
        // Anything else in hand (a wire coil, a block to place against it) is the item's to use.
        return stack.isEmpty() ? InteractionResult.TRY_WITH_EMPTY_HAND : InteractionResult.PASS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof PoleTransformerBlockEntity transformer) {
            String m = ArcforgeTransmission.MODID;
            player.sendOverlayMessage(Component.translatable("message." + m + ".transformer.status",
                    GridInteractions.labels(server, transformer, 0), GridInteractions.labels(server, transformer, 1),
                    GridInteractions.format(Math.round(transformer.lastFlow()))));
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
        return new PoleTransformerBlockEntity(pos, state);
    }
}
