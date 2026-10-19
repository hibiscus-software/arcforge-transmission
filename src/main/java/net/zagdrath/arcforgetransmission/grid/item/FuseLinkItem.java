/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.item;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.tower.TowerBlockEntity;
import net.zagdrath.arcforgetransmission.tower.TowerTier;

// A Fuse Link: used on a blown Fuse Cutout, it goes in and the cutout closes again (one link a fuse).
public class FuseLinkItem extends Item {
    public FuseLinkItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(WireCoilItem.endpointAt(context.getLevel(), context.getClickedPos()) instanceof TowerBlockEntity tower)
                || tower.tier().kind() != TowerTier.Kind.FUSE) {
            return InteractionResult.PASS;
        }
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }
        String m = ArcforgeTransmission.MODID;
        if (!tower.refuse(level)) {
            if (context.getPlayer() != null) {
                context.getPlayer().sendOverlayMessage(Component.translatable("message." + m + ".fuse.intact"));
            }
            return InteractionResult.FAIL;
        }
        context.getItemInHand().consume(1, context.getPlayer());
        level.playSound(null, tower.getBlockPos(), SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.8F, 1.4F);
        if (context.getPlayer() != null) {
            context.getPlayer().sendOverlayMessage(Component.translatable("message." + m + ".fuse.replaced"));
        }
        return InteractionResult.SUCCESS;
    }
}
