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

// Arcforge's Wrench on the grid's blocks (pin insulators, the transformer, the towers, barbed wire), before
// anything else: the game skips a block's own use when the player sneaks with an item in hand, so the sneaking actions
// (Sneak + Configure for a pin insulator's current limit, Sneak + Dismantle) would never
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
        net.minecraft.world.level.block.state.BlockState state = event.getLevel().getBlockState(event.getPos());
        Block block = state.getBlock();
        // Barbed wire on Rotate: its outward side a quarter turn (back, sneaking), its strands following.
        if (block instanceof StationFenceBarbedWireBlock && WrenchItem.mode(event.getItemStack()) == WrenchMode.ROTATE) {
            if (!event.getLevel().isClientSide() && GridInteractions.mayChange(event.getPlayer(), event.getLevel(), event.getPos())) {
                var facing = state.getValue(StationFenceBarbedWireBlock.FACING);
                var turned = state.setValue(StationFenceBarbedWireBlock.FACING, event.getPlayer().isShiftKeyDown() ? facing.getCounterClockWise()
                        : facing.getClockWise());
                event.getLevel().setBlock(event.getPos(), StationFenceBarbedWireBlock.connected(turned, event.getLevel(), event.getPos()), Block.UPDATE_ALL);
            }
            event.cancelWithResult(InteractionResult.SUCCESS);
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
