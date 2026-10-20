/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission;

import net.zagdrath.arcforgetransmission.grid.world.Generators;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.zagdrath.arcforgetransmission.effects.TransmissionParticles;
import net.zagdrath.arcforgetransmission.effects.TransmissionSounds;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.data.AttachGroups;
import net.zagdrath.arcforgetransmission.tower.TowerTier;
import net.zagdrath.arcforgetransmission.factory.FactoryContent;
import net.zagdrath.arcforgetransmission.grid.block.GridContent;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridPayloads;
import net.zagdrath.arcforgetransmission.material.MaterialContent;
import net.zagdrath.arcforgetransmission.tower.TowerContent;

@Mod(ArcforgeTransmission.MODID)
public class ArcforgeTransmission {
    public static final String MODID = "arcforgetransmission";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ArcforgeTransmission(IEventBus modEventBus, ModContainer modContainer) {
        TowerContent.register(modEventBus);
        GridContent.register(modEventBus);
        FactoryContent.register(modEventBus);
        MaterialContent.register(modEventBus);
        TransmissionSounds.register(modEventBus);
        TransmissionParticles.register(modEventBus);
        Generators.register(modEventBus);
        modContainer.registerConfig(localConfigType(), GridConfig.SPEC, MODID + "-common.toml");
        modEventBus.addListener(ArcforgeTransmission::commonSetup);
        GridPayloads.register(modEventBus);
    }

    // Every attach file read up front, so a block never reads one from the jar mid-game.
    private static void commonSetup(FMLCommonSetupEvent event) {
        List<String> models = new ArrayList<>(List.of("pole_transformer"));
        for (TowerTier tier : TowerTier.values()) {
            models.add(tier.id());
        }
        for (VoltageTier tier : VoltageTier.values()) {
            models.add("pin_insulator_" + tier.id());
        }
        AttachGroups.preload(models);
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
