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

// An instrument transformer, fitted by using it on what it measures: a Current Transformer (a breaker's or a
// transformer's current, for a Relay Panel's overcurrent and differential protection) or a Potential Transformer (a
// breaker's, transformer's or bank's voltage, for the panel's capacitor and reactor switching). One a block.
public class FittingItem extends Item {
    private final boolean current;

    public FittingItem(boolean current, Item.Properties properties) {
        super(properties);
        this.current = current;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(WireCoilItem.endpointAt(context.getLevel(), context.getClickedPos()) instanceof TowerBlockEntity tower)) {
            return InteractionResult.PASS;
        }
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }
        String m = ArcforgeTransmission.MODID;
        String result = tower.fit(current);
        if (context.getPlayer() != null) {
            context.getPlayer().sendOverlayMessage(Component.translatable("message." + m + ".fitting." + result));
        }
        if (!result.equals("fitted")) {
            return InteractionResult.FAIL;
        }
        context.getItemInHand().consume(1, context.getPlayer());
        level.playSound(null, tower.getBlockPos(), SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.6F, 1.6F);
        return InteractionResult.SUCCESS;
    }
}
