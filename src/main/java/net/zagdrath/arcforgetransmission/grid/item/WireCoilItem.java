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
import net.zagdrath.arcforgetransmission.grid.block.GridInteractions;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.ConnectionRules;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.GridNode;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridContent;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.tower.TowerBlock;
import net.zagdrath.arcforgetransmission.tower.TowerPartBlock;

// A coil of wire: one tier's conductor (bare, rubber-insulated or underground cable, the last two LV and MV only), or
// Shield Wire (grounded steel for the tower peaks, any tier, no tier of its own). Used on an insulator it ties on one end
// (the insulator nearest where it's clicked); used on another, it strings the wire between them (one coil a wire), or
// says why it can't (another tier, the other circuit type, a conductor on a shield point or shield wire on a conductor's,
// too far, already joined). Sneak-use in the air drops the loose end. A tower's parts count as the tower.
public class WireCoilItem extends Item {
    private final @Nullable VoltageTier tier;
    private final GridEdge.Kind kind;

    public WireCoilItem(@Nullable VoltageTier tier, GridEdge.Kind kind, Item.Properties properties) {
        super(properties);
        this.tier = tier;
        this.kind = kind;
    }

    // Its tier (null for Shield Wire, which takes the towers').
    public @Nullable VoltageTier tier() {
        return tier;
    }

    public GridEdge.Kind kind() {
        return kind;
    }

    public boolean insulated() {
        return kind == GridEdge.Kind.INSULATED;
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
        } else if (level.getBlockEntity(pos) instanceof net.zagdrath.arcforgetransmission.factory.SectionPartBlockEntity part) {
            at = part.mainPos();
        } else if (level.getBlockState(pos).getBlock() instanceof net.zagdrath.arcforgetransmission.factory.CableTerminationPartBlock) {
            at = net.zagdrath.arcforgetransmission.factory.CableTerminationPartBlock.mainOf(pos, level.getBlockState(pos));
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
        if (!GridInteractions.mayChange(player, level, endpoint.getBlockPos())) {
            return InteractionResult.FAIL;
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
        boolean shield = kind == GridEdge.Kind.SHIELD;
        if ((node.phase() == Phase.G) != shield || !shield && node.tier() != tier) {
            player.sendOverlayMessage(Component.translatable("message." + m + ".wire." + (shield ? "shield_only" : node.phase() == Phase.G
                    ? "shield_point" : ConnectionRules.Refusal.WRONG_WIRE.key())));
            return InteractionResult.FAIL;
        }
        VoltageTier wireTier = shield ? node.tier() : tier;
        ItemStack stack = context.getItemInHand();
        String dimension = level.dimension().identifier().toString();
        LinkTarget pending = stack.get(GridContent.LINK.get());
        if (pending == null || !pending.dimension().equals(dimension)) {
            stack.set(GridContent.LINK.get(), new LinkTarget(dimension, here.pos(), index));
            player.sendOverlayMessage(Component.translatable("message." + m + ".wire.tied", node.phase().name()));
            level.playSound(null, here.pos(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.3F, 1.2F);
            return InteractionResult.SUCCESS;
        }
        ConnectionRules.Refusal refusal = GridManager.connect(server, new GridData.Anchor(pending.pos(), pending.index()), here, wireTier, kind);
        if (refusal != null) {
            Component message = refusal == ConnectionRules.Refusal.TOO_LONG
                    ? Component.translatable("message." + m + ".wire.too_long", (int) GridConfig.SETTINGS.maxSpan(wireTier))
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
        if (tier == null) {
            builder.accept(Component.translatable("tooltip." + m + ".wire.shield").withStyle(ChatFormatting.GRAY));
            return;
        }
        builder.accept(Component.translatable("tooltip." + m + ".wire.tier", tier.name(), tier.volts(),
                Component.translatable("conductor." + m + "." + tier.conductor())).withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("tooltip." + m + ".wire.rating", amps(GridConfig.SETTINGS.ratedAmps(tier)),
                (int) GridConfig.SETTINGS.maxSpan(tier)).withStyle(ChatFormatting.GRAY));
        if (kind == GridEdge.Kind.INSULATED || kind == GridEdge.Kind.BURIED) {
            builder.accept(Component.translatable("tooltip." + m + ".wire." + (kind == GridEdge.Kind.BURIED ? "buried" : "insulated"))
                    .withStyle(ChatFormatting.DARK_AQUA));
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
