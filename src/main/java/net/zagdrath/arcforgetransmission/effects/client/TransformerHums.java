/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.effects.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.effects.HumModel;
import net.zagdrath.arcforgetransmission.effects.Humming;
import net.zagdrath.arcforgetransmission.effects.TransmissionSounds;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;

// The transformers' hum, each client tick: the nearest humming transformers within range (at most maxInstances) each
// keep a fading loop playing (LoopSound), its volume and pitch following its load as its HUD was last sent (HumModel);
// one that leaves the nearest, goes out of range or is de-energised fades out.
final class TransformerHums {
    private static final Map<BlockPos, LoopSound> PLAYING = new HashMap<>();

    private TransformerHums() {}

    static void tick(Minecraft mc) {
        var level = mc.level;
        var player = mc.player;
        boolean on = EffectsConfig.HUM.getAsBoolean() && level != null && player != null;
        List<Humming> nearest = new ArrayList<>();
        if (on) {
            double range = EffectsConfig.HUM_RANGE.getAsDouble();
            Vec3 at = player.position();
            for (GridEndpointBlockEntity endpoint : GridEndpointBlockEntity.CLIENT_LOADED) {
                if (endpoint.getLevel() == level && !endpoint.isRemoved() && endpoint instanceof Humming hum && hum.hums()
                        && hum.humAt().distanceToSqr(at) <= range * range) {
                    nearest.add(hum);
                }
            }
            nearest.sort(Comparator.comparingDouble(h -> h.humAt().distanceToSqr(at)));
            nearest = nearest.subList(0, Math.min(nearest.size(), EffectsConfig.HUM_MAX.getAsInt()));
        }
        double full = EffectsConfig.HUM_VOLUME.getAsDouble();
        int fade = EffectsConfig.HUM_FADE_TICKS.getAsInt();
        Set<BlockPos> chosen = new HashSet<>();
        for (Humming hum : nearest) {
            BlockPos pos = ((GridEndpointBlockEntity) hum).getBlockPos();
            chosen.add(pos);
            double load = hum.humLoad();
            double volume = HumModel.volume(load, hum.humEnergized(), full, EffectsConfig.HUM_IDLE.getAsDouble());
            double pitch = HumModel.pitch(load, EffectsConfig.HUM_PITCH_RISE.getAsDouble());
            LoopSound sound = PLAYING.get(pos);
            if ((sound == null || sound.dropped()) && volume > 0) {
                sound = new LoopSound(TransmissionSounds.TRANSFORMER_HUM.get(), hum.humAt(), full, fade);
                PLAYING.put(pos, sound);
                mc.getSoundManager().play(sound);
            }
            if (sound != null) {
                sound.target(volume, pitch, hum.humAt());
            }
        }
        PLAYING.entrySet().removeIf(entry -> {
            if (!chosen.contains(entry.getKey())) {
                entry.getValue().fadeOut();
            }
            return entry.getValue().isStopped();
        });
    }

    static void clear() {
        PLAYING.clear();
    }
}
