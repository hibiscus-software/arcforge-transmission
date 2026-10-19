/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.world;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.Catenary;
import net.zagdrath.arcforgetransmission.grid.FaultEvent;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.GridFaults;
import net.zagdrath.arcforgetransmission.grid.GridNetwork;
import net.zagdrath.arcforgetransmission.grid.block.GridContent;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;
import net.zagdrath.arcforgetransmission.grid.block.PoleTransformerBlockEntity;
import net.zagdrath.arcforgetransmission.tower.TowerBlockEntity;
import net.zagdrath.arcforgetransmission.tower.TowerTier;

// Lightning on the lines. A strike (any bolt, or one a thunderstorm draws to a span, GridConfig.LIGHTNING_CHANCE) near a
// conductor (within 4 blocks of its curve) is a fault on that conductor's network, unless a shield wire runs within 6
// blocks of the strike, above the conductor (it takes the strike to ground). A fault trips every recloser and breaker
// on the network (they reclose) or, if there are none, blows its fuses; with no protection at all the struck wire burns
// out. The surge reaches every transformer on the network: one without a Surge Arrester within
// GridConfig.ARRESTER_RANGE blocks is destroyed (GridConfig.LIGHTNING_DAMAGE).
@EventBusSubscriber(modid = ArcforgeTransmission.MODID)
public final class GridLightning {
    private static final double CONDUCTOR_REACH = 4.0, SHIELD_REACH = 6.0;

    private GridLightning() {}

    @SubscribeEvent
    static void onJoin(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk() && event.getEntity() instanceof LightningBolt bolt && event.getLevel() instanceof ServerLevel level) {
            strike(level, bolt.position());
        }
    }

    // In a thunderstorm, each loaded conductor span's chance a tick of drawing a strike to its middle.
    static void storm(ServerLevel level, GridData.Dimension dimension) {
        double chance = GridConfig.LIGHTNING_CHANCE.getAsDouble();
        if (chance <= 0 || !level.isThundering()) {
            return;
        }
        // One draw for the whole grid (the chance a span, times the spans), then a span at random.
        var edges = dimension.graph().edges();
        if (edges.isEmpty() || level.getRandom().nextDouble() >= chance * edges.size()) {
            return;
        }
        GridEdge edge = new ArrayList<>(edges).get(level.getRandom().nextInt(edges.size()));
        if (exposed(edge)) {
            GridData.NodeRecord a = dimension.node(edge.a()), b = dimension.node(edge.b());
            if (a == null || b == null) {
                return;
            }
            double[] mid = Catenary.point(GridManager.array(a.at()), GridManager.array(b.at()), 0.5);
            BlockPos at = BlockPos.containing(mid[0], mid[1], mid[2]);
            if (!level.isLoaded(at) || !level.canSeeSky(at)) {
                return;
            }
            LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
            if (bolt != null) {
                bolt.snapTo(mid[0], mid[1], mid[2]);
                level.addFreshEntity(bolt);
            }
        }
    }

    // A conductor in the open air (not a jumper, a shield or underground cable).
    private static boolean exposed(GridEdge edge) {
        return edge.kind() == GridEdge.Kind.BARE || edge.kind() == GridEdge.Kind.INSULATED;
    }

    // A strike at a point: the conductor it hits (if any), unless a shield wire takes it.
    public static void strike(ServerLevel level, Vec3 at) {
        GridData.Dimension dimension = GridData.of(level);
        Hit conductor = nearest(dimension, at, true);
        if (conductor == null || conductor.distance > CONDUCTOR_REACH) {
            return;
        }
        Hit shield = nearest(dimension, at, false);
        if (shield != null && shield.distance <= SHIELD_REACH && shield.point.y >= conductor.point.y - 0.5) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, shield.point.x, shield.point.y, shield.point.z, 24, 0.3, 0.3, 0.3, 0.3);
            return;
        }
        fault(level, dimension, conductor);
    }

    private record Hit(GridEdge edge, Vec3 point, double distance) {}

    // The conductor (or shield wire) whose curve passes nearest the point.
    private static @Nullable Hit nearest(GridData.Dimension dimension, Vec3 at, boolean conductors) {
        Hit best = null;
        for (GridEdge edge : dimension.graph().edges()) {
            if (conductors ? !exposed(edge) : edge.kind() != GridEdge.Kind.SHIELD) {
                continue;
            }
            GridData.NodeRecord a = dimension.node(edge.a()), b = dimension.node(edge.b());
            if (a == null || b == null || Math.min(a.at().distanceTo(at), b.at().distanceTo(at)) > edge.length() + SHIELD_REACH) {
                continue;
            }
            double[] pa = GridManager.array(a.at()), pb = GridManager.array(b.at());
            int segments = Math.max(4, (int) Math.ceil(edge.length() * 2.0));
            for (int i = 0; i <= segments; i++) {
                double[] p = Catenary.point(pa, pb, (double) i / segments);
                Vec3 point = new Vec3(p[0], p[1], p[2]);
                double d = point.distanceTo(at);
                if (best == null || d < best.distance) {
                    best = new Hit(edge, point, d);
                }
            }
        }
        return best;
    }

    // A strike on a conductor: a flashover to ground at the wire's nearer end (GridManager.fault: the protection nearest it
    // clears it, or the wire burns if nothing does), and the surge reaches every transformer on its network: one without a
    // Surge Arrester near is destroyed, unless a Relay Panel's differential protection (a CT fitted) trips it out first.
    private static void fault(ServerLevel level, GridData.Dimension dimension, Hit hit) {
        GridNetwork network = dimension.graph().networkOf(hit.edge().a());
        if (network == null) {
            return;
        }
        GridFaults.raise(FaultEvent.edge(hit.edge().id(), FaultEvent.Cause.LIGHTNING, 0));
        GridData.NodeRecord a = dimension.node(hit.edge().a()), b = dimension.node(hit.edge().b());
        long node = a == null || b != null && b.at().distanceTo(hit.point()) < a.at().distanceTo(hit.point()) ? hit.edge().b() : hit.edge().a();
        GridManager.fault(level, node, hit.edge().id(), FaultEvent.Cause.LIGHTNING, true);
        Vec3 p = hit.point();
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 40, 0.5, 0.5, 0.5, 0.4);
        if (!GridConfig.LIGHTNING_DAMAGE.getAsBoolean()) {
            return;
        }
        long id = network.id();
        for (GridEndpointBlockEntity endpoint : new ArrayList<>(GridManager.loadedIn(level))) {
            if (endpoint.isRemoved() || !onNetwork(dimension, endpoint, id)) {
                continue;
            }
            boolean transformer = endpoint instanceof PoleTransformerBlockEntity
                    || endpoint instanceof TowerBlockEntity tower && (tower.tier().isTransformer() || tower.tier().kind() == TowerTier.Kind.SERVICE);
            if (!transformer || arrested(level, endpoint.getBlockPos())) {
                continue;
            }
            BlockPos at = endpoint.getBlockPos();
            if (endpoint instanceof TowerBlockEntity tower && tower.differentialTrip(level)) {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.getX() + 0.5, at.getY() + 1.5, at.getZ() + 0.5, 30, 0.6, 0.6, 0.6, 0.3);
                continue;
            }
            level.playSound(null, at, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.0F, 1.2F);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 20, 0.4, 0.6, 0.4, 0.02);
            level.destroyBlock(at, true);
        }
    }

    // Whether any of a block's insulators is on the network.
    private static boolean onNetwork(GridData.Dimension dimension, GridEndpointBlockEntity endpoint, long network) {
        for (long node : dimension.nodesAt(endpoint.getBlockPos())) {
            GridNetwork n = node < 0 ? null : dimension.graph().networkOf(node);
            if (n != null && n.id() == network) {
                return true;
            }
        }
        return false;
    }

    // Whether a Surge Arrester stands within range of a transformer.
    private static boolean arrested(ServerLevel level, BlockPos at) {
        int r = GridConfig.ARRESTER_RANGE.getAsInt();
        for (BlockPos pos : BlockPos.betweenClosed(at.offset(-r, -r, -r), at.offset(r, r, r))) {
            if (level.isLoaded(pos) && level.getBlockState(pos).is(GridContent.SURGE_ARRESTER.get())) {
                return true;
            }
        }
        return false;
    }
}
