/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.item;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
import net.zagdrath.arcforgetransmission.grid.ConnectionRules;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.GridNode;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridContent;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.tower.TowerBlock;
import net.zagdrath.arcforgetransmission.tower.TowerPartBlock;

// A coil of one tier's wire (one conductor), bare or (LV and MV) rubber-insulated. Used on an insulator it ties on one end (the insulator nearest where it's
// clicked); used on another, it strings the wire between them (one coil a wire), or says why it can't (another tier,
// the other circuit type, too far, already joined). Sneak-use in the air drops the loose end. A tower's parts count as
// the tower.
public class WireCoilItem extends Item {
    private final VoltageTier tier;
    private final boolean insulated;

    public WireCoilItem(VoltageTier tier, boolean insulated, Item.Properties properties) {
        super(properties);
        this.tier = tier;
        this.insulated = insulated;
    }

    public VoltageTier tier() {
        return tier;
    }

    // Rubber-insulated (LV and MV): safe to touch, drawn black.
    public boolean insulated() {
        return insulated;
    }

    // The endpoint a click on pos means: the block's own, or for a tower part its tower's.
    public static @Nullable GridEndpointBlockEntity endpointAt(Level level, BlockPos pos) {
        BlockPos at = pos;
        if (level.getBlockState(pos).getBlock() instanceof TowerPartBlock) {
            BlockPos main = TowerPartBlock.mainOf(level, pos);
            if (main == null || !(level.getBlockState(main).getBlock() instanceof TowerBlock)) {
                return null;
            }
            at = main;
        }
        return level.getBlockEntity(at) instanceof GridEndpointBlockEntity endpoint ? endpoint : null;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        GridEndpointBlockEntity endpoint = endpointAt(level, context.getClickedPos());
        if (endpoint == null) {
            return InteractionResult.PASS;
        }
        if (!(level instanceof ServerLevel server) || player == null) {
            return InteractionResult.SUCCESS;
        }
        String m = ArcforgeTransmission.MODID;
        int index = endpoint.nearest(context.getClickLocation());
        if (index < 0) {
            return InteractionResult.PASS;
        }
        GridData.Anchor here = new GridData.Anchor(endpoint.getBlockPos(), index);
        GridNode node = GridManager.node(server, here);
        if (node == null) {
            return InteractionResult.PASS;
        }
        if (node.tier() != tier) {
            player.sendOverlayMessage(Component.translatable("message." + m + ".wire." + ConnectionRules.Refusal.WRONG_WIRE.key()));
            return InteractionResult.FAIL;
        }
        ItemStack stack = context.getItemInHand();
        String dimension = level.dimension().identifier().toString();
        LinkTarget pending = stack.get(GridContent.LINK.get());
        if (pending == null || !pending.dimension().equals(dimension)) {
            stack.set(GridContent.LINK.get(), new LinkTarget(dimension, here.pos(), index));
            player.sendOverlayMessage(Component.translatable("message." + m + ".wire.tied", node.phase().name()));
            level.playSound(null, here.pos(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.3F, 1.2F);
            return InteractionResult.SUCCESS;
        }
        ConnectionRules.Refusal refusal = GridManager.connect(server, new GridData.Anchor(pending.pos(), pending.index()), here, tier,
                insulated ? GridEdge.Kind.INSULATED : GridEdge.Kind.BARE);
        if (refusal != null) {
            Component message = refusal == ConnectionRules.Refusal.TOO_LONG
                    ? Component.translatable("message." + m + ".wire.too_long", (int) GridConfig.SETTINGS.maxSpan(tier))
                    : Component.translatable("message." + m + ".wire." + refusal.key());
            player.sendOverlayMessage(message);
            if (refusal == ConnectionRules.Refusal.MISSING) {
                stack.remove(GridContent.LINK.get());
            }
            return InteractionResult.FAIL;
        }
        stack.remove(GridContent.LINK.get());
        if (!player.isCreative()) {
            stack.shrink(1);
        }
        player.sendOverlayMessage(Component.translatable("message." + m + ".wire.strung"));
        level.playSound(null, here.pos(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.3F, 0.8F);
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

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        String m = ArcforgeTransmission.MODID;
        builder.accept(Component.translatable("tooltip." + m + ".wire.tier", tier.name(), tier.volts(),
                Component.translatable("conductor." + m + "." + tier.conductor())).withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("tooltip." + m + ".wire.rating", amps(GridConfig.SETTINGS.ratedAmps(tier)),
                (int) GridConfig.SETTINGS.maxSpan(tier)).withStyle(ChatFormatting.GRAY));
        if (insulated) {
            builder.accept(Component.translatable("tooltip." + m + ".wire.insulated").withStyle(ChatFormatting.DARK_AQUA));
        }
        LinkTarget link = stack.get(GridContent.LINK.get());
        if (link != null) {
            builder.accept(Component.translatable("tooltip." + m + ".wire.tied", link.pos().toShortString()).withStyle(ChatFormatting.AQUA));
        }
    }

    private static String amps(double amps) {
        return amps == Math.rint(amps) ? Long.toString((long) amps) : String.format(java.util.Locale.ROOT, "%.1f", amps);
    }
}
