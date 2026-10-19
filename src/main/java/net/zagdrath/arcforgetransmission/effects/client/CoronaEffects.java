/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.effects.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.effects.TransmissionParticles;
import net.zagdrath.arcforgetransmission.effects.TransmissionSounds;
import net.zagdrath.arcforgetransmission.grid.Catenary;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;
import net.zagdrath.arcforgetransmission.grid.world.Insulator;
import net.zagdrath.arcforgetransmission.grid.world.WireView;

// Corona on wet EHV lines, each client tick, on the client alone: while it rains (or thunders) where a span hangs, its
// energised bare conductors within range of the player give a soft crackling buzz (one fading loop, at the nearest, as
// loud as the spans near you add up to) and a faint blue-violet glow: a budget of particles a tick, more at night,
// shared over the nearest spans in proportion to their strength. A span's strength is its voltage per unit (as its HUD
// was last sent), and HV's (when turned on) a share of that. Only endpoints near the player are looked at: nothing is
// worked out for far-off lines.
final class CoronaEffects {
    // How far beyond range an endpoint may stand and still have a span reaching into it (the longest span).
    private static final double REACH = 64;
    private static final RandomSource RANDOM = RandomSource.create();
    private static LoopSound sound;

    private record Span(Vec3 from, Vec3 to, double distance, double strength) {}

    private CoronaEffects() {}

    static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        var player = mc.player;
        List<Span> spans = level != null && player != null && EffectsConfig.CORONA.getAsBoolean() && level.isRaining()
                ? spans(level, player.getEyePosition()) : List.of();
        double range = EffectsConfig.CORONA_RANGE.getAsDouble(), full = EffectsConfig.CORONA_VOLUME.getAsDouble();
        double loudness = 0;
        for (Span span : spans) {
            loudness += span.strength() * (1 - span.distance() / range);
        }
        loudness = Math.min(1, loudness);
        if (sound != null && sound.dropped()) {
            sound = null;
        }
        if (!spans.isEmpty() && loudness > 0) {
            Vec3 at = nearest(spans.get(0), player.getEyePosition());
            if (sound == null) {
                sound = new LoopSound(TransmissionSounds.CORONA_BUZZ.get(), at, full, 20);
                mc.getSoundManager().play(sound);
            }
            sound.target(full * loudness, 0.95 + 0.1 * loudness, at);
            glow(level, spans);
        } else if (sound != null) {
            sound.fadeOut();
        }
    }

    // The energised bare EHV (and HV) conductors near the player in the rain, nearest first.
    private static List<Span> spans(ClientLevel level, Vec3 eye) {
        double range = EffectsConfig.CORONA_RANGE.getAsDouble();
        boolean hv = EffectsConfig.CORONA_HV.getAsBoolean();
        double hvStrength = EffectsConfig.CORONA_HV_STRENGTH.getAsDouble();
        List<Span> out = new ArrayList<>();
        for (GridEndpointBlockEntity endpoint : GridEndpointBlockEntity.CLIENT_LOADED) {
            if (endpoint.getLevel() != level || endpoint.isRemoved() || endpoint.wireViews().isEmpty()
                    || Vec3.atCenterOf(endpoint.getBlockPos()).distanceTo(eye) > range + REACH) {
                continue;
            }
            List<Insulator> insulators = endpoint.insulators();
            for (WireView wire : endpoint.wireViews()) {
                boolean ehv = wire.tier() == VoltageTier.EHV;
                if (!wire.kind().equals("bare") || !ehv && !(hv && wire.tier() == VoltageTier.HV)) {
                    continue;
                }
                double volts = endpoint.hudVolts(nearestInsulator(insulators, wire.from()));
                if (volts <= 0.05) {
                    continue;
                }
                Span span = new Span(wire.from(), wire.to(), 0, 0);
                Vec3 near = nearest(span, eye);
                double distance = near.distanceTo(eye);
                if (distance > range || !level.isRainingAt(BlockPos.containing(near))) {
                    continue;
                }
                out.add(new Span(wire.from(), wire.to(), distance, Math.min(1, volts) * (ehv ? 1 : hvStrength)));
            }
        }
        out.sort(Comparator.comparingDouble(Span::distance));
        return out;
    }

    private static int nearestInsulator(List<Insulator> insulators, Vec3 at) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < insulators.size(); i++) {
            double d = insulators.get(i).at().distanceToSqr(at);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // The point of the span's curve nearest the eye (to a sixteenth of the span).
    private static Vec3 nearest(Span span, Vec3 eye) {
        Vec3 best = span.from();
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i <= 16; i++) {
            Vec3 p = point(span, i / 16.0);
            double d = p.distanceToSqr(eye);
            if (d < bestDistance) {
                bestDistance = d;
                best = p;
            }
        }
        return best;
    }

    private static Vec3 point(Span span, double t) {
        double[] p = Catenary.point(new double[] { span.from().x, span.from().y, span.from().z }, new double[] { span.to().x, span.to().y, span.to().z }, t);
        return new Vec3(p[0], p[1], p[2]);
    }

    // This tick's motes: the budget, less by day (sky darkness 0 full day to 11 night), over the nearest spans by
    // strength.
    private static void glow(ClientLevel level, List<Span> spans) {
        double night = level.getSkyDarken() / 11.0, daylight = EffectsConfig.CORONA_DAYLIGHT.getAsDouble();
        double visibility = daylight + (1 - daylight) * Math.max(0, Math.min(1, night));
        List<Span> shared = spans.subList(0, Math.min(spans.size(), EffectsConfig.CORONA_SPANS.getAsInt()));
        double total = 0;
        for (Span span : shared) {
            total += span.strength();
        }
        double expected = EffectsConfig.CORONA_PARTICLES.getAsInt() * visibility;
        int count = (int) expected + (RANDOM.nextDouble() < expected - (int) expected ? 1 : 0);
        for (int i = 0; i < count && total > 0; i++) {
            double pick = RANDOM.nextDouble() * total;
            Span span = shared.get(0);
            for (Span s : shared) {
                span = s;
                pick -= s.strength();
                if (pick <= 0) {
                    break;
                }
            }
            Vec3 p = point(span, RANDOM.nextDouble());
            level.addParticle(TransmissionParticles.CORONA.get(), p.x + RANDOM.nextGaussian() * 0.04, p.y + RANDOM.nextGaussian() * 0.04,
                    p.z + RANDOM.nextGaussian() * 0.04, span.strength() * visibility, 0, 0);
        }
    }

    static void clear() {
        sound = null;
    }
}
