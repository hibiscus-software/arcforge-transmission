/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.CircuitType;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.GridGraph;
import net.zagdrath.arcforgetransmission.grid.GridNode;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// The grid of every dimension, saved once for the server (like Arcforge's own saved data): each insulator (where it is,
// its tier, its phase label) and each wire. It's the one true record: block entities only mirror what they draw, and a
// wire whose ends' chunks aren't loaded is still here, so networks survive unloading and saving whole.
public class GridData extends SavedData {
    private static final Codec<Phase> PHASE = Codec.STRING.xmap(Phase::byId, Phase::id);

    // An insulator as saved: by its block and its stable key (Insulator.key; empty in a save from before keys, matched by
    // its place in the block's list, index, until it takes one).
    public record NodeRecord(long id, BlockPos pos, int index, VoltageTier tier, Phase phase, Vec3 at, int group, String key) {
        static final Codec<NodeRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("id").forGetter(NodeRecord::id),
                BlockPos.CODEC.fieldOf("pos").forGetter(NodeRecord::pos),
                Codec.INT.fieldOf("index").forGetter(NodeRecord::index),
                WireView.TIER.fieldOf("tier").forGetter(NodeRecord::tier),
                PHASE.fieldOf("phase").forGetter(NodeRecord::phase),
                WireView.VEC.fieldOf("at").forGetter(NodeRecord::at),
                Codec.INT.optionalFieldOf("group", 0).forGetter(NodeRecord::group),
                Codec.STRING.optionalFieldOf("key", "").forGetter(NodeRecord::key)).apply(i, NodeRecord::new));
    }

    // A wire as saved (wires saved before insulation load as bare), a regulator's boost through a link, busway's size and
    // a cable's derating.
    public record EdgeRecord(long id, long a, long b, VoltageTier tier, double length, GridEdge.Kind kind, double boost, int size, double derate) {
        static final Codec<EdgeRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("id").forGetter(EdgeRecord::id),
                Codec.LONG.fieldOf("a").forGetter(EdgeRecord::a),
                Codec.LONG.fieldOf("b").forGetter(EdgeRecord::b),
                WireView.TIER.fieldOf("tier").forGetter(EdgeRecord::tier),
                Codec.DOUBLE.fieldOf("length").forGetter(EdgeRecord::length),
                Codec.STRING.xmap(GridEdge.Kind::byId, GridEdge.Kind::id).optionalFieldOf("kind", GridEdge.Kind.BARE).forGetter(EdgeRecord::kind),
                Codec.DOUBLE.optionalFieldOf("boost", 0.0).forGetter(EdgeRecord::boost),
                Codec.INT.optionalFieldOf("size", 1).forGetter(EdgeRecord::size),
                Codec.DOUBLE.optionalFieldOf("derate", 1.0).forGetter(EdgeRecord::derate)).apply(i, EdgeRecord::new));
    }

    record DimensionRecord(String dimension, long nextId, List<NodeRecord> nodes, List<EdgeRecord> edges) {
        static final Codec<DimensionRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("dimension").forGetter(DimensionRecord::dimension),
                Codec.LONG.fieldOf("next_id").forGetter(DimensionRecord::nextId),
                NodeRecord.CODEC.listOf().fieldOf("nodes").forGetter(DimensionRecord::nodes),
                EdgeRecord.CODEC.listOf().fieldOf("edges").forGetter(DimensionRecord::edges)).apply(i, DimensionRecord::new));
    }

    private static final Codec<GridData> CODEC = DimensionRecord.CODEC.listOf().xmap(GridData::new, GridData::save);
    public static final SavedDataType<GridData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "grid"), GridData::new, CODEC);

    // Where an insulator is: its block and its place in that block's list.
    public record Anchor(BlockPos pos, int index) {}

    // One dimension's grid: the graph the solver runs on, and where each node is in the world.
    public final class Dimension {
        final GridGraph graph = new GridGraph();
        final Map<Long, NodeRecord> nodes = new TreeMap<>();
        final Map<Long, List<Long>> byPos = new HashMap<>();
        long nextId = 1;

        public GridGraph graph() {
            return graph;
        }

        public @Nullable NodeRecord node(long id) {
            return nodes.get(id);
        }

        // The node of an insulator, or null.
        public @Nullable Long nodeAt(Anchor anchor) {
            List<Long> ids = byPos.get(anchor.pos().asLong());
            return ids == null || anchor.index() < 0 || anchor.index() >= ids.size() ? null : ids.get(anchor.index());
        }

        public List<Long> nodesAt(BlockPos pos) {
            return byPos.getOrDefault(pos.asLong(), List.of());
        }

        long newId() {
            setDirty();
            return nextId++;
        }

        void putNode(NodeRecord record) {
            nodes.put(record.id(), record);
            List<Long> ids = byPos.computeIfAbsent(record.pos().asLong(), k -> new ArrayList<>());
            while (ids.size() <= record.index()) {
                ids.add(-1L);
            }
            ids.set(record.index(), record.id());
            if (graph.node(record.id()) == null) {
                graph.addNode(new GridNode(record.id(), record.tier(), record.phase()));
            }
            setDirty();
        }

        // Removes every node of a block; returns the wires that went with them.
        List<GridEdge> removeAt(BlockPos pos) {
            List<Long> ids = byPos.remove(pos.asLong());
            List<GridEdge> removed = new ArrayList<>();
            if (ids != null) {
                for (long id : ids) {
                    if (id >= 0) {
                        removed.addAll(graph.removeNode(id));
                        nodes.remove(id);
                    }
                }
                setDirty();
            }
            return removed;
        }

        void markDirty() {
            setDirty();
        }

        DimensionRecord save(String dimension) {
            List<NodeRecord> nodeList = new ArrayList<>();
            for (NodeRecord record : nodes.values()) {
                GridNode node = graph.node(record.id());
                nodeList.add(node == null ? record
                        : new NodeRecord(record.id(), record.pos(), record.index(), record.tier(), node.phase(), record.at(), record.group(), record.key()));
            }
            List<EdgeRecord> edgeList = new ArrayList<>();
            for (GridEdge edge : graph.edges()) {
                edgeList.add(new EdgeRecord(edge.id(), edge.a(), edge.b(), edge.tier(), edge.length(), edge.kind(), edge.boost(), edge.size(),
                        edge.derate()));
            }
            return new DimensionRecord(dimension, nextId, nodeList, edgeList);
        }
    }

    private final Map<String, Dimension> dimensions = new LinkedHashMap<>();

    public GridData() {}

    private GridData(List<DimensionRecord> saved) {
        for (DimensionRecord record : saved) {
            Dimension dimension = new Dimension();
            dimension.nextId = record.nextId();
            for (NodeRecord node : record.nodes()) {
                dimension.putNode(node);
            }
            for (EdgeRecord edge : record.edges()) {
                if (dimension.graph.node(edge.a()) != null && dimension.graph.node(edge.b()) != null) {
                    GridNode a = dimension.graph.node(edge.a());
                    GridEdge loaded = new GridEdge(edge.id(), edge.a(), edge.b(), edge.tier(), a.circuit(), edge.length(), edge.kind(), edge.size());
                    loaded.setBoost(edge.boost());
                    loaded.setDerate(edge.derate());
                    dimension.graph.addEdge(loaded);
                }
            }
            dimensions.put(record.dimension(), dimension);
        }
    }

    private List<DimensionRecord> save() {
        List<DimensionRecord> out = new ArrayList<>();
        dimensions.forEach((name, dimension) -> out.add(dimension.save(name)));
        return out;
    }

    // A dimension's grid by its name, or null (a test reads a reloaded save through it).
    public @Nullable Dimension dimension(String name) {
        return dimensions.get(name);
    }

    // Every wire's saved form (a test compares a reloaded save with it).
    public static List<EdgeRecord> edgesOf(Dimension dimension) {
        return dimension.save("").edges();
    }

    public static GridData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public static Dimension of(ServerLevel level) {
        GridData data = get(level.getServer());
        return data.dimensions.computeIfAbsent(level.dimension().identifier().toString(), k -> data.new Dimension());
    }

    // A wire's circuit type, from its ends (both the same: ConnectionRules).
    static CircuitType circuitOf(GridGraph graph, long node) {
        GridNode n = graph.node(node);
        return n == null ? CircuitType.THREE_PHASE : n.circuit();
    }
}
