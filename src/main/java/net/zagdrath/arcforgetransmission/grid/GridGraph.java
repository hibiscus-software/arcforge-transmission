/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

// Every node and wire of one world (dimension): wires (single conductors) are edges, insulators are nodes. A network
// is one connected piece of wire: one conductor's worth of a circuit. The networks (connected
// pieces) and their routes are worked out when first needed after the layout changes, and kept until it changes again.
public final class GridGraph {
    // Sorted by id, so everything worked out from them comes out the same every time.
    private final Map<Long, GridNode> nodes = new TreeMap<>();
    private final Map<Long, GridEdge> edges = new TreeMap<>();
    private final Map<Long, List<GridEdge>> adjacency = new HashMap<>();
    private @Nullable List<GridNetwork> networks;
    private final Map<Long, GridNetwork> networkOf = new HashMap<>();

    public @Nullable GridNode node(long id) {
        return nodes.get(id);
    }

    public @Nullable GridEdge edge(long id) {
        return edges.get(id);
    }

    public Collection<GridNode> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public Collection<GridEdge> edges() {
        return Collections.unmodifiableCollection(edges.values());
    }

    public List<GridEdge> edgesOf(long node) {
        return Collections.unmodifiableList(adjacency.getOrDefault(node, List.of()));
    }

    public boolean connected(long a, long b) {
        for (GridEdge edge : adjacency.getOrDefault(a, List.of())) {
            if (edge.other(a) == b) {
                return true;
            }
        }
        return false;
    }

    public void addNode(GridNode node) {
        nodes.put(node.id(), node);
        adjacency.computeIfAbsent(node.id(), id -> new ArrayList<>());
        invalidate();
    }

    // Removes a node and every wire on it; returns those wires.
    public List<GridEdge> removeNode(long id) {
        List<GridEdge> removed = new ArrayList<>(adjacency.getOrDefault(id, List.of()));
        for (GridEdge edge : removed) {
            removeEdge(edge.id());
        }
        nodes.remove(id);
        adjacency.remove(id);
        invalidate();
        return removed;
    }

    // Relabels a node. Within its circuit type at any time (a transposition, or a tower's ABC/ACB); to the other type
    // only while it has no wires, so a wired network never mixes the two.
    public boolean setPhase(long id, Phase phase) {
        GridNode node = nodes.get(id);
        if (node == null || phase.circuit() != node.circuit() && !adjacency.getOrDefault(id, List.of()).isEmpty()) {
            return false;
        }
        node.setPhase(phase);
        return true;
    }

    // Adds a wire the rules have allowed (ConnectionRules); both ends must exist.
    public void addEdge(GridEdge edge) {
        if (!nodes.containsKey(edge.a()) || !nodes.containsKey(edge.b())) {
            throw new IllegalArgumentException("Wire " + edge.id() + " joins a missing node");
        }
        edges.put(edge.id(), edge);
        adjacency.get(edge.a()).add(edge);
        adjacency.get(edge.b()).add(edge);
        invalidate();
    }

    public @Nullable GridEdge removeEdge(long id) {
        GridEdge edge = edges.remove(id);
        if (edge != null) {
            adjacency.getOrDefault(edge.a(), new ArrayList<>()).remove(edge);
            adjacency.getOrDefault(edge.b(), new ArrayList<>()).remove(edge);
            invalidate();
        }
        return edge;
    }

    private void invalidate() {
        networks = null;
        networkOf.clear();
    }

    // The networks: each connected piece with at least one wire, in a fixed order.
    public List<GridNetwork> networks() {
        if (networks == null) {
            List<GridNetwork> built = new ArrayList<>();
            for (GridNode start : nodes.values()) {
                if (networkOf.containsKey(start.id()) || adjacency.getOrDefault(start.id(), List.of()).isEmpty()) {
                    continue;
                }
                List<Long> members = new ArrayList<>();
                List<GridEdge> memberEdges = new ArrayList<>();
                Deque<Long> queue = new ArrayDeque<>();
                queue.add(start.id());
                GridNetwork network = new GridNetwork(start.id(), start.tier(), start.circuit(), members, memberEdges, this);
                networkOf.put(start.id(), network);
                while (!queue.isEmpty()) {
                    long id = queue.poll();
                    members.add(id);
                    for (GridEdge edge : adjacency.get(id)) {
                        long other = edge.other(id);
                        if (!networkOf.containsKey(other)) {
                            networkOf.put(other, network);
                            queue.add(other);
                        }
                        if (edge.a() == id) {
                            memberEdges.add(edge);
                        }
                    }
                }
                Collections.sort(members);
                memberEdges.sort((x, y) -> Long.compare(x.id(), y.id()));
                built.add(network);
            }
            networks = built;
        }
        return networks;
    }

    // The network a node is in, or null when it has no wires.
    public @Nullable GridNetwork networkOf(long node) {
        networks();
        return networkOf.get(node);
    }
}
