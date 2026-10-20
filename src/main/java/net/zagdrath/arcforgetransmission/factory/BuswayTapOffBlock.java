/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.arcforge.item.tool.WrenchItem;
import net.zagdrath.arcforge.item.tool.WrenchMode;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridInteractions;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;

// A busway tap-off, clipped onto a straight run of busway on its FACING side: LV a plug-in tap box, MV a bolted one (only at
// an access cover), HV and EHV a small GIS tee module with its breaker and earthing switch. Its flag shows its breaker
// (red closed, green open, yellow tripped); an empty hand works it, the Wrench on Configure steps its frame. See
// BuswayTapOffBlockEntity.
public class BuswayTapOffBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final BooleanProperty CLOSED = BooleanProperty.create("closed"), TRIPPED = BooleanProperty.create("tripped");

    private final VoltageTier tier;

    public BuswayTapOffBlock(VoltageTier tier, BlockBehaviour.Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(CLOSED, false).setValue(TRIPPED, false));
    }

    public VoltageTier tier() {
        return tier;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CLOSED, TRIPPED);
    }

    // Clipped onto the busway clicked on: a straight run of its tier (at MV, an access cover).
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction toward = context.getClickedFace().getOpposite();
        BlockState state = defaultBlockState().setValue(FACING, toward);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction toward = state.getValue(FACING);
        BlockState run = level.getBlockState(pos.relative(toward));
        if (!(run.getBlock() instanceof BuswayBlock busway) || busway.tier() != tier) {
            return false;
        }
        // On a straight's side (not along its axis, and not on an elbow or tee).
        int arms = 0;
        Direction.Axis axis = null;
        for (var side : BuswayBlock.SIDES.entrySet()) {
            if (run.getValue(side.getValue())) {
                arms++;
                axis = side.getKey().getAxis();
            }
        }
        boolean straight = arms <= 2 && (arms == 0 || axis != toward.getAxis());
        return straight && (tier != VoltageTier.MV || run.getValue(BuswayBlock.FEATURE));
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
            BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        return direction == state.getValue(FACING) && !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof BuswayTapOffBlockEntity tap) {
            tap.neighbourChanged(server);
        }
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (GridInteractions.isWrench(stack) && WrenchItem.mode(stack) == WrenchMode.CONFIGURE) {
            if (!GridInteractions.mayChange(player, level, pos)) {
                return InteractionResult.FAIL;
            }
            if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof BuswayTapOffBlockEntity tap) {
                tap.breaker().setFrame(tap.breaker().frame() + 1);
                tap.setChanged();
                player.sendOverlayMessage(Component.translatable("message." + ArcforgeTransmission.MODID + ".lineup.frame", 1,
                        GridInteractions.format(tap.frame())));
            }
            return InteractionResult.SUCCESS;
        }
        return stack.isEmpty() ? InteractionResult.TRY_WITH_EMPTY_HAND : InteractionResult.PASS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!GridInteractions.mayChange(player, level, pos)) {
            return InteractionResult.FAIL;
        }
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof BuswayTapOffBlockEntity tap) {
            player.sendOverlayMessage(tap.operate(server));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return BlockShapes.of(tier.id() + "_busway_tap_off", state);
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
        return new BuswayTapOffBlockEntity(pos, state);
    }
}
