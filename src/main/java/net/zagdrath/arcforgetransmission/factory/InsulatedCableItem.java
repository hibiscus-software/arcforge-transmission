/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridContent;
import net.zagdrath.arcforgetransmission.grid.block.GridInteractions;
import net.zagdrath.arcforgetransmission.grid.item.LinkTarget;
import net.zagdrath.arcforgetransmission.grid.item.WireCoilItem;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;

// Insulated Cable of a tier: three conductors in one jacket (LV black, MV a red stripe; HV and EHV three single cores in
// trefoil, an orange or violet stripe). Use it on one Cable Termination, then another of its tier: it's laid along tray
// between them by the shortest path (a free drop at each end; no tray at all only for a drop that short), taking an item
// for each factory.tray.cableBlocksPerItem blocks. Sneak-use in the air lets go of its loose end.
public class InsulatedCableItem extends Item {
    private final VoltageTier tier;

    public InsulatedCableItem(VoltageTier tier, Item.Properties properties) {
        super(properties);
        this.tier = tier;
    }

    public VoltageTier tier() {
        return tier;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null || !(WireCoilItem.endpointAt(level, context.getClickedPos()) instanceof CableTerminationBlockEntity here)) {
            return InteractionResult.PASS;
        }
        if (!GridInteractions.mayChange(player, level, here.getBlockPos())) {
            return InteractionResult.FAIL;
        }
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.SUCCESS;
        }
        String m = ArcforgeTransmission.MODID;
        if (here.tier() != tier) {
            player.sendOverlayMessage(Component.translatable("message." + m + ".cable.wrong_tier", tier.name()));
            return InteractionResult.FAIL;
        }
        ItemStack stack = context.getItemInHand();
        String dimension = level.dimension().identifier().toString();
        LinkTarget pending = stack.get(GridContent.LINK.get());
        if (pending == null || !pending.dimension().equals(dimension)
                || !(level.getBlockEntity(pending.pos()) instanceof CableTerminationBlockEntity from)) {
            stack.set(GridContent.LINK.get(), new LinkTarget(dimension, here.getBlockPos(), 0));
            player.sendOverlayMessage(Component.translatable("message." + m + ".cable.tied"));
            level.playSound(null, here.getBlockPos(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.3F, 1.2F);
            return InteractionResult.SUCCESS;
        }
        int have = player.hasInfiniteMaterials() ? Integer.MAX_VALUE : count(player);
        int[] spent = new int[1];
        Component refused = CableNetwork.lay(server, from, here, tier, have, spent);
        if (refused != null) {
            player.sendOverlayMessage(refused);
            return InteractionResult.FAIL;
        }
        stack.remove(GridContent.LINK.get());
        if (!player.hasInfiniteMaterials()) {
            take(player, spent[0]);
        }
        player.sendOverlayMessage(Component.translatable("message." + m + ".cable.laid", spent[0]));
        level.playSound(null, here.getBlockPos(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.3F, 0.8F);
        return InteractionResult.SUCCESS;
    }

    // Sneak-use in the air: let go of the loose end.
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isSecondaryUseActive() && stack.has(GridContent.LINK.get())) {
            stack.remove(GridContent.LINK.get());
            if (!level.isClientSide()) {
                player.sendOverlayMessage(Component.translatable("message." + ArcforgeTransmission.MODID + ".wire.cleared"));
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private int count(Player player) {
        int n = 0;
        for (ItemStack s : player.getInventory()) {
            if (s.getItem() == this) {
                n += s.getCount();
            }
        }
        return n;
    }

    private void take(Player player, int items) {
        for (ItemStack s : player.getInventory()) {
            if (items <= 0) {
                return;
            }
            if (s.getItem() == this) {
                int t = Math.min(items, s.getCount());
                s.shrink(t);
                items -= t;
            }
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        String m = ArcforgeTransmission.MODID;
        builder.accept(Component.translatable("tooltip." + m + ".cable.rating", net.zagdrath.arcforgetransmission.grid.block.GridInteractions
                .format(GridConfig.SETTINGS.ratedAmps(tier)), GridConfig.CABLE_BLOCKS_PER_ITEM.getAsInt()).withStyle(ChatFormatting.GRAY));
    }
}
