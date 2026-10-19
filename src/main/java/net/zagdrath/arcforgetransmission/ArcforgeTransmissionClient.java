/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.zagdrath.arcforgetransmission.effects.client.EffectsConfig;

@Mod(value = ArcforgeTransmission.MODID, dist = Dist.CLIENT)
public class ArcforgeTransmissionClient {
    public ArcforgeTransmissionClient(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, EffectsConfig.SPEC, ArcforgeTransmission.MODID + "-client.toml");
    }
}
