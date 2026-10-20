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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

// The cells an HV or EHV Cable Termination stands up into (or hangs down into): invisible, shaped as the piece of the
// termination inside them, as hard to break as it, and breaking one breaks the termination. STEP is how many blocks from
// the termination it is; HANGING, that the termination is above it.
public class CableTerminationPartBlock extends Block {
    public static final IntegerProperty STEP = IntegerProperty.create("step", 1, 2);
    public static final BooleanProperty HANGING = BooleanProperty.create("hanging");

    public CableTerminationPartBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(STEP, 1).setValue(HANGING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(STEP, HANGING);
    }

    // The termination it belongs to.
    public static BlockPos mainOf(BlockPos pos, BlockState state) {
        return pos.relative(state.getValue(HANGING) ? Direction.UP : Direction.DOWN, state.getValue(STEP));
    }

    private static @Nullable BlockState main(BlockGetter level, BlockPos pos, BlockState state) {
        BlockState main = level.getBlockState(mainOf(pos, state));
        return main.getBlock() instanceof CableTerminationBlock ? main : null;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        BlockState main = main(level, pos, state);
        if (main == null) {
            return Shapes.block();
        }
        return ((CableTerminationBlock) main.getBlock()).shape(main, state.getValue(STEP));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state) {
        return true;
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        BlockState main = main(level, pos, state);
        return main != null ? main.getDestroyProgress(player, level, mainOf(pos, state)) : super.getDestroyProgress(state, player, level, pos);
    }

    // Breaking it breaks the termination (which drops itself).
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        BlockPos main = mainOf(pos, state);
        if (main(level, pos, state) != null) {
            level.destroyBlock(main, !player.isCreative(), player);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    // However it goes (an explosion, a piston), the termination goes with it, dropping itself.
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        if (main(level, pos, state) != null) {
            level.destroyBlock(mainOf(pos, state), true);
        }
    }

    // Gone with its termination.
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
            BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        return main(level, pos, state) == null ? Blocks.AIR.defaultBlockState() : state;
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        BlockState main = main(level, pos, state);
        return main != null ? new ItemStack(main.getBlock()) : ItemStack.EMPTY;
    }

    // The Wrench and an empty hand work it as its termination.
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        BlockPos main = mainOf(pos, state);
        BlockState mainState = main(level, pos, state);
        return mainState == null ? InteractionResult.PASS
                : mainState.useItemOn(stack, level, player, hand, hit.withPosition(main));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockPos main = mainOf(pos, state);
        BlockState mainState = main(level, pos, state);
        return mainState == null ? InteractionResult.PASS : mainState.useWithoutItem(level, player, hit.withPosition(main));
    }

    // Drawn by its block entity: it dims no light and casts no ambient-occlusion shade on its neighbours, however much of
    // its block its shape fills.
    @Override
    protected int getLightDampening(BlockState state) {
        return 0;
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }
}
