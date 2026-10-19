/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.grid.Catenary;
import net.zagdrath.arcforgetransmission.grid.ConnectionRules;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.GridNode;
import net.zagdrath.arcforgetransmission.grid.GridTicker;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.Port;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridContent;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;

// The grid in the world: keeps GridData's insulators in step with the blocks that have them, strings and removes wires,
// and runs each dimension's tick (GridTicker over the loaded ports), burning wires out with their drops and fires.
// Server side only.
public final class GridManager {
    // Where FE enters or leaves the grid this tick: a port (Port) and what moves its energy.
    public interface PortHandler {
        Port port(long id);

        // Takes up to fe from the source; returns what it took.
        int draw(int fe);

        // Gives up to fe to the sink; returns what it took.
        int deliver(int fe);

        // How the tick went for it.
        default void report(FlowSolver.PortStatus status, double drawn, double delivered) {}
    }

    // Something in the world that brings ports: the inductors' blocks, a transformer.
    public interface PortSource {
        void ports(ServerLevel level, GridData.Dimension dimension, List<PortHandler> out);
    }

    private static final Map<ServerLevel, Set<GridEndpointBlockEntity>> LOADED = new WeakHashMap<>();

    private GridManager() {}

    // --- Loaded endpoints ---------------------------------------------------------------------------------------------

    public static void loaded(ServerLevel level, GridEndpointBlockEntity endpoint) {
        LOADED.computeIfAbsent(level, k -> new LinkedHashSet<>()).add(endpoint);
    }

    public static void unloaded(ServerLevel level, GridEndpointBlockEntity endpoint) {
        Set<GridEndpointBlockEntity> set = LOADED.get(level);
        if (set != null) {
            set.remove(endpoint);
        }
    }

    public static void forget(ServerLevel level) {
        LOADED.remove(level);
    }

    public static Collection<GridEndpointBlockEntity> loadedIn(ServerLevel level) {
        return Collections.unmodifiableCollection(LOADED.getOrDefault(level, Set.of()));
    }

    // --- Insulators -------------------------------------------------------------------------------------------------

    // Makes the grid's record of a block's insulators match what it has (new ones get their starting labels; a label
    // already set stays), and gives the block its wires to draw.
    public static void attach(ServerLevel level, BlockPos pos, List<Insulator> insulators, GridEndpointBlockEntity endpoint) {
        GridData.Dimension dimension = GridData.of(level);
        List<Long> existing = new ArrayList<>(dimension.nodesAt(pos));
        for (int i = 0; i < insulators.size(); i++) {
            Insulator insulator = insulators.get(i);
            Long id = i < existing.size() && existing.get(i) >= 0 ? existing.get(i) : null;
            GridData.NodeRecord record = id == null ? null : dimension.node(id);
            if (record != null && record.tier() == insulator.tier()) {
                if (!record.at().equals(insulator.at()) || record.group() != insulator.group()) {
                    GridNode node = dimension.graph().node(record.id());
                    dimension.putNode(new GridData.NodeRecord(record.id(), pos, i, record.tier(), node != null ? node.phase() : record.phase(),
                            insulator.at(), insulator.group()));
                }
                continue;
            }
            if (record != null) {
                dimension.graph().removeNode(record.id());
                dimension.nodes.remove(record.id());
            }
            dimension.putNode(new GridData.NodeRecord(dimension.newId(), pos, i, insulator.tier(), insulator.phase(), insulator.at(), insulator.group()));
        }
        for (int i = insulators.size(); i < existing.size(); i++) {
            long id = existing.get(i);
            if (id >= 0) {
                dimension.graph().removeNode(id);
                dimension.nodes.remove(id);
                dimension.byPos.get(pos.asLong()).set(i, -1L);
                dimension.markDirty();
            }
        }
        endpoint.setWires(wiresOf(dimension, pos), false);
    }

    // A block with insulators is gone: its wires go too, each dropping its coil (drop) at the block, and the other ends
    // stop drawing them.
    public static void detach(ServerLevel level, BlockPos pos, boolean drop) {
        GridData.Dimension dimension = GridData.of(level);
        Map<Long, BlockPos> others = new TreeMap<>();
        Set<Long> mine = new LinkedHashSet<>(dimension.nodesAt(pos));
        for (long id : mine) {
            for (GridEdge edge : dimension.graph().edgesOf(id)) {
                GridData.NodeRecord other = dimension.node(edge.other(id));
                if (other != null && !mine.contains(other.id())) {
                    others.put(edge.id(), other.pos());
                }
            }
        }
        List<GridEdge> removed = dimension.removeAt(pos);
        if (drop) {
            for (GridEdge edge : removed) {
                Block.popResource(level, pos, new ItemStack(GridContent.coil(edge.tier())));
            }
        }
        new LinkedHashSet<>(others.values()).forEach(other -> refresh(level, dimension, other));
    }

    // The node at an insulator, or null.
    public static @Nullable GridNode node(ServerLevel level, GridData.Anchor anchor) {
        GridData.Dimension dimension = GridData.of(level);
        Long id = dimension.nodeAt(anchor);
        return id == null ? null : dimension.graph().node(id);
    }

    public static boolean isWired(ServerLevel level, GridData.Anchor anchor) {
        GridData.Dimension dimension = GridData.of(level);
        Long id = dimension.nodeAt(anchor);
        return id != null && !dimension.graph().edgesOf(id).isEmpty();
    }

    // Relabels an insulator (to the other circuit type only while it has no wires). False when it can't.
    public static boolean setPhase(ServerLevel level, GridData.Anchor anchor, Phase phase) {
        GridData.Dimension dimension = GridData.of(level);
        Long id = dimension.nodeAt(anchor);
        if (id == null || !dimension.graph().setPhase(id, phase)) {
            return false;
        }
        dimension.markDirty();
        return true;
    }

    // Swaps B and C across a block's group of three-phase insulators (ABC <-> ACB). False if the group has none.
    public static boolean swapBC(ServerLevel level, BlockPos pos, int group) {
        GridData.Dimension dimension = GridData.of(level);
        boolean any = false;
        for (long id : dimension.nodesAt(pos)) {
            GridData.NodeRecord record = dimension.node(id);
            GridNode node = dimension.graph().node(id);
            if (record == null || node == null || record.group() != group) {
                continue;
            }
            if (node.phase() == Phase.B || node.phase() == Phase.C) {
                dimension.graph().setPhase(id, node.phase() == Phase.B ? Phase.C : Phase.B);
                any = true;
            }
        }
        if (any) {
            dimension.markDirty();
        }
        return any;
    }

    // --- Wires ------------------------------------------------------------------------------------------------------

    // Strings a wire of a tier between two insulators, or says why not.
    public static @Nullable ConnectionRules.Refusal connect(ServerLevel level, GridData.Anchor from, GridData.Anchor to, VoltageTier wire) {
        GridData.Dimension dimension = GridData.of(level);
        Long a = dimension.nodeAt(from), b = dimension.nodeAt(to);
        if (a == null || b == null) {
            return ConnectionRules.Refusal.MISSING;
        }
        GridData.NodeRecord ra = dimension.node(a), rb = dimension.node(b);
        double span = ra.at().distanceTo(rb.at());
        ConnectionRules.Refusal refusal = ConnectionRules.check(dimension.graph(), a, b, wire, span, GridConfig.SETTINGS);
        if (refusal != null) {
            return refusal;
        }
        double length = Catenary.length(array(ra.at()), array(rb.at()));
        dimension.graph().addEdge(new GridEdge(dimension.newId(), a, b, wire, GridData.circuitOf(dimension.graph(), a), length));
        dimension.markDirty();
        refresh(level, dimension, ra.pos());
        refresh(level, dimension, rb.pos());
        return null;
    }

    // The wires a block draws: those strung from one of its insulators (each wire is drawn once, by its first end).
    public static List<WireView> wiresOf(GridData.Dimension dimension, BlockPos pos) {
        List<WireView> wires = new ArrayList<>();
        for (long id : dimension.nodesAt(pos)) {
            if (id < 0) {
                continue;
            }
            for (GridEdge edge : dimension.graph().edgesOf(id)) {
                if (edge.a() != id) {
                    continue;
                }
                GridData.NodeRecord a = dimension.node(edge.a()), b = dimension.node(edge.b());
                if (a != null && b != null) {
                    wires.add(new WireView(edge.id(), a.at(), b.at(), edge.tier()));
                }
            }
        }
        wires.sort(Comparator.comparingLong(WireView::edge));
        return wires;
    }

    // Sends a loaded block its wires again.
    static void refresh(ServerLevel level, GridData.Dimension dimension, BlockPos pos) {
        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof GridEndpointBlockEntity endpoint) {
            endpoint.setWires(wiresOf(dimension, pos), true);
        }
    }

    static double[] array(Vec3 v) {
        return new double[] { v.x, v.y, v.z };
    }

    // --- Ticking ----------------------------------------------------------------------------------------------------

    public static void tick(ServerLevel level) {
        GridData.Dimension dimension = GridData.of(level);
        if (dimension.graph().edges().isEmpty()) {
            return;
        }
        List<PortHandler> handlers = new ArrayList<>();
        for (GridEndpointBlockEntity endpoint : new ArrayList<>(loadedIn(level))) {
            if (!endpoint.isRemoved() && endpoint instanceof PortSource source) {
                source.ports(level, dimension, handlers);
            }
        }
        Map<Long, PortHandler> byId = new HashMap<>();
        List<Port> ports = new ArrayList<>();
        long next = 1;
        for (PortHandler handler : handlers) {
            long id = next++;
            byId.put(id, handler);
            ports.add(handler.port(id));
        }
        GridTicker.Report report = GridTicker.tick(dimension.graph(), new GridTicker.Terminals() {
            @Override
            public Collection<Port> ports() {
                return ports;
            }

            @Override
            public void commit(Map<Long, Integer> draws, Map<Long, Integer> deliveries) {
                // Draw first; if a source gave less than it offered (it changed since), every delivery shrinks to match, so
                // nothing is made.
                long planned = 0, got = 0;
                for (Map.Entry<Long, Integer> draw : draws.entrySet()) {
                    planned += draw.getValue();
                    got += byId.get(draw.getKey()).draw(draw.getValue());
                }
                double share = planned > 0 ? Math.min(1.0, (double) got / planned) : 1.0;
                for (Map.Entry<Long, Integer> delivery : deliveries.entrySet()) {
                    int fe = (int) Math.floor(delivery.getValue() * share);
                    if (fe > 0) {
                        byId.get(delivery.getKey()).deliver(fe);
                    }
                }
            }
        }, GridConfig.SETTINGS);
        FlowSolver.Result result = report.result();
        byId.forEach((id, handler) -> handler.report(result.status(id), result.drawn(id), result.delivered(id)));
        if (!report.burnt().isEmpty()) {
            burn(level, dimension, report.burnt());
        }
    }

    // Burnt-out wires: already off the graph; each drops Burnt Wire under its middle and may set a fire there.
    private static void burn(ServerLevel level, GridData.Dimension dimension, List<GridEdge> burnt) {
        Set<BlockPos> ends = new LinkedHashSet<>();
        for (GridEdge edge : burnt) {
            GridData.NodeRecord a = dimension.node(edge.a()), b = dimension.node(edge.b());
            if (a == null || b == null) {
                continue;
            }
            ends.add(a.pos());
            ends.add(b.pos());
            double[] mid = Catenary.point(array(a.at()), array(b.at()), 0.5);
            BlockPos at = BlockPos.containing(mid[0], mid[1], mid[2]);
            Block.popResource(level, at, new ItemStack(GridContent.BURNT_WIRE.get()));
            if (GridConfig.BURNOUT_FIRE.getAsBoolean() && level.getRandom().nextDouble() < GridConfig.BURNOUT_FIRE_CHANCE.getAsDouble()) {
                fireUnder(level, at);
            }
        }
        dimension.markDirty();
        ends.forEach(pos -> refresh(level, dimension, pos));
    }

    // Sets a fire on the first place it can burn at or below a point (within 24 blocks down).
    private static void fireUnder(ServerLevel level, BlockPos from) {
        BlockPos.MutableBlockPos pos = from.mutable();
        for (int i = 0; i < 24 && pos.getY() > level.getMinY(); i++, pos.move(Direction.DOWN)) {
            if (level.isLoaded(pos) && level.getBlockState(pos).isAir() && BaseFireBlock.canBePlacedAt(level, pos, Direction.UP)) {
                level.setBlock(pos, BaseFireBlock.getState(level, pos), Block.UPDATE_ALL);
                return;
            }
        }
    }
}
