/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;
import net.zagdrath.arcforgetransmission.grid.block.PinInsulatorBlockEntity;
import net.zagdrath.arcforgetransmission.grid.block.PortHud;
import net.zagdrath.arcforgetransmission.grid.item.WireCoilItem;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.Insulator;
import net.zagdrath.arcforgetransmission.tower.TowerBlock;
import net.zagdrath.arcforgetransmission.tower.TowerBlockEntity;
import net.zagdrath.arcforgetransmission.tower.TowerPlacement;

// While the crosshair is on an insulator (a Pin Insulator, one on a tower, a transformer's bushing): a small panel under
// it with what it is (the block's name, its phase label and circuit type), its wires and the current on them against
// their rating, and for a pin insulator its port's mode, current limit and how its circuit is doing. The block entities
// keep the client told (GridEndpointBlockEntity.refreshDisplay).
@EventBusSubscriber(modid = ArcforgeTransmission.MODID, value = Dist.CLIENT)
public final class InsulatorHud {
    private static final int PAD = 4, LINE = 10, BACKGROUND = 0xC0101214, BORDER = 0xFF3A4048;

    private InsulatorHud() {}

    @SubscribeEvent
    static void registerHud(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "insulator"), InsulatorHud::draw);
    }

    // The endpoint and insulator under the crosshair: on a tower only when it's on an insulator, not its frame.
    private record Target(GridEndpointBlockEntity endpoint, int index) {}

    private static @Nullable Target target(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        GridEndpointBlockEntity endpoint = WireCoilItem.endpointAt(level, hit.getBlockPos());
        if (endpoint == null) {
            return null;
        }
        Vec3 at = hit.getLocation();
        if (endpoint instanceof TowerBlockEntity tower) {
            BlockState state = tower.getBlockState();
            if (!(state.getBlock() instanceof TowerBlock block)) {
                return null;
            }
            boolean onInsulator = false;
            for (AABB box : TowerPlacement.insulatorBoxes(block.tier(), TowerPlacement.eighths(state), tower.getBlockPos())) {
                if (box.inflate(1.0 / 16.0 + 1.0E-3).contains(at)) {
                    onInsulator = true;
                    break;
                }
            }
            if (!onInsulator) {
                return null;
            }
        }
        int index = endpoint.nearest(at);
        return index < 0 ? null : new Target(endpoint, index);
    }

    private static void draw(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() != null) {
            return;
        }
        Target target = target(minecraft);
        if (target == null) {
            return;
        }
        GridEndpointBlockEntity endpoint = target.endpoint();
        int index = target.index();
        List<Insulator> insulators = endpoint.insulators();
        if (index >= insulators.size()) {
            return;
        }
        String m = ArcforgeTransmission.MODID;
        Phase phase = endpoint.hudPhase(index);
        if (phase == null) {
            phase = insulators.get(index).phase();
        }
        VoltageTier tier = insulators.get(index).tier();
        List<Component> lines = new ArrayList<>();
        lines.add(endpoint.getBlockState().getBlock().getName().withStyle(ChatFormatting.WHITE));
        lines.add(Component.translatable("hud." + m + ".insulator.phase", Component.literal(phase.name()).withStyle(ChatFormatting.AQUA),
                Component.translatable("circuit." + m + "." + phase.circuit().id())).withStyle(ChatFormatting.GRAY));
        int wires = endpoint.hudWires(index);
        if (wires == 0) {
            lines.add(Component.translatable("hud." + m + ".insulator.unwired").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            double amps = endpoint.hudAmps(index), rated = GridConfig.SETTINGS.ratedAmps(tier);
            ChatFormatting colour = amps > rated + 1.0E-9 ? ChatFormatting.RED : amps > 0.8 * rated ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
            lines.add(Component.translatable("hud." + m + ".insulator." + (wires == 1 ? "wire" : "wires"), wires, format(amps), format(rated))
                    .withStyle(colour));
        }
        // Its voltage, against its tier's (yellow under the full-power line, red under the cutoff).
        double volts = endpoint.hudVolts(index);
        if (volts > 0) {
            ChatFormatting colour = volts < GridConfig.CUTOFF_VOLTAGE.getAsDouble() ? ChatFormatting.RED
                    : volts < GridConfig.FULL_POWER_VOLTAGE.getAsDouble() ? ChatFormatting.YELLOW : ChatFormatting.AQUA;
            lines.add(Component.translatable("hud." + m + ".insulator.volts", String.format(Locale.ROOT, "%.1f", volts * 100),
                    Math.round(volts * tier.volts())).withStyle(colour));
        }
        Component mode = endpoint instanceof PortHud port ? port.hudMode() : null;
        if (mode != null) {
            PortHud port = (PortHud) endpoint;
            lines.add(mode.copy().withStyle(ChatFormatting.YELLOW));
            boolean bushing = endpoint instanceof PinInsulatorBlockEntity insulator && insulator.onWallBushing();
            if (!bushing && port.hudFlow()) {
                FlowSolver.PortStatus status = port.status();
                MutableComponent statusText = Component.translatable("port_status." + m + "." + status.name().toLowerCase(Locale.ROOT));
                lines.add((port.lastFlow() >= 1 ? Component.translatable("hud." + m + ".pin_insulator.flow", statusText, Math.round(port.lastFlow()))
                        : statusText).withStyle(switch (status) {
                    case OK -> ChatFormatting.GREEN;
                    // Wired up and live, waiting on the other end: not a fault.
                    case NO_PARTNER -> ChatFormatting.GOLD;
                    default -> ChatFormatting.RED;
                }));
            }
        }
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
