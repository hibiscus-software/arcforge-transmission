/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.world;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.zagdrath.arcforgetransmission.effects.ArcEvent;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// Raises an interruption's arc (ArcEvent) where the server sees it happen, and sends it to the players within REACH of
// it, one small packet each; anything else (a game test) may listen too. A flashover at one node is told once, not on each
// tick it's raised as it starts.
public final class GridArcs {
    // Blocks round an arc its packet reaches (the crack is heard about this far).
    public static final double REACH = 64;
    private static final List<Consumer<ArcEvent>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Map<Long, Long> FLASHOVERS = new HashMap<>();

    private GridArcs() {}

    public static void listen(Consumer<ArcEvent> listener) {
        LISTENERS.add(listener);
    }

    public static void unlisten(Consumer<ArcEvent> listener) {
        LISTENERS.remove(listener);
    }

    public static void arc(ServerLevel level, Vec3 at, ArcEvent.Kind kind, VoltageTier tier, double severity) {
        ArcEvent arc = new ArcEvent(at, kind, tier, (float) Math.max(0, severity));
        for (Consumer<ArcEvent> listener : LISTENERS) {
            listener.accept(arc);
        }
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(at) <= REACH * REACH && player.connection != null && player.connection.hasChannel(arc)) {
                PacketDistributor.sendToPlayer(player, arc);
            }
        }
    }

    // A flashover to ground at a node (its insulator): once per fault, however many ticks it's raised as it starts.
    public static void flashover(ServerLevel level, long node, Vec3 at, VoltageTier tier, double severity) {
        long now = level.getGameTime();
        Long last = FLASHOVERS.put(node, now);
        if (last != null && now - last <= 2) {
            return;
        }
        if (FLASHOVERS.size() > 256) {
            FLASHOVERS.values().removeIf(t -> now - t > 2);
        }
        arc(level, at, ArcEvent.Kind.FLASHOVER, tier, severity);
    }
}
