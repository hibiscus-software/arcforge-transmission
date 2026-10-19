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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.zagdrath.arcforgetransmission.grid.block.GridInteractions;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.block.GridContent;
import net.zagdrath.arcforgetransmission.tower.TowerBlockEntity;
import net.zagdrath.arcforgetransmission.tower.TowerTier;

// Control Cable: use it on one block, then another, to link them (or, if they're linked, unlink them):
//  - a Relay Panel with a breaker or recloser (it protects it), a Capacitor Bank or Shunt Reactor (it switches it on
//    the voltage) or a Substation Transformer (its differential);
//  - a Disconnect Switch with its breaker (the interlock: the switch only operates with the breaker open).
// Within MAX_REACH blocks. The cable isn't used up: the link is a setting, not a wire.
public class ControlCableItem extends Item {
    public static final int MAX_REACH = 48;

    public ControlCableItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(WireCoilItem.endpointAt(context.getLevel(), context.getClickedPos()) instanceof TowerBlockEntity tower)) {
            return InteractionResult.PASS;
        }
        if (!GridInteractions.mayChange(context.getPlayer(), context.getLevel(), context.getClickedPos())) {
            return InteractionResult.FAIL;
        }
        if (!(context.getLevel() instanceof ServerLevel level) || context.getPlayer() == null) {
            return InteractionResult.SUCCESS;
        }
        Player player = context.getPlayer();
        String m = ArcforgeTransmission.MODID, dimension = level.dimension().identifier().toString();
        ItemStack stack = context.getItemInHand();
        LinkTarget pending = stack.get(GridContent.LINK.get());
        if (pending == null || !pending.dimension().equals(dimension) || pending.pos().equals(tower.getBlockPos())) {
            stack.set(GridContent.LINK.get(), new LinkTarget(dimension, tower.getBlockPos(), 0));
            player.sendOverlayMessage(Component.translatable("message." + m + ".control.tied"));
            return InteractionResult.SUCCESS;
        }
        stack.remove(GridContent.LINK.get());
        if (!(level.getBlockEntity(pending.pos()) instanceof TowerBlockEntity first)) {
            player.sendOverlayMessage(Component.translatable("message." + m + ".control.cant"));
            return InteractionResult.FAIL;
        }
        if (!first.getBlockPos().closerThan(tower.getBlockPos(), MAX_REACH)) {
            player.sendOverlayMessage(Component.translatable("message." + m + ".control.too_far", MAX_REACH));
            return InteractionResult.FAIL;
        }
        TowerBlockEntity owner = owner(first, tower), target = owner == first ? tower : first;
        if (owner == null || !accepts(owner.tier().kind(), target.tier().kind())) {
            player.sendOverlayMessage(Component.translatable("message." + m + ".control.cant"));
            return InteractionResult.FAIL;
        }
        boolean linked = owner.toggleControl(target.getBlockPos());
        player.sendOverlayMessage(Component.translatable("message." + m + ".control." + (linked ? "linked" : "unlinked"), target.getBlockState().getBlock()
                .getName(), owner.getBlockState().getBlock().getName()));
        level.playSound(null, tower.getBlockPos(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.4F, 1.2F);
        return InteractionResult.SUCCESS;
    }

    // Which of the two holds the link: a Relay Panel, else a Disconnect Switch.
    private static TowerBlockEntity owner(TowerBlockEntity a, TowerBlockEntity b) {
        for (TowerTier.Kind kind : new TowerTier.Kind[] { TowerTier.Kind.RELAY, TowerTier.Kind.DISCONNECT }) {
            if (a.tier().kind() == kind) {
                return a;
            }
            if (b.tier().kind() == kind) {
                return b;
            }
        }
        return null;
    }

    private static boolean accepts(TowerTier.Kind owner, TowerTier.Kind target) {
        return owner == TowerTier.Kind.RELAY
                ? target == TowerTier.Kind.BREAKER || target == TowerTier.Kind.SHUNT || target == TowerTier.Kind.SUBSTATION
                : owner == TowerTier.Kind.DISCONNECT && target == TowerTier.Kind.BREAKER;
    }
}
