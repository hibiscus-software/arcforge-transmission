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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.factory.LineupRatings;
import net.zagdrath.arcforgetransmission.factory.SectionBlockEntity;
import net.zagdrath.arcforgetransmission.factory.SectionPartBlockEntity;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.OpState;

// While the crosshair is on any block of a lineup's section: the whole lineup at a glance. Each incomer's state, voltage,
// current and power; each transformer's load and temperature (a share of its trip point) and its fans; the mains' and the
// tie's states; each feeder's state, current and share of its setting. Big numbers in kFE/t and MFE/t. A row that isn't a
// lineup says what's wrong. The section keeps the client told (SectionBlockEntity's HUD rows).
@EventBusSubscriber(modid = ArcforgeTransmission.MODID, value = Dist.CLIENT)
public final class LineupHud {
    private static final int PAD = 4, LINE = 10, BACKGROUND = 0xC0101214, BORDER = 0xFF3A4048;

    private LineupHud() {}

    @SubscribeEvent
    static void registerHud(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "lineup"), LineupHud::draw);
    }

    private static @Nullable SectionBlockEntity target(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        if (level.getBlockEntity(pos) instanceof SectionPartBlockEntity part) {
            pos = part.mainPos();
        }
        return level.getBlockEntity(pos) instanceof SectionBlockEntity section ? section : null;
    }

    // The lines for a section's lineup.
    public static List<Component> lines(SectionBlockEntity section) {
        String m = ArcforgeTransmission.MODID;
        List<Component> lines = new ArrayList<>();
        lines.add(section.getBlockState().getBlock().getName().withStyle(ChatFormatting.WHITE));
        if (!section.hudProblem().isEmpty()) {
            lines.add(Component.translatable("hud." + m + ".lineup.problem", Component.translatable("hud." + m + ".lineup." + section.hudProblem()))
                    .withStyle(ChatFormatting.RED));
            return lines;
        }
        long[] rows = section.hud();
        for (int i = 0; i + SectionBlockEntity.HUD_FIELDS <= rows.length; i += SectionBlockEntity.HUD_FIELDS) {
            int kind = (int) rows[i], number = (int) rows[i + 1], state = (int) rows[i + 2];
            VoltageTier tier = VoltageTier.values()[(int) Math.max(0, Math.min(3, rows[i + 3]))];
            double volts = rows[i + 4] / 1000.0, amps = rows[i + 5] / 100.0;
            long power = rows[i + 6];
            double load = rows[i + 7] / 10.0, temp = rows[i + 8] / 10.0;
            MutableComponent line = switch (kind) {
                case SectionBlockEntity.Hud.INCOMER -> Component.translatable("hud." + m + ".lineup.incomer", number, state(state),
                        Math.round(volts * tier.volts()), amps(amps), LineupRatings.power(power));
                case SectionBlockEntity.Hud.TRANSFORMER -> Component.translatable("hud." + m + ".lineup.transformer", number, percent(load), percent(temp),
                        Component.translatable(rows[i + 9] != 0 ? "hud.arcforgetransmission.lineup.fans_on" : "hud.arcforgetransmission.lineup.fans_off"));
                case SectionBlockEntity.Hud.MAIN -> Component.translatable("hud." + m + ".lineup.main", number, state(state), amps(amps),
                        LineupRatings.power(power));
                case SectionBlockEntity.Hud.TIE -> Component.translatable("hud." + m + ".lineup.tie", state(state), amps(amps));
                default -> Component.translatable("hud." + m + ".lineup.feeder", number, state(state), amps(amps), percent(load));
            };
            ChatFormatting colour = kind == SectionBlockEntity.Hud.TRANSFORMER ? temp > 0 ? ChatFormatting.GOLD : ChatFormatting.AQUA
                    : state == OpState.TRIPPED.ordinal() ? ChatFormatting.YELLOW : state == OpState.CLOSED.ordinal() ? ChatFormatting.RED : ChatFormatting.GREEN;
            lines.add(line.withStyle(colour));
        }
        return lines;
    }

    private static Component state(int state) {
        return Component.translatable("hud." + ArcforgeTransmission.MODID + ".lineup.state." + OpState.values()[Math.max(0, Math.min(2, state))].name()
                .toLowerCase(Locale.ROOT));
    }

    private static String amps(double amps) {
        return String.format(Locale.ROOT, amps < 100 ? "%.1f" : "%.0f", amps);
    }

    private static String percent(double p) {
        return String.format(Locale.ROOT, "%.0f", p);
    }

    private static void draw(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() != null) {
            return;
        }
        SectionBlockEntity section = target(minecraft);
        if (section == null) {
            return;
        }
        List<Component> lines = lines(section);
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
