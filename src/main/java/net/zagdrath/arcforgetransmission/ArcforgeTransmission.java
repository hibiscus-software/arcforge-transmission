/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.zagdrath.arcforgetransmission.tower.TowerContent;

@Mod(ArcforgeTransmission.MODID)
public class ArcforgeTransmission {
    public static final String MODID = "arcforgetransmission";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ArcforgeTransmission(IEventBus modEventBus, ModContainer modContainer) {
        TowerContent.register(modEventBus);
    }
}
