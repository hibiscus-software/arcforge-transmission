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
import net.zagdrath.arcforgetransmission.grid.block.PinInsulatorBlockEntity;
import net.zagdrath.arcforgetransmission.grid.block.WallBushingBlock;

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

    // Something in the world that brings ports: the pin insulators' blocks, a transformer.
    public interface PortSource {
        void ports(ServerLevel level, GridData.Dimension dimension, List<PortHandler> out);
    }

    private static final Map<ServerLevel, Set<GridEndpointBlockEntity>> LOADED = new WeakHashMap<>();
    // The last tick's current on every wire, per level (for the HUD between ticks).
    private static final Map<ServerLevel, Map<Long, Double>> LAST_AMPS = new WeakHashMap<>();

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
        LAST_AMPS.remove(level);
    }

    // Tells a block's client what its insulators' HUD shows (GridEndpointBlockEntity.refreshDisplay).
    public static void display(ServerLevel level, GridEndpointBlockEntity endpoint, boolean force) {
        endpoint.refreshDisplay(level, LAST_AMPS.getOrDefault(level, Map.of()), force);
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
                dropWires(level, dimension, pos, record.id());
                dimension.nodes.remove(record.id());
            }
            dimension.putNode(new GridData.NodeRecord(dimension.newId(), pos, i, insulator.tier(), insulator.phase(), insulator.at(), insulator.group()));
        }
        for (int i = insulators.size(); i < existing.size(); i++) {
            long id = existing.get(i);
            if (id >= 0) {
                dropWires(level, dimension, pos, id);
                dimension.nodes.remove(id);
                dimension.byPos.get(pos.asLong()).set(i, -1L);
                dimension.markDirty();
            }
        }
        // Only told again when they've changed (a far end went while this was unloaded): its chunk's data went out with
        // what it had saved.
        List<WireView> wires = wiresOf(dimension, pos);
        if (!wires.equals(endpoint.wires())) {
            endpoint.setWires(wires, true);
        }
    }

    // A block's insulators have moved (a transformer turned): the grid takes their new places, each wire on them is
    // re-hung (its length, so its loss, worked out again) and both ends redraw it.
    public static void moved(ServerLevel level, BlockPos pos, GridEndpointBlockEntity endpoint) {
        GridData.Dimension dimension = GridData.of(level);
        attach(level, pos, endpoint.insulators(), endpoint);
        Set<BlockPos> ends = new LinkedHashSet<>();
        ends.add(pos);
        for (long id : new ArrayList<>(dimension.nodesAt(pos))) {
            if (id < 0) {
                continue;
            }
            for (GridEdge edge : new ArrayList<>(dimension.graph().edgesOf(id))) {
                GridData.NodeRecord a = dimension.node(edge.a()), b = dimension.node(edge.b());
                if (a == null || b == null) {
                    continue;
                }
                ends.add(a.pos());
                ends.add(b.pos());
                double length = edge.isJumper() ? 0.0 : Catenary.length(array(a.at()), array(b.at()));
                if (Math.abs(length - edge.length()) > 1.0E-6) {
                    dimension.graph().removeEdge(edge.id());
                    dimension.graph().addEdge(new GridEdge(edge.id(), edge.a(), edge.b(), edge.tier(), edge.circuit(), length, edge.kind()));
                }
            }
        }
        dimension.markDirty();
        ends.forEach(end -> refresh(level, dimension, end));
    }

    // Takes a node out of the graph, its wires dropping their coils at pos and the far ends told.
    private static void dropWires(ServerLevel level, GridData.Dimension dimension, BlockPos pos, long id) {
        Set<BlockPos> others = new LinkedHashSet<>();
        for (GridEdge edge : dimension.graph().edgesOf(id)) {
            GridData.NodeRecord other = dimension.node(edge.other(id));
            if (other != null && !other.pos().equals(pos)) {
                others.add(other.pos());
            }
        }
        for (GridEdge edge : dimension.graph().removeNode(id)) {
            Block.popResource(level, pos, GridContent.coilFor(edge));
        }
        dimension.markDirty();
        others.forEach(other -> refresh(level, dimension, other));
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
                Block.popResource(level, pos, GridContent.coilFor(edge));
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

    // Whether an insulator has a wire (a Wall Bushing's jumpers don't count).
    public static boolean isWired(ServerLevel level, GridData.Anchor anchor) {
        GridData.Dimension dimension = GridData.of(level);
        Long id = dimension.nodeAt(anchor);
        return id != null && dimension.graph().edgesOf(id).stream().anyMatch(edge -> !edge.isJumper());
    }

    // Relabels an insulator (to the other circuit type only while it has no wires; its jumpers through a Wall Bushing
    // come off first, and the bushing's rejoin puts back what still matches). False when it can't.
    public static boolean setPhase(ServerLevel level, GridData.Anchor anchor, Phase phase) {
        GridData.Dimension dimension = GridData.of(level);
        Long id = dimension.nodeAt(anchor);
        if (id == null) {
            return false;
        }
        GridNode node = dimension.graph().node(id);
        if (node != null && node.circuit() != phase.circuit() && !isWired(level, anchor)) {
            for (GridEdge edge : new ArrayList<>(dimension.graph().edgesOf(id))) {
                dimension.graph().removeEdge(edge.id());
            }
        }
        if (!dimension.graph().setPhase(id, phase)) {
            return false;
        }
        dimension.markDirty();
        return true;
    }

    // Joins the Pin Insulators on a Wall Bushing through it: those of one tier and one label each get a jumper to the
    // first of them (no length, never drawn or dropped). Run whenever one is mounted, comes off or is relabelled.
    public static void rejoin(ServerLevel level, BlockPos bushing) {
        GridData.Dimension dimension = GridData.of(level);
        List<Long> nodes = new ArrayList<>();
        if (level.getBlockState(bushing).getBlock() instanceof WallBushingBlock) {
            for (Direction side : Direction.values()) {
                BlockPos at = bushing.relative(side);
                if (level.isLoaded(at) && level.getBlockEntity(at) instanceof PinInsulatorBlockEntity insulator && !insulator.isRemoved()
                        && insulator.mountPos().equals(bushing)) {
                    Long id = dimension.nodeAt(new GridData.Anchor(at, 0));
                    if (id != null && id >= 0 && dimension.graph().node(id) != null) {
                        nodes.add(id);
                    }
                }
            }
        }
        boolean changed = false;
        for (long id : nodes) {
            for (GridEdge edge : new ArrayList<>(dimension.graph().edgesOf(id))) {
                if (edge.isJumper()) {
                    dimension.graph().removeEdge(edge.id());
                    changed = true;
                }
            }
        }
        Map<String, Long> first = new HashMap<>();
        for (long id : nodes) {
            GridNode node = dimension.graph().node(id);
            Long head = first.putIfAbsent(node.tier().id() + ":" + node.phase().id(), id);
            if (head != null) {
                dimension.graph().addEdge(new GridEdge(dimension.newId(), head, id, node.tier(), node.circuit(), 0.0, GridEdge.Kind.JUMPER));
                changed = true;
            }
        }
        if (changed) {
            dimension.markDirty();
        }
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

    // Strings a bare wire of a tier between two insulators, or says why not.
    public static ConnectionRules.@Nullable Refusal connect(ServerLevel level, GridData.Anchor from, GridData.Anchor to, VoltageTier wire) {
        return connect(level, from, to, wire, GridEdge.Kind.BARE);
    }

    // Strings a wire of a tier and kind (bare or insulated) between two insulators, or says why not.
    public static ConnectionRules.@Nullable Refusal connect(ServerLevel level, GridData.Anchor from, GridData.Anchor to, VoltageTier wire,
            GridEdge.Kind kind) {
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
        dimension.graph().addEdge(new GridEdge(dimension.newId(), a, b, wire, GridData.circuitOf(dimension.graph(), a), length, kind));
        dimension.markDirty();
        refresh(level, dimension, ra.pos());
        refresh(level, dimension, rb.pos());
        return null;
    }

    // A wire a ray passes near: its edge, the point on it nearest the ray, and how far along the ray that is.
    public record WireHit(long edge, Vec3 at, double along) {}

    // Cuts one wire (the Wire Cutters): off the graph, its coil dropped at `drop`, both ends told. False if it's gone.
    public static boolean cut(ServerLevel level, long edgeId, Vec3 drop) {
        GridData.Dimension dimension = GridData.of(level);
        GridEdge existing = dimension.graph().edge(edgeId);
        if (existing == null || existing.isJumper()) {
            return false;
        }
        GridEdge edge = dimension.graph().removeEdge(edgeId);
        Block.popResource(level, BlockPos.containing(drop), GridContent.coilFor(edge));
        dimension.markDirty();
        for (long node : new long[] { edge.a(), edge.b() }) {
            GridData.NodeRecord record = dimension.node(node);
            if (record != null) {
                refresh(level, dimension, record.pos());
            }
        }
        return true;
    }

    // The wires on an insulator.
    public static List<Long> wiresAt(ServerLevel level, GridData.Anchor anchor) {
        GridData.Dimension dimension = GridData.of(level);
        Long id = dimension.nodeAt(anchor);
        List<Long> out = new ArrayList<>();
        if (id != null) {
            dimension.graph().edgesOf(id).stream().filter(edge -> !edge.isJumper()).forEach(edge -> out.add(edge.id()));
        }
        return out;
    }

    // The wire whose curve passes nearest a ray (from `from` along the unit `dir`, up to `reach`), within `radius` of it,
    // the first along the ray when two are as near; null if none does.
    public static @Nullable WireHit wireAlong(ServerLevel level, Vec3 from, Vec3 dir, double reach, double radius) {
        GridData.Dimension dimension = GridData.of(level);
        WireHit best = null;
        double bestDistance = radius;
        double[] o = array(from), d = array(dir.scale(reach));
        for (GridEdge edge : dimension.graph().edges()) {
            if (edge.isJumper()) {
                continue;
            }
            GridData.NodeRecord a = dimension.node(edge.a()), b = dimension.node(edge.b());
            if (a == null || b == null || a.at().distanceTo(from) > reach + edge.length() && b.at().distanceTo(from) > reach + edge.length()) {
                continue;
            }
            double[] pa = array(a.at()), pb = array(b.at());
            int segments = Math.max(4, (int) Math.ceil(edge.length() * 2.0));
            double[] prev = Catenary.point(pa, pb, 0.0);
            for (int i = 1; i <= segments; i++) {
                double[] next = Catenary.point(pa, pb, (double) i / segments);
                double[] st = closest(o, d, prev, next);
                double[] onRay = { o[0] + d[0] * st[0], o[1] + d[1] * st[0], o[2] + d[2] * st[0] };
                double[] onWire = { prev[0] + (next[0] - prev[0]) * st[1], prev[1] + (next[1] - prev[1]) * st[1], prev[2] + (next[2] - prev[2]) * st[1] };
                double dx = onRay[0] - onWire[0], dy = onRay[1] - onWire[1], dz = onRay[2] - onWire[2];
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double along = st[0] * reach;
                if (distance < bestDistance - 1.0E-6 || best != null && Math.abs(distance - bestDistance) <= 1.0E-6 && along < best.along()) {
                    bestDistance = distance;
                    best = new WireHit(edge.id(), new Vec3(onWire[0], onWire[1], onWire[2]), along);
                }
                prev = next;
            }
        }
        return best;
    }

    // The closest points of segment o..o+d and segment p..q: {s along the first, t along the second}, each in [0, 1].
    private static double[] closest(double[] o, double[] d, double[] p, double[] q) {
        double[] e = { q[0] - p[0], q[1] - p[1], q[2] - p[2] }, r = { o[0] - p[0], o[1] - p[1], o[2] - p[2] };
        double a = dot(d, d), b = dot(d, e), c = dot(e, e), f = dot(d, r), g = dot(e, r);
        double denom = a * c - b * b;
        double s = denom > 1.0E-12 ? clamp((b * g - c * f) / denom) : 0.0;
        double t = c > 1.0E-12 ? clamp((b * s + g) / c) : 0.0;
        s = a > 1.0E-12 ? clamp((b * t - f) / a) : 0.0;
        return new double[] { s, t };
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // The wires a block draws: those strung from one of its insulators (each wire is drawn once, by its first end).
    public static List<WireView> wiresOf(GridData.Dimension dimension, BlockPos pos) {
        List<WireView> wires = new ArrayList<>();
        for (long id : dimension.nodesAt(pos)) {
            if (id < 0) {
                continue;
            }
            for (GridEdge edge : dimension.graph().edgesOf(id)) {
                if (edge.a() != id || edge.isJumper()) {
                    continue;
                }
                GridData.NodeRecord a = dimension.node(edge.a()), b = dimension.node(edge.b());
                if (a != null && b != null) {
                    wires.add(new WireView(edge.id(), a.at(), b.at(), edge.tier(), edge.kind() == GridEdge.Kind.INSULATED));
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
            display(level, endpoint, true);
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
                // Draw first; if a source gave less than it offered (it changed since), the deliveries in its part of
                // the grid (the ports joined through any network) shrink to match, so nothing is made.
                Map<Long, Long> group = groups(dimension, ports);
                Map<Long, long[]> plannedGot = new HashMap<>();
                for (Map.Entry<Long, Integer> draw : draws.entrySet()) {
                    long[] pg = plannedGot.computeIfAbsent(group.get(draw.getKey()), k -> new long[2]);
                    pg[0] += draw.getValue();
                    pg[1] += byId.get(draw.getKey()).draw(draw.getValue());
                }
                for (Map.Entry<Long, Integer> delivery : deliveries.entrySet()) {
                    long[] pg = plannedGot.get(group.get(delivery.getKey()));
                    double share = pg == null || pg[0] <= 0 ? 0.0 : Math.min(1.0, (double) pg[1] / pg[0]);
                    int fe = (int) Math.floor(delivery.getValue() * share);
                    if (fe > 0) {
                        byId.get(delivery.getKey()).deliver(fe);
                    }
                }
            }
        }, GridConfig.SETTINGS);
        FlowSolver.Result result = report.result();
        byId.forEach((id, handler) -> handler.report(result.status(id), result.drawn(id), result.delivered(id)));
        WireShock.tick(level, dimension, ports, result);
        LAST_AMPS.put(level, result.edgeAmps());
        for (GridEndpointBlockEntity endpoint : new ArrayList<>(loadedIn(level))) {
            if (!endpoint.isRemoved()) {
                display(level, endpoint, false);
            }
        }
        if (!report.burnt().isEmpty()) {
            burn(level, dimension, report.burnt());
        }
    }

    // Ports joined through any network share a group (union-find over their nodes' networks): a port's group id.
    private static Map<Long, Long> groups(GridData.Dimension dimension, List<Port> ports) {
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, Long> byNetwork = new HashMap<>();
        for (Port port : ports) {
            parent.putIfAbsent(port.id(), port.id());
            for (long node : port.nodes()) {
                var network = dimension.graph().networkOf(node);
                if (network == null) {
                    continue;
                }
                Long other = byNetwork.putIfAbsent(network.id(), port.id());
                if (other != null) {
                    parent.put(find(parent, port.id()), find(parent, other));
                }
            }
        }
        Map<Long, Long> out = new HashMap<>();
        for (Port port : ports) {
            out.put(port.id(), find(parent, port.id()));
        }
        return out;
    }

    private static long find(Map<Long, Long> parent, long id) {
        long root = id;
        while (parent.get(root) != root) {
            root = parent.get(root);
        }
        parent.put(id, root);
        return root;
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
