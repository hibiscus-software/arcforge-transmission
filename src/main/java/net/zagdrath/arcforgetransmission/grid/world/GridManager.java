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
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import net.zagdrath.arcforgetransmission.grid.Catenary;
import net.zagdrath.arcforgetransmission.grid.ConnectionRules;
import net.zagdrath.arcforgetransmission.grid.Coupling;
import net.zagdrath.arcforgetransmission.grid.EnergyCommit;
import net.zagdrath.arcforgetransmission.grid.FaultEvent;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.GridFaults;
import net.zagdrath.arcforgetransmission.grid.GridGraph;
import net.zagdrath.arcforgetransmission.grid.GridNetwork;
import net.zagdrath.arcforgetransmission.grid.GridNode;
import net.zagdrath.arcforgetransmission.grid.Shunt;
import net.zagdrath.arcforgetransmission.grid.GridTicker;
import net.zagdrath.arcforgetransmission.grid.InsulatorMatch;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.Port;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.block.GridContent;
import net.zagdrath.arcforgetransmission.grid.block.GridEndpointBlockEntity;
import net.zagdrath.arcforgetransmission.grid.block.GridInteractions;
import net.zagdrath.arcforgetransmission.grid.block.PinInsulatorBlockEntity;
import net.zagdrath.arcforgetransmission.grid.block.WallBushingBlock;

// The grid in the world: keeps GridData's insulators in step with the blocks that have them, strings and removes wires,
// and runs each dimension's tick (GridTicker over the loaded ports), burning wires out with their drops and fires.
// Server side only.
public final class GridManager {
    // Where FE enters or leaves the grid this tick: a port (Port) and what moves its energy.
    public interface PortHandler {
        Port port(long id);

        // Takes up to fe from the source within tx (kept only if tx commits); returns what it took.
        int draw(int fe, TransactionContext tx);

        // Gives up to fe to the sink within tx (kept only if tx commits); returns what it took.
        int deliver(int fe, TransactionContext tx);

        // What was moved for it this tick, once committed.
        default void moved(int drawn, int delivered) {}

        // How the tick went for it.
        default void report(FlowSolver.PortStatus status, double drawn, double delivered) {}
    }

    // Something in the world that takes part in the grid's tick: brings ports (the pin insulators' blocks, a unit
    // transformer), transformers (couplings), capacitor banks and reactors (shunts), grounded nodes (a closed ground
    // switch) and protective links (breakers, reclosers, fuses); and, once the tick is solved, reacts to it (protection,
    // tap changers, sectionalizers, transfer switches).
    public interface PortSource {
        void ports(ServerLevel level, GridData.Dimension dimension, List<PortHandler> out);

        default void couplings(ServerLevel level, GridData.Dimension dimension, List<Coupling> out) {}

        default void shunts(ServerLevel level, GridData.Dimension dimension, List<Shunt> out) {}

        default void grounded(ServerLevel level, GridData.Dimension dimension, Set<Long> out) {}

        default void protective(ServerLevel level, GridData.Dimension dimension, Map<Long, FlowSolver.Protective> out) {}

        default void afterTick(ServerLevel level, GridData.Dimension dimension, TickView view) {}
    }

    // What a tick came to, for the blocks reacting to it (and between ticks, for interlocks and the HUD): the solve, the
    // fault current on each wire and link (and how many protective devices lie between each and its fault), each
    // network's voltage (the average of its nodes' that power passed; per unit) and the grounded networks.
    public record TickView(FlowSolver.Result result, Map<Long, Double> faultAmps, Map<Long, FlowSolver.Beyond> faultBeyond, Map<Long, Double> networkVolts,
            Set<Long> groundedNetworks) {
        public static final TickView EMPTY = new TickView(FlowSolver.Result.EMPTY, Map.of(), Map.of(), Map.of(), Set.of());

        // The current through a wire or link: the load's, or a fault's if more.
        public double amps(long edge) {
            return Math.max(result.amps(edge), faultAmps.getOrDefault(edge, 0.0));
        }

        public boolean faulted(long edge) {
            return faultAmps.getOrDefault(edge, 0.0) > 0;
        }

        // Protective devices between a link and the trouble through it (its fault's, else its loads').
        public FlowSolver.Beyond beyond(long edge) {
            return faulted(edge) ? faultBeyond.getOrDefault(edge, FlowSolver.Beyond.NONE) : result.beyond(edge, FlowSolver.Beyond.NONE);
        }

        public boolean live(GridGraph graph, long node) {
            GridNetwork network = graph.networkOf(node);
            return network != null && result.liveNetworks().contains(network.id());
        }

        // Live from its own side: fed without counting a transfer switch's link (what an ATS watches each feeder by).
        public boolean sourceLive(GridGraph graph, long node) {
            GridNetwork network = graph.networkOf(node);
            return network != null && result.sourceLive().contains(network.id());
        }

        public boolean grounded(GridGraph graph, long node) {
            GridNetwork network = graph.networkOf(node);
            return network != null && groundedNetworks.contains(network.id());
        }

        // A node's voltage (per unit): its own if power passed it, else its network's, else nominal if live, else 0.
        public double volts(GridGraph graph, long node) {
            Double own = result.nodeVolts().get(node);
            if (own != null) {
                return own;
            }
            GridNetwork network = graph.networkOf(node);
            if (network == null || !result.liveNetworks().contains(network.id())) {
                return 0.0;
            }
            return networkVolts.getOrDefault(network.id(), 1.0);
        }
    }

    // An active ground fault: where (a node, and the wire it's on, -1 for none), what, since when, and whether it clears
    // once its line goes dead (a flashover) or stays (a grounded line).
    private record Fault(long node, long edge, FaultEvent.Cause cause, long since, boolean temporary) {}

    private static final Map<ServerLevel, Set<GridEndpointBlockEntity>> LOADED = new WeakHashMap<>();
    // The last tick's current and loss on every wire, per level (for the HUD, devices and the Clamp Meter between ticks).
    private static final Map<ServerLevel, Map<Long, Double>> LAST_AMPS = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<Long, Double>> LAST_LOSS = new WeakHashMap<>();
    private static final Map<ServerLevel, TickView> LAST_VIEW = new WeakHashMap<>();
    private static final Map<ServerLevel, FlowSolver.State> STATES = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<Long, Fault>> FAULTS = new WeakHashMap<>();
    private static final Map<ServerLevel, Set<Long>> LAST_PHASE_LOSS = new WeakHashMap<>();

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
        LAST_LOSS.remove(level);
        LAST_VIEW.remove(level);
        STATES.remove(level);
        FAULTS.remove(level);
        LAST_PHASE_LOSS.remove(level);
    }

    // The last tick's view (TickView.EMPTY before the first).
    public static TickView view(ServerLevel level) {
        return LAST_VIEW.getOrDefault(level, TickView.EMPTY);
    }

    // A conductor to ground at a node (on a wire, or -1): a flashover (temporary: it clears when its line goes dead, or
    // burns the wire if nothing clears it) or a lasting fault. Protection sees its current from the next tick.
    public static void fault(ServerLevel level, long node, long edge, FaultEvent.Cause cause, boolean temporary) {
        FAULTS.computeIfAbsent(level, k -> new TreeMap<>()).putIfAbsent(node, new Fault(node, edge, cause, level.getGameTime(), temporary));
    }

    // Sets a regulator's boost on its links (jumpers between pairs of its insulators, source first), per unit.
    public static void boost(ServerLevel level, BlockPos pos, List<int[]> pairs, double boost) {
        GridData.Dimension dimension = GridData.of(level);
        for (int[] pair : pairs) {
            Long a = dimension.nodeAt(new GridData.Anchor(pos, pair[0])), b = dimension.nodeAt(new GridData.Anchor(pos, pair[1]));
            if (a == null || b == null) {
                continue;
            }
            for (GridEdge edge : dimension.graph().edgesOf(a)) {
                if (edge.isJumper() && edge.other(a) == b) {
                    edge.setBoost(edge.a() == a ? boost : 1.0 / (1.0 + boost) - 1.0);
                }
            }
        }
    }

    // Joins (or parts) two blocks' insulators with a jumper (bus sections in a row). True if it changed.
    public static boolean join(ServerLevel level, GridData.Anchor from, GridData.Anchor to, boolean present) {
        GridData.Dimension dimension = GridData.of(level);
        Long a = dimension.nodeAt(from), b = dimension.nodeAt(to);
        if (a == null || b == null || a < 0 || b < 0 || dimension.graph().node(a) == null || dimension.graph().node(b) == null) {
            return false;
        }
        GridEdge existing = null;
        for (GridEdge edge : dimension.graph().edgesOf(a)) {
            if (edge.isJumper() && edge.other(a) == b) {
                existing = edge;
            }
        }
        if (present && existing == null) {
            GridNode node = dimension.graph().node(a);
            if (node.tier() != dimension.graph().node(b).tier()) {
                return false;
            }
            dimension.graph().addEdge(new GridEdge(dimension.newId(), a, b, node.tier(), node.circuit(), 0.0, GridEdge.Kind.JUMPER));
        } else if (!present && existing != null) {
            dimension.graph().removeEdge(existing.id());
        } else {
            return false;
        }
        dimension.markDirty();
        return true;
    }

    // The last tick's loss on every wire of a level (FE/t, by edge id).
    public static Map<Long, Double> lastLoss(ServerLevel level) {
        return LAST_LOSS.getOrDefault(level, Map.of());
    }

    // A wire burns out on the spot (lightning on an unprotected line): it's gone, Burnt Wire drops under its middle.
    public static void burnOut(ServerLevel level, GridData.Dimension dimension, GridEdge edge) {
        if (dimension.graph().removeEdge(edge.id()) != null) {
            burn(level, dimension, List.of(edge));
        }
    }

    // The last tick's current on every wire of a level (A, by edge id).
    public static Map<Long, Double> lastAmps(ServerLevel level) {
        return LAST_AMPS.getOrDefault(level, Map.of());
    }

    // Makes a device's links (jumpers between pairs of its insulators, by index) present or absent. True if any changed.
    public static boolean link(ServerLevel level, BlockPos pos, List<int[]> pairs, boolean present) {
        GridData.Dimension dimension = GridData.of(level);
        boolean changed = false;
        for (int[] pair : pairs) {
            Long a = dimension.nodeAt(new GridData.Anchor(pos, pair[0])), b = dimension.nodeAt(new GridData.Anchor(pos, pair[1]));
            if (a == null || b == null || a < 0 || b < 0 || dimension.graph().node(a) == null || dimension.graph().node(b) == null) {
                continue;
            }
            GridEdge existing = null;
            for (GridEdge edge : dimension.graph().edgesOf(a)) {
                if (edge.isJumper() && edge.other(a) == b) {
                    existing = edge;
                }
            }
            if (present && existing == null) {
                GridNode node = dimension.graph().node(a);
                dimension.graph().addEdge(new GridEdge(dimension.newId(), a, b, node.tier(), node.circuit(), 0.0, GridEdge.Kind.JUMPER));
                changed = true;
            } else if (!present && existing != null) {
                dimension.graph().removeEdge(existing.id());
                changed = true;
            }
        }
        if (changed) {
            dimension.markDirty();
        }
        return changed;
    }

    // Tells a block's client what its insulators' HUD shows (GridEndpointBlockEntity.refreshDisplay).
    public static void display(ServerLevel level, GridEndpointBlockEntity endpoint, boolean force) {
        endpoint.refreshDisplay(level, LAST_AMPS.getOrDefault(level, Map.of()), view(level), force);
    }

    // Blocks a creative player is breaking this tick: their wires' coils and fittings don't drop.
    private static final Map<ServerLevel, Set<Long>> CREATIVE_BREAKS = new WeakHashMap<>();

    public static void breaking(net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.world.entity.player.Player player) {
        if (level instanceof ServerLevel server && player.hasInfiniteMaterials()) {
            CREATIVE_BREAKS.computeIfAbsent(server, k -> new java.util.HashSet<>()).add(pos.asLong());
        }
    }

    // Whether what a block leaves behind drops (not when a creative player broke it).
    public static boolean dropsAt(ServerLevel level, BlockPos pos) {
        Set<Long> creative = CREATIVE_BREAKS.get(level);
        return creative == null || !creative.contains(pos.asLong());
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
        // Each insulator's saved node, by its key (or its place, in a save from before keys: InsulatorMatch).
        List<String> savedKeys = new ArrayList<>(existing.size());
        for (long id : existing) {
            GridData.NodeRecord record = id < 0 ? null : dimension.node(id);
            savedKeys.add(record == null ? null : record.key());
        }
        List<String> keys = new ArrayList<>(insulators.size());
        insulators.forEach(insulator -> keys.add(insulator.key()));
        int[] match = InsulatorMatch.match(savedKeys, keys);
        boolean[] kept = new boolean[existing.size()];
        List<GridData.NodeRecord> next = new ArrayList<>(insulators.size());
        for (int i = 0; i < insulators.size(); i++) {
            Insulator insulator = insulators.get(i);
            GridData.NodeRecord record = match[i] < 0 ? null : dimension.node(existing.get(match[i]));
            if (record != null && record.tier() == insulator.tier()) {
                kept[match[i]] = true;
                GridNode node = dimension.graph().node(record.id());
                next.add(new GridData.NodeRecord(record.id(), pos, i, record.tier(), node != null ? node.phase() : record.phase(), insulator.at(),
                        insulator.group(), insulator.key()));
            } else {
                next.add(new GridData.NodeRecord(dimension.newId(), pos, i, insulator.tier(), insulator.phase(), insulator.at(), insulator.group(),
                        insulator.key()));
            }
        }
        // Nodes no insulator is any more: their wires drop.
        for (int i = 0; i < existing.size(); i++) {
            long id = existing.get(i);
            if (id >= 0 && !kept[i]) {
                dropWires(level, dimension, pos, id);
                dimension.nodes.remove(id);
                dimension.graph().removeNode(id);
            }
        }
        boolean changed = existing.size() != next.size();
        for (int i = 0; i < next.size() && !changed; i++) {
            GridData.NodeRecord was = dimension.node(existing.get(i));
            changed = !next.get(i).equals(was);
        }
        if (changed) {
            dimension.byPos.remove(pos.asLong());
            next.forEach(dimension::putNode);
            dimension.markDirty();
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

    // Whether a player may cut a wire: may change things at both its ends (GridInteractions.mayChange).
    public static boolean mayCut(ServerLevel level, net.minecraft.world.entity.player.Player player, long edgeId) {
        GridData.Dimension dimension = GridData.of(level);
        GridEdge edge = dimension.graph().edge(edgeId);
        if (edge == null) {
            return false;
        }
        for (long node : new long[] { edge.a(), edge.b() }) {
            GridData.NodeRecord record = dimension.node(node);
            if (record != null && !GridInteractions.mayChange(player, level, record.pos())) {
                return false;
            }
        }
        return true;
    }

    // Cuts one wire (the Wire Cutters): off the graph, its coil dropped at `drop` (unless in creative), both ends told.
    // False if it's gone.
    public static boolean cut(ServerLevel level, long edgeId, Vec3 drop) {
        return cut(level, edgeId, drop, true);
    }

    public static boolean cut(ServerLevel level, long edgeId, Vec3 drop, boolean dropCoil) {
        GridData.Dimension dimension = GridData.of(level);
        GridEdge existing = dimension.graph().edge(edgeId);
        if (existing == null || existing.isJumper()) {
            return false;
        }
        GridEdge edge = dimension.graph().removeEdge(edgeId);
        if (dropCoil) {
            Block.popResource(level, BlockPos.containing(drop), GridContent.coilFor(edge));
        }
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
            if (edge.isJumper() || edge.kind() == GridEdge.Kind.BURIED) {
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
                if (edge.a() != id || edge.isJumper() || edge.kind() == GridEdge.Kind.BURIED) {
                    continue;
                }
                GridData.NodeRecord a = dimension.node(edge.a()), b = dimension.node(edge.b());
                if (a != null && b != null) {
                    wires.add(new WireView(edge.id(), a.at(), b.at(), edge.tier(), edge.kind().name().toLowerCase(java.util.Locale.ROOT)));
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
        CREATIVE_BREAKS.remove(level);
        GridData.Dimension dimension = GridData.of(level);
        if (dimension.graph().edges().isEmpty()) {
            return;
        }
        List<PortHandler> handlers = new ArrayList<>();
        List<Coupling> couplings = new ArrayList<>();
        List<Shunt> shunts = new ArrayList<>();
        Set<Long> grounded = new java.util.TreeSet<>();
        Map<Long, FlowSolver.Protective> protective = new HashMap<>();
        List<PortSource> sources = new ArrayList<>();
        for (GridEndpointBlockEntity endpoint : new ArrayList<>(loadedIn(level))) {
            if (!endpoint.isRemoved() && endpoint instanceof PortSource source) {
                sources.add(source);
                source.ports(level, dimension, handlers);
                source.couplings(level, dimension, couplings);
                source.shunts(level, dimension, shunts);
                source.grounded(level, dimension, grounded);
                source.protective(level, dimension, protective);
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
        FlowSolver.State state = STATES.computeIfAbsent(level, k -> new FlowSolver.State());
        GridTicker.Report report = GridTicker.tick(dimension.graph(), new GridTicker.Terminals() {
            @Override
            public Collection<Port> ports() {
                return ports;
            }

            @Override
            public Collection<Coupling> couplings() {
                return couplings;
            }

            @Override
            public Collection<Shunt> shunts() {
                return shunts;
            }

            @Override
            public Set<Long> grounded() {
                return grounded;
            }

            @Override
            public Map<Long, FlowSolver.Protective> protective() {
                return protective;
            }

            @Override
            public FlowSolver.State state() {
                return state;
            }

            @Override
            public void commit(Map<Long, Integer> draws, Map<Long, Integer> deliveries) {
                // In one transaction, tried again until every sink took what it was given (EnergyCommit): a source that
                // gave less than it offered shrinks the deliveries in its part of the grid (the ports joined through any
                // network or transformer), and FE a sink refuses is never drawn.
                Map<Long, Long> group = groups(dimension, ports, couplings);
                EnergyCommit.Moved moved = EnergyCommit.run(draws, deliveries, group, () -> attempt(byId));
                byId.forEach((id, handler) -> {
                    int drawn = moved.drawn().getOrDefault(id, 0), delivered = moved.delivered().getOrDefault(id, 0);
                    if (drawn > 0 || delivered > 0) {
                        handler.moved(drawn, delivered);
                    }
                });
            }
        }, GridConfig.SETTINGS);
        FlowSolver.Result result = report.result();
        byId.forEach((id, handler) -> handler.report(result.status(id), result.drawn(id), result.delivered(id)));
        // The grounded networks, and the faults: each active one's current from every source that reaches it.
        Set<Long> groundedNetworks = new java.util.TreeSet<>();
        Map<Long, Long> groundedNodeOf = new TreeMap<>();
        for (long node : grounded) {
            GridNetwork network = dimension.graph().networkOf(node);
            if (network != null) {
                groundedNetworks.add(network.id());
                groundedNodeOf.putIfAbsent(network.id(), node);
            }
        }
        Map<Long, Fault> faults = FAULTS.computeIfAbsent(level, k -> new TreeMap<>());
        for (long network : result.groundFaults()) {
            Long node = groundedNodeOf.get(network);
            if (node != null) {
                faults.putIfAbsent(node, new Fault(node, -1, FaultEvent.Cause.GROUND_FAULT, level.getGameTime(), false));
            }
        }
        Map<Long, Double> faultAmps = new TreeMap<>();
        Map<Long, FlowSolver.Beyond> faultBeyond = new TreeMap<>();
        for (Fault fault : new ArrayList<>(faults.values())) {
            // A lasting fault is a grounded line: it's over once the line is no longer grounded and live.
            if (!fault.temporary()) {
                GridNetwork network = dimension.graph().networkOf(fault.node());
                if (network == null || !result.groundFaults().contains(network.id())) {
                    faults.remove(fault.node());
                    continue;
                }
            }
            FlowSolver.FaultFlow flow = FlowSolver.fault(dimension.graph(), ports, couplings, fault.node(), protective, GridConfig.SETTINGS);
            if (flow.amps() <= 0) {
                // Dead: a flashover is cleared.
                if (fault.temporary()) {
                    faults.remove(fault.node());
                }
                continue;
            }
            if (fault.since() == level.getGameTime() || fault.since() == level.getGameTime() - 1) {
                GridFaults.raise(FaultEvent.node(fault.node(), FaultEvent.Cause.GROUND_FAULT, flow.amps()));
            }
            flow.edgeAmps().forEach((edge, amps) -> faultAmps.merge(edge, amps, Math::max));
            flow.beyondInfo().forEach((edge, beyond) -> faultBeyond.merge(edge, beyond, (a, b) -> a.count() <= b.count() ? a : b));
            if (fault.temporary() && level.getGameTime() - fault.since() >= GridConfig.FLASHOVER_TICKS.getAsInt()) {
                // Nothing cleared it: the wire burns.
                GridEdge edge = fault.edge() >= 0 ? dimension.graph().edge(fault.edge()) : null;
                if (edge != null) {
                    burnOut(level, dimension, edge);
                }
                faults.remove(fault.node());
                continue;
            }
            if (level.getGameTime() % 10 == 0) {
                GridYard.stepPotential(level, dimension, fault.node(), flow.amps());
            }
        }
        // Each network's voltage: the average of its nodes' that power passed.
        Map<Long, double[]> sums = new HashMap<>();
        result.nodeVolts().forEach((node, volts) -> {
            GridNetwork network = dimension.graph().networkOf(node);
            if (network != null) {
                double[] sum = sums.computeIfAbsent(network.id(), k -> new double[2]);
                sum[0] += volts;
                sum[1]++;
            }
        });
        Map<Long, Double> networkVolts = new HashMap<>();
        sums.forEach((network, sum) -> networkVolts.put(network, sum[0] / sum[1]));
        // A lost phase is told once, when it's lost.
        Set<Long> lostBefore = LAST_PHASE_LOSS.getOrDefault(level, Set.of());
        Set<Long> lostNow = new java.util.HashSet<>();
        for (long portId : result.phaseLoss()) {
            Port port = ports.get((int) portId - 1);
            long first = port.nodes().get(0);
            lostNow.add(first);
            if (!lostBefore.contains(first)) {
                GridFaults.raise(FaultEvent.node(first, FaultEvent.Cause.PHASE_LOSS, 0));
            }
        }
        LAST_PHASE_LOSS.put(level, lostNow);
        TickView view = new TickView(result, faultAmps, faultBeyond, networkVolts, groundedNetworks);
        LAST_VIEW.put(level, view);
        LAST_AMPS.put(level, result.edgeAmps());
        LAST_LOSS.put(level, result.edgeLoss());
        for (PortSource source : sources) {
            if (!(source instanceof GridEndpointBlockEntity endpoint) || !endpoint.isRemoved()) {
                source.afterTick(level, dimension, view);
            }
        }
        WireShock.tick(level, dimension, ports, result);
        GridLightning.storm(level, dimension);
        // The HUD's numbers every 5 ticks (labels, wiring and device changes go at once, forced, where they happen).
        if (level.getGameTime() % 5 == 0) {
            for (GridEndpointBlockEntity endpoint : new ArrayList<>(loadedIn(level))) {
                if (!endpoint.isRemoved()) {
                    display(level, endpoint, false);
                }
            }
        }
        if (!report.burnt().isEmpty()) {
            burn(level, dimension, report.burnt());
        }
    }

    // Ports joined through any network or transformer share a group (union-find over their nodes' networks, each
    // transformer joining its sides'): a port's group id.
    // One try at moving the tick's energy: a root transaction the handlers draw and deliver within.
    private static EnergyCommit.Attempt attempt(Map<Long, PortHandler> byId) {
        Transaction tx = Transaction.openRoot();
        return new EnergyCommit.Attempt() {
            @Override
            public int draw(long port, int fe) {
                return byId.get(port).draw(fe, tx);
            }

            @Override
            public int deliver(long port, int fe) {
                return byId.get(port).deliver(fe, tx);
            }

            @Override
            public void commit() {
                tx.commit();
            }

            @Override
            public void close() {
                tx.close();
            }
        };
    }

    private static Map<Long, Long> groups(GridData.Dimension dimension, List<Port> ports, List<Coupling> couplings) {
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, Long> byNetwork = new HashMap<>();
        List<Port> all = new ArrayList<>(ports);
        long pseudo = -1;
        for (Coupling coupling : couplings) {
            List<Long> nodes = new ArrayList<>(coupling.primary());
            coupling.secondaries().forEach(nodes::addAll);
            all.add(new Port(pseudo--, coupling.primaryTier(), nodes, 0, 0, Double.POSITIVE_INFINITY));
        }
        for (Port port : all) {
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
