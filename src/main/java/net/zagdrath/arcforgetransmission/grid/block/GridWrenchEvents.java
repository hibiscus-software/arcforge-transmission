/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.zagdrath.arcforge.item.tool.WrenchItem;
import net.zagdrath.arcforge.item.tool.WrenchMode;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.item.WireCoilItem;
import net.zagdrath.arcforgetransmission.tower.TowerBlock;
import net.zagdrath.arcforgetransmission.tower.TowerPartBlock;

// Arcforge's Wrench on the grid's blocks (pin insulators, the transformer, the towers, the creative blocks), before
// anything else: the game skips a block's own use when the player sneaks with an item in hand, so the sneaking actions
// (Sneak + Configure for a pin insulator's current limit or a creative block's setpoint, Sneak + Dismantle) would never
// reach the block. This runs whether or not the player sneaks; GridInteractions does the work.
@EventBusSubscriber(modid = ArcforgeTransmission.MODID)
public final class GridWrenchEvents {
    private GridWrenchEvents() {}

    @SubscribeEvent
    static void useItemOnBlock(UseItemOnBlockEvent event) {
        if (event.getUsePhase() != UseItemOnBlockEvent.UsePhase.ITEM_BEFORE_BLOCK || !GridInteractions.isWrench(event.getItemStack())
                || event.getPlayer() == null) {
            return;
        }
        Block block = event.getLevel().getBlockState(event.getPos()).getBlock();
        if (block instanceof CreativeEnergyBlock) {
            if (WrenchItem.mode(event.getItemStack()) == WrenchMode.CONFIGURE) {
                event.cancelWithResult(CreativeEnergyBlock.configure(event.getLevel(), event.getPos(), event.getPlayer()));
            }
            return;
        }
        boolean dismantleable = block instanceof PinInsulatorBlock || block instanceof PoleTransformerBlock;
        if (!dismantleable && !(block instanceof TowerBlock) && !(block instanceof TowerPartBlock)) {
            return;
        }
        GridEndpointBlockEntity endpoint = WireCoilItem.endpointAt(event.getLevel(), event.getPos());
        if (endpoint == null) {
            return;
        }
        InteractionResult result = GridInteractions.wrench(event.getItemStack(), event.getLevel(), endpoint, event.getPlayer(),
                event.getUseOnContext().getClickLocation(), dismantleable);
        if (result != null && result != InteractionResult.PASS) {
            event.cancelWithResult(result);
        }
    }
}
