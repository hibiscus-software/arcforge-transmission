/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.client;

import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.block.PinInsulatorBlockEntity;

// While the crosshair is on a Pin Insulator: a small panel under it with what the insulator is set to (its name, phase
// label and circuit type, its port's mode and current limit) and how its circuit is doing (the status, and the FE/t
// through it). The block entity keeps the client told (PinInsulatorBlockEntity.refreshDisplay).
@EventBusSubscriber(modid = ArcforgeTransmission.MODID, value = Dist.CLIENT)
public final class PinInsulatorHud {
    private static final int PAD = 4, LINE = 10, BACKGROUND = 0xC0101214, BORDER = 0xFF3A4048;

    private PinInsulatorHud() {}

    @SubscribeEvent
    static void registerHud(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "pin_insulator"),
                PinInsulatorHud::draw);
    }

    private static void draw(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() != null || minecraft.level == null || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || !(minecraft.level.getBlockEntity(hit.getBlockPos()) instanceof PinInsulatorBlockEntity insulator)) {
            return;
        }
        String m = ArcforgeTransmission.MODID;
        Phase phase = insulator.phase();
        FlowSolver.PortStatus status = insulator.status();
        MutableComponent statusText = Component.translatable("port_status." + m + "." + status.name().toLowerCase(Locale.ROOT));
        List<Component> lines = List.of(
                insulator.getBlockState().getBlock().getName().withStyle(ChatFormatting.WHITE),
                Component.translatable("hud." + m + ".pin_insulator.phase", Component.literal(phase.name()).withStyle(ChatFormatting.AQUA),
                        Component.translatable("circuit." + m + "." + phase.circuit().id())).withStyle(ChatFormatting.GRAY),
                Component.translatable("hud." + m + ".pin_insulator.mode",
                        Component.translatable("pin_insulator_mode." + m + "." + insulator.mode().id()).withStyle(ChatFormatting.YELLOW),
                        format(insulator.limitAmps()), insulator.limitEighths()).withStyle(ChatFormatting.GRAY),
                (insulator.lastFlow() >= 1 ? Component.translatable("hud." + m + ".pin_insulator.flow", statusText, Math.round(insulator.lastFlow()))
                        : statusText).withStyle(status == FlowSolver.PortStatus.OK ? ChatFormatting.GREEN : ChatFormatting.RED));
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

    private static String format(double amps) {
        return amps == Math.rint(amps) ? Long.toString((long) amps) : String.format(Locale.ROOT, "%.1f", amps);
    }
}
