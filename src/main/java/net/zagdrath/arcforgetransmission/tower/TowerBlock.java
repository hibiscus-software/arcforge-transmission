/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.arcforgetransmission.grid.block.GridInteractions;
import net.zagdrath.arcforgetransmission.grid.item.WireCoilItem;

// A transmission tower's main block, at the foot of one of its poles or legs: its block entity draws the whole tower
// (TowerRenderer) and takes the rest with it when it goes. FACING is the line's direction, the way the
// player faced placing it. Placed by TowerItem; its other blocks are TowerPartBlocks.
public class TowerBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    // Turned an eighth further clockwise than FACING (45 degrees, for corners).
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty DIAGONAL =
            net.minecraft.world.level.block.state.properties.BooleanProperty.create("diagonal");

    private final TowerTier tier;

    public TowerBlock(TowerTier tier, BlockBehaviour.Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(DIAGONAL, false));
    }

    public TowerTier tier() {
        return tier;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, DIAGONAL);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TowerBlockEntity(pos, state);
    }

    // An open structure: light passes through it, and it casts no ambient-occlusion shade on the ground under it (its
    // shape is only for hits and walking into).
    @Override
    protected boolean propagatesSkylightDown(BlockState state) {
        return true;
    }

    @Override
    protected int getLightDampening(BlockState state) {
        return 0;
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    // Its own piece of the model (the foot of a pole, or a leg on its footing).
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int eighths = TowerPlacement.eighths(state);
        return TowerPlacement.shape(tier, eighths, TowerPlacement.mainOffset(tier, eighths));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    // The whole tower goes, from whichever block: the item drops once, from here (in survival).
    public static void breakTower(Level level, BlockPos main, boolean drop) {
        if (!level.isClientSide() && level.getBlockState(main).getBlock() instanceof TowerBlock) {
            level.destroyBlock(main, drop);
        }
    }

    // Empty-handed: a recloser, breaker or disconnect switch opens or closes by hand (GridInteractions has the Wrench).
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (WireCoilItem.endpointAt(level, pos) instanceof TowerBlockEntity tower && tower.tier().isDevice()) {
            if (level instanceof net.minecraft.server.level.ServerLevel server) {
                net.minecraft.network.chat.Component now = tower.operate(server);
                if (now != null) {
                    player.sendOverlayMessage(now);
                }
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    // A comparator reads a transformer's or device's load off its main block.
    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof TowerBlockEntity tower ? tower.comparator() : 0;
    }

    // Arcforge's Wrench on any part of the tower: its insulators' labels (see GridInteractions).
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (WireCoilItem.endpointAt(level, pos) instanceof TowerBlockEntity tower) {
            InteractionResult result = GridInteractions.wrench(stack, level, tower, player, hit.getLocation(), false);
            if (result != null) {
                return result;
            }
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }
}
