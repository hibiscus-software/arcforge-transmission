/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridPayloads;

// The client's side of GridPayloads: a block's HUD read into it, an arc drawn, and the server's grid settings kept (until
// leaving).
@EventBusSubscriber(modid = ArcforgeTransmission.MODID, value = Dist.CLIENT)
public final class GridClientPayloads {
    private GridClientPayloads() {}

    public static void hud(GridPayloads.Hud payload) {
        var level = Minecraft.getInstance().level;
        if (level != null && level.isLoaded(payload.pos()) && level.getBlockEntity(payload.pos()) instanceof GridEndpointBlockEntity endpoint) {
            endpoint.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), payload.tag()));
        }
    }

    public static void settings(GridPayloads.Settings payload) {
        GridConfig.sync(payload.synced());
    }

    public static void arc(net.zagdrath.arcforgetransmission.effects.ArcEvent payload) {
        net.zagdrath.arcforgetransmission.effects.client.ArcEffects.play(payload);
    }

    @SubscribeEvent
    static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        GridConfig.sync(null);
    }
}
