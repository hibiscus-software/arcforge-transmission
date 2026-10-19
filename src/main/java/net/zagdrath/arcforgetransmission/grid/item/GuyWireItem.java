/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.item;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.zagdrath.arcforgetransmission.grid.block.GridInteractions;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.block.GridContent;
import net.zagdrath.arcforgetransmission.tower.TowerBlockEntity;

// A Guy Wire: used on a pole or tower, then on a block of the ground, it braces the one to the other with a steel guy
// (where a line ends or turns and the wires pull the pole one way). Sneak-used on the pole, it takes the last guy off
// again. The guy is only a brace: it carries nothing.
public class GuyWireItem extends Item {
    public GuyWireItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        String m = ArcforgeTransmission.MODID;
        String dimension = level.dimension().identifier().toString();
        if (!GridInteractions.mayChange(player, level, context.getClickedPos())) {
            return InteractionResult.FAIL;
        }
        if (WireCoilItem.endpointAt(level, context.getClickedPos()) instanceof TowerBlockEntity tower) {
            if (level.isClientSide()) {
                return InteractionResult.SUCCESS;
            }
            if (player != null && player.isSecondaryUseActive()) {
                if (tower.removeGuy()) {
                    Block.popResource(level, context.getClickedPos(), new ItemStack(GridContent.GUY_WIRE.get()));
                    player.sendOverlayMessage(Component.translatable("message." + m + ".guy.removed"));
                }
                return InteractionResult.SUCCESS;
            }
            stack.set(GridContent.LINK.get(), new LinkTarget(dimension, tower.getBlockPos(), 0));
            if (player != null) {
                player.sendOverlayMessage(Component.translatable("message." + m + ".guy.tied"));
            }
            return InteractionResult.SUCCESS;
        }
        LinkTarget pending = stack.get(GridContent.LINK.get());
        if (pending == null || !pending.dimension().equals(dimension)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockPos anchor = context.getClickedPos();
        if (!(level.getBlockEntity(pending.pos()) instanceof TowerBlockEntity tower) || !tower.addGuy(anchor)) {
            if (player != null) {
                player.sendOverlayMessage(Component.translatable("message." + m + ".guy.too_far"));
            }
            return InteractionResult.FAIL;
        }
        stack.remove(GridContent.LINK.get());
        stack.consume(1, player);
        level.playSound(null, anchor, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        if (player != null) {
            player.sendOverlayMessage(Component.translatable("message." + m + ".guy.strung"));
        }
        return InteractionResult.SUCCESS;
    }
}
