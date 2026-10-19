/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.zagdrath.arcforge.item.conduit.ConduitFacadeItem;
import net.zagdrath.arcforge.registry.ModItems;

// A Wall Bushing: a block to build into a wall and pass wires through it. Pin Insulators mounted on its faces carry no FE
// of their own: those of one tier and one phase label are joined through the block (GridManager.rejoin), so a wire run
// can come in on one side and leave on the other (put A, B and C on each side for a three-phase run).
//
// It takes an Arcforge Conduit Facade over the whole block, as a conduit side does: use a facade on it to fit one (blank,
// or carrying the block it copies), use a block on a fitted facade to dress it in that block's look, and sneak-use it
// with an empty hand to take the block back off, then again to take the facade off. The facade is only its look.
public class WallBushingBlock extends BaseEntityBlock {
    public WallBushingBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WallBushingBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof WallBushingBlockEntity bushing)) {
            return InteractionResult.PASS;
        }
        if (stack.getItem() instanceof ConduitFacadeItem) {
            if (bushing.hasFacade()) {
                return InteractionResult.FAIL;
            }
            BlockState target = ConduitFacadeItem.target(stack);
            if (target != null && !ConduitFacadeItem.canCopy(target)) {
                return InteractionResult.FAIL;
            }
            if (!level.isClientSide()) {
                bushing.setFacade(true, target);
                stack.consume(1, player);
                level.playSound(null, pos, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 0.8F, 1.2F);
            }
            return InteractionResult.SUCCESS;
        }
        BlockState dress = bushing.hasFacade() && bushing.target() == null ? ConduitFacadeItem.targetFor(stack) : null;
        if (dress != null) {
            if (!level.isClientSide()) {
                bushing.setFacade(true, dress);
                stack.consume(1, player);
                level.playSound(null, pos, dress.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
            }
            return InteractionResult.SUCCESS;
        }
        // Anything else (a Pin Insulator to mount, a block to build against it) is the item's to use.
        return stack.isEmpty() ? InteractionResult.TRY_WITH_EMPTY_HAND : InteractionResult.PASS;
    }

    // Sneak-use, empty-handed: the dressing comes off, then the facade.
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.isSecondaryUseActive() || !(level.getBlockEntity(pos) instanceof WallBushingBlockEntity bushing) || !bushing.hasFacade()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            BlockState target = bushing.target();
            if (target != null) {
                bushing.setFacade(true, null);
                give(player, new ItemStack(target.getBlock()));
            } else {
                bushing.setFacade(false, null);
                give(player, new ItemStack(ModItems.CONDUIT_FACADE.get()));
            }
            level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 1.0F);
        }
        return InteractionResult.SUCCESS;
    }

    private static void give(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            Block.popResource(player.level(), player.blockPosition(), stack);
        }
    }

    // Broken, it gives its facade and the block it was dressed in back too.
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && !player.isCreative() && level.getBlockEntity(pos) instanceof WallBushingBlockEntity bushing && bushing.hasFacade()) {
            Block.popResource(level, pos, new ItemStack(ModItems.CONDUIT_FACADE.get()));
            if (bushing.target() != null) {
                Block.popResource(level, pos, new ItemStack(bushing.target().getBlock()));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
