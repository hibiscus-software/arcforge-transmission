/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.factory.CableNetwork;
import net.zagdrath.arcforgetransmission.factory.CableTrayBlock;
import net.zagdrath.arcforgetransmission.factory.CableTrayBlockEntity;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// While the crosshair is on a block of cable tray: its fill (cables of its slots), how many of each tier, and the
// derating every cable through it takes for the company (the Clamp Meter reads each one's current against it).
@EventBusSubscriber(modid = ArcforgeTransmission.MODID, value = Dist.CLIENT)
public final class TrayHud {
    private static final int PAD = 4, LINE = 10, BACKGROUND = 0xC0101214, BORDER = 0xFF3A4048;

    private TrayHud() {}

    @SubscribeEvent
    static void registerHud(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "cable_tray"), TrayHud::draw);
    }

    private static @Nullable CableTrayBlockEntity target(Minecraft minecraft) {
        if (minecraft.level == null || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return minecraft.level.getBlockEntity(hit.getBlockPos()) instanceof CableTrayBlockEntity tray ? tray : null;
    }

    public static List<Component> lines(CableTrayBlockEntity tray) {
        String m = ArcforgeTransmission.MODID;
        List<Component> lines = new ArrayList<>();
        lines.add(tray.getBlockState().getBlock().getName().withStyle(ChatFormatting.WHITE));
        CableTrayBlock.Kind kind = tray.getBlockState().getBlock() instanceof CableTrayBlock block ? block.kind() : CableTrayBlock.Kind.STANDARD;
        List<CableNetwork.Lie> lies = tray.lies();
        int fill = CableNetwork.fill(kind), cables = lies.size();
        lines.add(Component.translatable("hud." + m + ".cable_tray.fill", cables, fill)
                .withStyle(cables >= fill ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
        int[] tiers = new int[VoltageTier.values().length];
        for (CableNetwork.Lie lie : lies) {
            tiers[Math.max(0, Math.min(tiers.length - 1, lie.tier()))]++;
        }
        for (VoltageTier tier : VoltageTier.values()) {
            if (tiers[tier.ordinal()] > 0) {
                lines.add(Component.translatable("hud." + m + ".cable_tray.tier", tiers[tier.ordinal()], tier.name()).withStyle(ChatFormatting.GRAY));
            }
        }
        double derate = CableNetwork.derating(cables);
        lines.add(derate < 1.0
                ? Component.translatable("hud." + m + ".cable_tray.derated", String.format(Locale.ROOT, "%.0f", 100 * derate), cables)
                        .withStyle(ChatFormatting.YELLOW)
                : Component.translatable("hud." + m + ".cable_tray.full").withStyle(ChatFormatting.GREEN));
        return lines;
    }

    private static void draw(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() != null) {
            return;
        }
        CableTrayBlockEntity tray = target(minecraft);
        if (tray == null) {
            return;
        }
        List<Component> lines = lines(tray);
        int width = 0;
        for (Component line : lines) {
            width = Math.max(width, minecraft.font.width(line));
        }
        int w = width + 2 * PAD, h = lines.size() * LINE + 2 * PAD - 2;
        int x = graphics.guiWidth() / 2 - w / 2, y = graphics.guiHeight() / 2 + 12;
        graphics.fill(x, y, x + w, y + h, BACKGROUND);
        graphics.outline(x, y, w, h, BORDER);
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(minecraft.font, lines.get(i), x + PAD, y + PAD + i * LINE, 0xFFFFFFFF, true);
        }
    }
}
