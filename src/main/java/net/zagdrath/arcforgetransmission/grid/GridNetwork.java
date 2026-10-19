/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.jspecify.annotations.Nullable;

// One connected piece of wire (one conductor's worth of a circuit): one tier, one circuit type. Its id is its lowest
// node id, the same for as long as that node stays in it. Its routes (shortest by wire length, ties broken by node
// id so they're the same every time) are found once per source and kept until the layout changes (GridGraph builds a
// new network then).
public final class GridNetwork {
    private final long id;
    private final VoltageTier tier;
    private final CircuitType circuit;
    private final List<Long> nodes;
    private final List<GridEdge> edges;
    private final GridGraph graph;
    private final Map<Long, Map<Long, Route>> routes = new HashMap<>();

    // A route from one node to another: the edges in order, each with the way power runs through it (true: a to b).
    public record Route(List<GridEdge> edges, List<Boolean> forward, List<Long> nodes) {}

    GridNetwork(long id, VoltageTier tier, CircuitType circuit, List<Long> nodes, List<GridEdge> edges, GridGraph graph) {
        this.id = id;
        this.tier = tier;
        this.circuit = circuit;
        this.nodes = nodes;
        this.edges = edges;
        this.graph = graph;
    }

    public long id() {
        return id;
    }

    public VoltageTier tier() {
        return tier;
    }

    public CircuitType circuit() {
        return circuit;
    }

    public List<Long> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<GridEdge> edges() {
        return Collections.unmodifiableList(edges);
    }

    // The shortest route from one node to another in this network, or null if there's none.
    public @Nullable Route route(long from, long to) {
        return routes.computeIfAbsent(from, this::routesFrom).get(to);
    }

    // Dijkstra from one node, by wire length.
    private Map<Long, Route> routesFrom(long source) {
        Map<Long, Double> distance = new HashMap<>();
        Map<Long, GridEdge> via = new HashMap<>();
        PriorityQueue<double[]> queue = new PriorityQueue<>((x, y) -> x[0] != y[0] ? Double.compare(x[0], y[0]) : Double.compare(x[1], y[1]));
        distance.put(source, 0.0);
        queue.add(new double[] { 0.0, source });
        while (!queue.isEmpty()) {
            double[] head = queue.poll();
            long node = (long) head[1];
            if (head[0] > distance.getOrDefault(node, Double.MAX_VALUE)) {
                continue;
            }
            // Edges by id and only a strictly shorter way kept: on a tie the first found (the lowest edge id from the node
            // nearest the source) stays, so routes don't depend on the order things were added in. (Re-choosing on ties
            // could point two nodes joined by a zero-length jumper at each other, a loop with no way back.)
            List<GridEdge> out = new ArrayList<>(graph.edgesOf(node));
            out.sort(Comparator.comparingLong(GridEdge::id));
            for (GridEdge edge : out) {
                long next = edge.other(node);
                double d = head[0] + edge.length();
                double known = distance.getOrDefault(next, Double.MAX_VALUE);
                if (d < known - 1.0E-9) {
                    distance.put(next, d);
                    via.put(next, edge);
                    queue.add(new double[] { d, next });
                }
            }
        }
        Map<Long, Route> result = new HashMap<>();
        for (long target : distance.keySet()) {
            List<GridEdge> path = new ArrayList<>();
            List<Boolean> forward = new ArrayList<>();
            List<Long> passed = new ArrayList<>();
            long at = target;
            passed.add(at);
            // A route never passes a node twice: a guard, should the tree ever be broken.
            for (int steps = 0; at != source; steps++) {
                GridEdge edge = via.get(at);
                if (edge == null || steps > distance.size()) {
                    throw new IllegalStateException("Broken route tree in network " + id + " from " + source + " to " + target);
                }
                long previous = edge.other(at);
                path.add(edge);
                forward.add(edge.a() == previous);
                passed.add(previous);
                at = previous;
            }
            Collections.reverse(path);
            Collections.reverse(forward);
            Collections.reverse(passed);
            result.put(target, new Route(List.copyOf(path), List.copyOf(forward), List.copyOf(passed)));
        }
        return result;
    }
}
