/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.effects;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;

// The grid's particles (their sprites from tools/transmission_particle_gen.py), spawned only on the client: corona's
// glow on a wet EHV conductor, an interruption's flash and its sparks.
public final class TransmissionParticles {
    private static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(Registries.PARTICLE_TYPE, ArcforgeTransmission.MODID);

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> CORONA = PARTICLES.register("corona", () -> new Simple(false));
    // A flash always shows, however many particles there are.
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> ARC_FLASH = PARTICLES.register("arc_flash", () -> new Simple(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SPARK = PARTICLES.register("spark", () -> new Simple(false));

    private TransmissionParticles() {}

    public static void register(IEventBus modEventBus) {
        PARTICLES.register(modEventBus);
    }

    private static final class Simple extends SimpleParticleType {
        Simple(boolean overrideLimiter) {
            super(overrideLimiter);
        }
    }
}
