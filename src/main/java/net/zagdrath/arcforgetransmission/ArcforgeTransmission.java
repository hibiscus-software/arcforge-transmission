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
import net.neoforged.fml.config.ModConfig;
import net.zagdrath.arcforgetransmission.grid.block.GridContent;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.tower.TowerContent;

@Mod(ArcforgeTransmission.MODID)
public class ArcforgeTransmission {
    public static final String MODID = "arcforgetransmission";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ArcforgeTransmission(IEventBus modEventBus, ModContainer modContainer) {
        TowerContent.register(modEventBus);
        GridContent.register(modEventBus);
        modContainer.registerConfig(localConfigType(), GridConfig.SPEC, MODID + "-common.toml");
    }

    // FML 12.0.8 (NeoForge 26.3.0.37-beta) renamed ModConfig.Type.COMMON to LOCAL (as Arcforge finds it): looked up by
    // name, so the jar loads on either side of the rename.
    private static ModConfig.Type localConfigType() {
        for (ModConfig.Type type : ModConfig.Type.values()) {
            if (type.name().equals("LOCAL") || type.name().equals("COMMON")) {
                return type;
            }
        }
        throw new IllegalStateException("ModConfig.Type has neither LOCAL nor COMMON");
    }
}
