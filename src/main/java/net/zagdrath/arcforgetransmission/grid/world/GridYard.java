/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.grid.block.GridContent;

// A substation yard: a fault near Station Fence is in a yard, and the ground round it rises with the fault current. Without
// Ground Grid under it, anything standing near takes a step-potential shock; with it, the ground stays even and nothing
// does.
public final class GridYard {
    private GridYard() {}

    // A fault at a node, `amps` flowing to ground: shocks what stands near it in an ungrounded yard.
    public static void stepPotential(ServerLevel level, GridData.Dimension dimension, long node, double amps) {
        GridData.NodeRecord record = dimension.node(node);
        if (record == null || amps <= 0) {
            return;
        }
        BlockPos at = BlockPos.containing(record.at());
        // The ground under the fault.
        BlockPos ground = at;
        for (int i = 0; i < 48 && ground.getY() > level.getMinY() && level.isLoaded(ground) && level.getBlockState(ground).getCollisionShape(level, ground)
                .isEmpty(); i++) {
            ground = ground.below();
        }
        if (!near(level, ground, GridConfig.YARD_RADIUS.getAsInt(), 6, true) || near(level, ground, GridConfig.GROUND_GRID_RADIUS.getAsInt(), 3, false)) {
            return;
        }
        int r = GridConfig.GROUND_GRID_RADIUS.getAsInt() + 2;
        Vec3 c = Vec3.atBottomCenterOf(ground.above());
        AABB box = new AABB(c.x - r, c.y - 1, c.z - r, c.x + r, c.y + 3, c.z + r);
        double fraction = GridConfig.STEP_FRACTION.getAsDouble();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && !e.isSpectator() && e.onGround())) {
            entity.hurtServer(level, level.damageSources().lightningBolt(), (float) (entity.getMaxHealth() * fraction));
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, entity.getX(), entity.getY() + 0.1, entity.getZ(), 10, 0.3, 0.05, 0.3, 0.1);
        }
        level.playSound(null, ground, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.BLOCKS, 0.4F, 1.6F);
    }

    // Whether Station Fence (fence) or Ground Grid (not) lies within r blocks across and `up` blocks up or down.
    private static boolean near(ServerLevel level, BlockPos at, int r, int up, boolean fence) {
        for (BlockPos pos : BlockPos.betweenClosed(at.offset(-r, -up, -r), at.offset(r, up, r))) {
            // Barbed wire stands only on a Station Fence: it counts as the fence too.
            if (level.isLoaded(pos) && (fence ? level.getBlockState(pos).is(GridContent.STATION_FENCE.get())
                    || level.getBlockState(pos).is(GridContent.STATION_FENCE_BARBED_WIRE.get()) : level.getBlockState(pos).is(GridContent.GROUND_GRID.get()))) {
                return true;
            }
        }
        return false;
    }
}
