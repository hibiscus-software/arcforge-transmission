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
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;

// One of a lineup section's invisible blocks round its main block: its shape the piece of the section's model inside it,
// as hard to break as the section, and breaking it breaks the section (which drops once). It works the section's
// handles and Wrench as its main block does, hears redstone for the breaker in it, and gives a comparator its load.
public class SectionPartBlock extends BaseEntityBlock {
    public SectionPartBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SectionPartBlockEntity(pos, state);
    }

    public static @Nullable BlockPos mainOf(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof SectionPartBlockEntity part ? part.mainPos() : null;
    }

    private static @Nullable BlockState mainState(BlockGetter level, BlockPos pos) {
        BlockPos main = mainOf(level, pos);
        if (main == null) {
            return null;
        }
        BlockState state = level.getBlockState(main);
        return state.getBlock() instanceof SectionBlock ? state : null;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        BlockPos main = mainOf(level, pos);
        BlockState section = mainState(level, pos);
        if (main == null || section == null) {
            return Shapes.block();
        }
        SectionBlock block = (SectionBlock) section.getBlock();
        Direction facing = section.getValue(SectionBlock.FACING);
        int[] cell = SectionGeometry.cellOf(block.section(), block.tier().ordinal(), main, facing, pos);
        return cell == null ? Shapes.block() : SectionGeometry.shape(block.section(), block.tier().ordinal(), facing, cell);
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
        BlockPos main = mainOf(level, pos);
        BlockState section = mainState(level, pos);
        return main != null && section != null ? section.getDestroyProgress(player, level, main) : super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        BlockPos main = mainOf(level, pos);
        BlockState section = mainState(level, pos);
        if (main != null && section != null) {
            GridManager.breaking(level, main, player);
            level.destroyBlock(main, !player.isCreative() && (!section.requiresCorrectToolForDrops() || player.hasCorrectToolForDrops(section)), player);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        BlockState section = mainState(level, pos);
        return section != null ? new ItemStack(section.getBlock()) : ItemStack.EMPTY;
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    // A part whose section is gone removes itself.
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (mainState(level, pos) == null) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        BlockPos main = mainOf(level, pos);
        if (level instanceof ServerLevel server && main != null && level.getBlockEntity(main) instanceof SectionBlockEntity section) {
            section.neighbourChanged(server);
        }
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        BlockPos main = mainOf(level, pos);
        return main != null && level.getBlockEntity(main) instanceof SectionBlockEntity section ? section.comparator(pos) : 0;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        BlockPos main = mainOf(level, pos);
        return main == null ? InteractionResult.PASS : SectionBlock.use(stack, level, main, player, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockPos main = mainOf(level, pos);
        return main == null ? InteractionResult.PASS : SectionBlock.use(ItemStack.EMPTY, level, main, player, hit);
    }
}
