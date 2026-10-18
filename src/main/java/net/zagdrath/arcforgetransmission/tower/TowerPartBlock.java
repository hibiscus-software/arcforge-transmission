/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
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
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

// One of a transmission tower's invisible blocks round its main block (like Arcforge's Arc Quarry parts). Its shape is the piece of
// the tower's model inside it, so the outline, hits and collision follow the poles, arms and members and leave the gaps
// open. It's as hard to break as the tower, and breaking it breaks the whole tower (the item drops once, from the main
// block). It never drops anything itself, and removes itself if its main block has gone.
public class TowerPartBlock extends BaseEntityBlock {
    // Whether it's part of the steel lattice (its breaking particles are steel's) or a wooden tower.
    public static final BooleanProperty STEEL = BooleanProperty.create("steel");

    public TowerPartBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(STEEL, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(STEEL);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TowerPartBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    // The main block this part belongs to, or null.
    public static @Nullable BlockPos mainOf(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof TowerPartBlockEntity part ? part.mainPos() : null;
    }

    // The tower's main block state, or null when it's gone.
    private static @Nullable BlockState mainState(BlockGetter level, BlockPos pos) {
        BlockPos main = mainOf(level, pos);
        if (main == null) {
            return null;
        }
        BlockState state = level.getBlockState(main);
        return state.getBlock() instanceof TowerBlock ? state : null;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        BlockPos main = mainOf(level, pos);
        BlockState tower = mainState(level, pos);
        if (main == null || tower == null) {
            return Shapes.block();
        }
        TowerBlock block = (TowerBlock) tower.getBlock();
        Direction facing = tower.getValue(TowerBlock.FACING);
        BlockPos centre = TowerPlacement.centre(block.tier(), facing, main);
        VoxelShape shape = TowerPlacement.shape(block.tier(), facing, pos.subtract(centre));
        // Never nothing at all: a part that only grazes the model still has something to aim at.
        return shape.isEmpty() ? Block.box(7.0, 7.0, 7.0, 9.0, 9.0, 9.0) : shape;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return mainState(level, pos) == null ? Shapes.block() : getShape(state, level, pos, context);
    }

    // As hard to break as the tower.
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        BlockPos main = mainOf(level, pos);
        BlockState tower = mainState(level, pos);
        return main != null && tower != null ? tower.getDestroyProgress(player, level, main) : super.getDestroyProgress(state, player, level, pos);
    }

    // A player breaking a part breaks the tower: its item drops once from the main block (in survival, and for the
    // lattice only with a pickaxe that can take it, as from its main block).
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        BlockPos main = mainOf(level, pos);
        BlockState tower = mainState(level, pos);
        if (main != null && tower != null) {
            TowerBlock.breakTower(level, main,
                    !player.isCreative() && (!tower.requiresCorrectToolForDrops() || player.hasCorrectToolForDrops(tower)));
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        BlockState tower = mainState(level, pos);
        return tower != null ? new ItemStack(tower.getBlock()) : ItemStack.EMPTY;
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    // A part whose main block is gone removes itself.
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (mainState(level, pos) == null) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
