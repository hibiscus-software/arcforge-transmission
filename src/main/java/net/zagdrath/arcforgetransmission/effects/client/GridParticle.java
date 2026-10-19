/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.effects.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;

// The grid's particles, all full-bright, their frames stepping with their age (the sprites from
// tools/transmission_particle_gen.py):
//  - CORONA: a faint mote on a wet conductor, hanging where it starts, drifting up a little; the first aux its
//    strength (0 to 1: how bright, and so how long it lasts);
//  - FLASH: an interruption's flash, still, a few ticks; the first aux its size (blocks);
//  - SPARK: a spark thrown off it, falling and slowing; the aux its velocity.
public class GridParticle extends SingleQuadParticle {
    public enum Kind {
        CORONA, FLASH, SPARK
    }

    private final SpriteSet sprites;
    private final Kind kind;

    GridParticle(ClientLevel level, double x, double y, double z, double a, double b, double c, SpriteSet sprites, Kind kind) {
        super(level, x, y, z, sprites.first());
        this.sprites = sprites;
        this.kind = kind;
        hasPhysics = false;
        switch (kind) {
            case CORONA -> {
                double strength = Math.max(0.1, Math.min(1.0, a));
                lifetime = 6 + (int) (random.nextFloat() * 10 * strength);
                quadSize = 0.035F + 0.03F * random.nextFloat();
                yd = 0.003;
                friction = 1.0F;
                alpha = (float) (0.35 + 0.5 * strength);
            }
            case FLASH -> {
                lifetime = 4;
                quadSize = (float) Math.max(0.2, a);
                friction = 1.0F;
            }
            case SPARK -> {
                lifetime = 8 + random.nextInt(10);
                quadSize = 0.04F + 0.03F * random.nextFloat();
                xd = a;
                yd = b;
                zd = c;
                gravity = 0.6F;
                friction = 0.92F;
                hasPhysics = true;
            }
        }
        setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        if (isAlive()) {
            setSpriteFromAge(sprites);
            if (kind == Kind.CORONA) {
                alpha *= 0.93F;
            }
        }
    }

    @Override
    protected SingleQuadParticle.Layer getLayer() {
        return kind == Kind.SPARK ? SingleQuadParticle.Layer.OPAQUE : SingleQuadParticle.Layer.TRANSLUCENT;
    }

    @Override
    public int getLightCoords(float partialTick) {
        return LightCoordsUtil.FULL_BRIGHT;
    }

    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;
        private final Kind kind;

        Provider(SpriteSet sprites, Kind kind) {
            this.sprites = sprites;
            this.kind = kind;
        }

        @Override
        public Particle createParticle(SimpleParticleType options, ClientLevel level, double x, double y, double z, double a, double b, double c,
                RandomSource random) {
            return new GridParticle(level, x, y, z, a, b, c, sprites, kind);
        }
    }
}
