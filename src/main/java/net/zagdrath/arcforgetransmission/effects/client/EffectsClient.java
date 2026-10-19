/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.effects.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.effects.TransmissionParticles;

// The sounds' and particles' client side: the particles' providers, and each client tick the transformers' hum and the
// corona on wet lines (the arcs come as packets: ArcEffects).
@EventBusSubscriber(modid = ArcforgeTransmission.MODID, value = Dist.CLIENT)
public final class EffectsClient {
    private EffectsClient() {}

    @SubscribeEvent
    static void registerParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(TransmissionParticles.CORONA.get(), sprites -> new GridParticle.Provider(sprites, GridParticle.Kind.CORONA));
        event.registerSpriteSet(TransmissionParticles.ARC_FLASH.get(), sprites -> new GridParticle.Provider(sprites, GridParticle.Kind.FLASH));
        event.registerSpriteSet(TransmissionParticles.SPARK.get(), sprites -> new GridParticle.Provider(sprites, GridParticle.Kind.SPARK));
    }

    @SubscribeEvent
    static void clientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) {
            return;
        }
        TransformerHums.tick(mc);
        CoronaEffects.tick(mc);
    }

    @SubscribeEvent
    static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TransformerHums.clear();
        CoronaEffects.clear();
    }
}
