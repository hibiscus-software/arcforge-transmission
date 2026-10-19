/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.effects.client;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.effects.ArcEvent;
import net.zagdrath.arcforgetransmission.effects.TransmissionParticles;
import net.zagdrath.arcforgetransmission.effects.TransmissionSounds;

// An interruption's arc, drawn on the client from the server's ArcEvent: a bright full-bright flash, a spray of sparks
// and a sharp crack (a fuse adds its pop, a breaker its clunk), all as big and as loud as the fault was (its current over
// the rating, a log scale) and bigger on higher tiers.
public final class ArcEffects {
    private static final RandomSource RANDOM = RandomSource.create();

    private ArcEffects() {}

    // How big an arc is: 0.5 at its rating, rising slowly with the current (about 2 at twenty times), times its tier's
    // scale; never more than 3.
    public static double size(ArcEvent arc) {
        double tier = switch (arc.tier()) {
            case LV -> 0.6;
            case MV -> 0.8;
            case HV -> 1.0;
            case EHV -> 1.25;
        };
        return Math.min(3.0, (0.5 + Math.log1p(Math.max(0, arc.severity())) / Math.log(4)) * tier);
    }

    public static void play(ArcEvent arc) {
        var level = Minecraft.getInstance().level;
        if (level == null || !EffectsConfig.ARCS.getAsBoolean()) {
            return;
        }
        double k = size(arc);
        Vec3 at = arc.at();
        level.addParticle(TransmissionParticles.ARC_FLASH.get(), at.x, at.y, at.z, 0.6 * k, 0, 0);
        for (int i = 0; i < 2; i++) {
            level.addParticle(TransmissionParticles.ARC_FLASH.get(), at.x + RANDOM.nextGaussian() * 0.2 * k, at.y + RANDOM.nextGaussian() * 0.2 * k,
                    at.z + RANDOM.nextGaussian() * 0.2 * k, 0.3 * k, 0, 0);
        }
        int sparks = (int) Math.round((10 + 18 * k) * EffectsConfig.ARC_PARTICLES.getAsDouble());
        for (int i = 0; i < sparks; i++) {
            double speed = (0.06 + 0.18 * RANDOM.nextDouble()) * k;
            Vec3 dir = new Vec3(RANDOM.nextGaussian(), RANDOM.nextGaussian() * 0.6 + 0.4, RANDOM.nextGaussian()).normalize().scale(speed);
            level.addParticle(TransmissionParticles.SPARK.get(), at.x, at.y, at.z, dir.x, dir.y, dir.z);
        }
        float volume = (float) (EffectsConfig.ARC_VOLUME.getAsDouble() * Math.min(1.5, 0.5 + 0.35 * k));
        float pitch = (float) Math.max(0.6, Math.min(1.3, 1.15 - 0.12 * k));
        switch (arc.kind()) {
            case FUSE -> {
                sound(at, TransmissionSounds.FUSE_POP.get(), volume, pitch + 0.1F);
                sound(at, TransmissionSounds.ARC_CRACK.get(), volume * 0.5F, pitch + 0.15F);
            }
            case BREAKER -> {
                sound(at, TransmissionSounds.BREAKER_CLUNK.get(), volume, 1.0F);
                sound(at, TransmissionSounds.ARC_CRACK.get(), volume, pitch);
            }
            case DISCONNECT -> sound(at, TransmissionSounds.ARC_CRACK.get(), volume, pitch - 0.1F);
            case FLASHOVER -> sound(at, TransmissionSounds.ARC_CRACK.get(), volume * 1.2F, pitch - 0.05F);
        }
    }

    private static void sound(Vec3 at, SoundEvent event, float volume, float pitch) {
        var level = Minecraft.getInstance().level;
        if (level != null) {
            level.playLocalSound(at.x, at.y, at.z, event, SoundSource.BLOCKS, volume, pitch, false);
        }
    }
}
