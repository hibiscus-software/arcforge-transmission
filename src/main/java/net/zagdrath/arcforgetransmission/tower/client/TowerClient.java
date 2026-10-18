/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.zagdrath.arcforgetransmission.tower.TowerContent;

// The towers' client side: each is drawn whole from its main block.
@EventBusSubscriber(modid = TowerContent.MODID, value = Dist.CLIENT)
public final class TowerClient {
    private TowerClient() {}

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(TowerContent.TOWER_ENTITY.get(), TowerRenderer::new);
    }
}
