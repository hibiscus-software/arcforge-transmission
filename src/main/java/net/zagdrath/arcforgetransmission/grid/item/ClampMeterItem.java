/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.item;

import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.factory.CableData;
import net.zagdrath.arcforgetransmission.factory.CableNetwork;
import net.zagdrath.arcforgetransmission.factory.CableTrayBlockEntity;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.GridNode;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;

// A Clamp Meter: used on a wire (the one the crosshair is on, within 8 blocks) it reads it out in chat: its tier and
// kind, the current on it against its rating, its loss (FE/t) and its length, and its ends' labels; used on an insulator
// it reads every wire on it, on a block of cable tray every cable through it. A cable derated by the tray it shares says
// so and why. The readings are the last tick's.
public class ClampMeterItem extends Item {
    private static final double REACH = 8.0, AIM = 0.3;

    public ClampMeterItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level instanceof ServerLevel server) {
            GridManager.WireHit hit = GridManager.wireAlong(server, player.getEyePosition(), player.getLookAngle(), REACH, AIM);
            if (hit == null) {
                player.sendOverlayMessage(Component.translatable("message." + ArcforgeTransmission.MODID + ".meter.nothing"));
            } else {
                read(server, player, List.of(hit.edge()));
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() != null && context.getLevel().getBlockEntity(context.getClickedPos()) instanceof CableTrayBlockEntity) {
            if (context.getLevel() instanceof ServerLevel server) {
                tray(server, context.getPlayer(), CableData.of(server).inTray(context.getClickedPos()));
            }
            return InteractionResult.SUCCESS;
        }
        GridEndpointBlockEntity endpoint = WireCoilItem.endpointAt(context.getLevel(), context.getClickedPos());
        Player player = context.getPlayer();
        if (endpoint == null || player == null) {
            return InteractionResult.PASS;
        }
        if (context.getLevel() instanceof ServerLevel server) {
            int index = endpoint.nearest(context.getClickLocation());
            List<Long> wires = index < 0 ? List.of() : GridManager.wiresAt(server, new GridData.Anchor(endpoint.getBlockPos(), index));
            if (wires.isEmpty()) {
                player.sendOverlayMessage(Component.translatable("message." + ArcforgeTransmission.MODID + ".meter.unwired"));
            } else {
                read(server, player, wires);
            }
        }
        return InteractionResult.SUCCESS;
    }

    // Each cable through a tray block: its tier, its current against its derated rating, and why it's derated.
    private static void tray(ServerLevel level, Player player, List<CableData.Cable> cables) {
        String m = ArcforgeTransmission.MODID;
        if (cables.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("message." + m + ".meter.empty_tray"));
            return;
        }
        GridData.Dimension dimension = GridData.of(level);
        for (CableData.Cable cable : cables) {
            GridEdge edge = cable.edges().isEmpty() ? null : dimension.graph().edge(cable.edges().get(0));
            double amps = CableNetwork.amps(level, cable), rated = edge == null ? 0 : GridConfig.SETTINGS.ratedAmps(edge);
            ChatFormatting colour = amps > rated + 1.0E-9 ? ChatFormatting.RED : amps > 0.8 * rated ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
            player.sendSystemMessage(Component.translatable("message." + m + ".meter.cable", Component.literal(cable.tier().name()),
                    Component.literal(format(amps)).withStyle(colour), format(rated), rated > 0 ? Math.round(100 * amps / rated) : 0,
                    format(cable.length())).withStyle(ChatFormatting.GRAY));
            derated(level, player, cable);
        }
    }

    // Why a cable's rated below its ampacity: the most cables it shares a tray block with.
    private static void derated(ServerLevel level, Player player, CableData.Cable cable) {
        double derate = CableNetwork.derate(level, cable);
        if (derate < 1.0) {
            player.sendSystemMessage(Component.translatable("message." + ArcforgeTransmission.MODID + ".meter.derated",
                    Math.round(100 * derate), CableNetwork.crowd(level, cable)).withStyle(ChatFormatting.YELLOW));
        }
    }

    private static void read(ServerLevel level, Player player, List<Long> wires) {
        String m = ArcforgeTransmission.MODID;
        GridData.Dimension dimension = GridData.of(level);
        for (long id : wires) {
            GridEdge edge = dimension.graph().edge(id);
            if (edge == null) {
                continue;
            }
            GridNode a = dimension.graph().node(edge.a()), b = dimension.graph().node(edge.b());
            double amps = GridManager.lastAmps(level).getOrDefault(id, 0.0), loss = GridManager.lastLoss(level).getOrDefault(id, 0.0);
            double rated = GridConfig.SETTINGS.ratedAmps(edge);
            ChatFormatting colour = amps > rated + 1.0E-9 ? ChatFormatting.RED : amps > 0.8 * rated ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
            player.sendSystemMessage(Component.translatable("message." + m + ".meter.wire",
                    Component.literal(edge.tier().name()), Component.translatable("wire_kind." + m + "." + edge.kind().name().toLowerCase(Locale.ROOT)),
                    Component.literal(format(amps)).withStyle(colour), format(rated), Math.round(100 * amps / rated), format(loss), format(edge.length()),
                    a == null ? "?" : a.phase().name(), b == null ? "?" : b.phase().name()).withStyle(ChatFormatting.GRAY));
            CableData.Cable cable = CableData.of(level).ofEdge(id);
            if (cable != null) {
                derated(level, player, cable);
            }
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, value < 10 ? "%.2f" : "%.1f", value);
    }
}
