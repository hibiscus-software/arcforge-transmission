/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.block.GridContent;

// The grid's client side: the pin insulators' wires and the transformer (towers draw their own wires, TowerRenderer).
@EventBusSubscriber(modid = ArcforgeTransmission.MODID, value = Dist.CLIENT)
public final class GridClient {
    private GridClient() {}

    // The Wall Bushing's facades (WallBushingModel).
    @SubscribeEvent
    static void registerStandaloneModels(ModelEvent.RegisterStandalone event) {
        WallBushingModel.registerStandalone(event);
    }

    @SubscribeEvent
    static void modifyBakingResult(ModelEvent.ModifyBakingResult event) {
        WallBushingModel.wrap(event);
    }

    @SubscribeEvent
    static void registerBlockTints(RegisterColorHandlersEvent.BlockTintSources event) {
        event.register(WallBushingModel.tints(event.getBlockColors()), GridContent.WALL_BUSHING.get());
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(GridContent.PIN_INSULATOR_ENTITY.get(), WiresOnlyRenderer::new);
        event.registerBlockEntityRenderer(GridContent.POLE_TRANSFORMER_ENTITY.get(), PoleTransformerRenderer::new);
    }
}
