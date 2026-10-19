/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.world;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.Catenary;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.GridNetwork;
import net.zagdrath.arcforgetransmission.grid.Port;

// Live bare wire shocks whatever touches it: a living thing whose box meets a bare wire on an energized network (one a
// source fed this tick) takes the tier's shock, the higher the voltage the worse (GridConfig.SHOCK_DAMAGE flat plus
// SHOCK_FRACTION of its max health: LV 1.5 hearts, MV a quarter of its health, HV half, EHV all of it), through armour,
// with a zap and sparks, every half second it stays in contact. Insulated LV and MV wire is safe to touch.
public final class WireShock {
    public static final ResourceKey<DamageType> ELECTROCUTION = ResourceKey.create(Registries.DAMAGE_TYPE,
            Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "electrocution"));
    // How often contact is checked (ticks), and how far from the conductor's middle counts as touching it (blocks).
    private static final int EVERY = 10;
    private static final double REACH = 0.2;

    private WireShock() {}

    static void tick(ServerLevel level, GridData.Dimension dimension, List<Port> ports, FlowSolver.Result result) {
        if (level.getGameTime() % EVERY != 0) {
            return;
        }
        // The live networks: any a source reaches, through transformers too (a grounded line is dead).
        Set<Long> energized = new HashSet<>(result.liveNetworks());
        if (energized.isEmpty()) {
            return;
        }
        Set<LivingEntity> shocked = new HashSet<>();
        for (GridEdge edge : dimension.graph().edges()) {
            double flat = GridConfig.SHOCK_DAMAGE.get(edge.tier()).getAsDouble(), fraction = GridConfig.SHOCK_FRACTION.get(edge.tier()).getAsDouble();
            GridNetwork network = dimension.graph().networkOf(edge.a());
            if (edge.kind() != GridEdge.Kind.BARE || flat <= 0 && fraction <= 0 || network == null || !energized.contains(network.id())) {
                continue;
            }
            GridData.NodeRecord a = dimension.node(edge.a()), b = dimension.node(edge.b());
            if (a == null || b == null || !level.isLoaded(net.minecraft.core.BlockPos.containing(a.at()))
                    || !level.isLoaded(net.minecraft.core.BlockPos.containing(b.at()))) {
                continue;
            }
            double[] pa = GridManager.array(a.at()), pb = GridManager.array(b.at());
            double[] mid = Catenary.point(pa, pb, 0.5);
            AABB around = new AABB(a.at(), b.at()).minmax(new AABB(mid[0], mid[1], mid[2], mid[0], mid[1], mid[2])).inflate(REACH + 0.5);
            List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class, around, entity -> entity.isAlive() && !entity.isSpectator());
            if (near.isEmpty()) {
                continue;
            }
            int segments = Math.max(4, (int) Math.ceil(edge.length() * 2.0));
            for (LivingEntity entity : near) {
                if (shocked.contains(entity)) {
                    continue;
                }
                AABB box = entity.getBoundingBox().inflate(REACH);
                double[] prev = pa;
                for (int i = 1; i <= segments; i++) {
                    double[] next = Catenary.point(pa, pb, (double) i / segments);
                    Vec3 p = new Vec3(prev[0], prev[1], prev[2]), q = new Vec3(next[0], next[1], next[2]);
                    if (box.contains(p) || box.clip(p, q).isPresent()) {
                        shock(level, entity, (float) (flat + fraction * entity.getMaxHealth()), box.getCenter());
                        shocked.add(entity);
                        break;
                    }
                    prev = next;
                }
            }
        }
    }

    private static void shock(ServerLevel level, LivingEntity entity, float damage, Vec3 at) {
        DamageSource source = new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ELECTROCUTION));
        entity.hurtServer(level, source, damage);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.BLOCKS, 0.35F, 1.9F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.REDSTONE_TORCH_BURNOUT, SoundSource.BLOCKS, 0.6F, 1.4F);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 12, 0.25, 0.4, 0.25, 0.2);
    }
}
