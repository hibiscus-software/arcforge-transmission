/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Builds small grids for the tests: three-phase lines of towers (one network per phase) with a port at each end, and
// fake ports that hold real buffers, so every tick's energy can be counted.
final class GridFixtures {
    static final Phase[] ABC = { Phase.A, Phase.B, Phase.C };

    private GridFixtures() {}

    // Phase k's insulator at position i along a line: 1000 k + i.
    static long node(int phase, int i) {
        return 1000L * phase + i;
    }

    // Phase k's wire from position i to i + 1: 1000 k + 100 + i.
    static long edge(int phase, int i) {
        return 1000L * phase + 100 + i;
    }

    // A three-phase line: insulators 1 (the source end) to towers + 2 (the sink end) on each phase, each span `span`
    // blocks long.
    static GridGraph line(VoltageTier tier, int towers, double span) {
        GridGraph graph = new GridGraph();
        int last = towers + 2;
        for (int k = 0; k < 3; k++) {
            for (int i = 1; i <= last; i++) {
                graph.addNode(new GridNode(node(k, i), tier, ABC[k]));
                if (i > 1) {
                    graph.addEdge(new GridEdge(edge(k, i - 1), node(k, i - 1), node(k, i), tier, CircuitType.THREE_PHASE, span));
                }
            }
        }
        return graph;
    }

    // A line's three insulators at position i: a port's nodes.
    static List<Long> at(int i) {
        return List.of(node(0, i), node(1, i), node(2, i));
    }

    // Ports with buffers: sources give from what they hold (up to a rate), sinks take up to a demand (into a buffer),
    // each with an optional current limit. Counts everything moved.
    static final class Buffers implements GridTicker.Terminals {
        final Map<Long, Long> stored = new HashMap<>();
        final Map<Long, Double> rate = new HashMap<>(), demand = new HashMap<>(), limit = new HashMap<>();
        final Map<Long, List<Long>> nodes = new LinkedHashMap<>();
        final VoltageTier tier;
        long drawnTotal, deliveredTotal;

        Buffers(VoltageTier tier) {
            this.tier = tier;
        }

        Buffers source(long port, List<Long> nodes, long stored, double rate) {
            this.nodes.put(port, nodes);
            this.stored.put(port, stored);
            this.rate.put(port, rate);
            return this;
        }

        Buffers sink(long port, List<Long> nodes, double demand) {
            this.nodes.put(port, nodes);
            this.stored.putIfAbsent(port, 0L);
            this.demand.put(port, demand);
            return this;
        }

        Buffers limit(long port, double amps) {
            limit.put(port, amps);
            return this;
        }

        // Unloads a port (its chunk goes): it's simply not offered.
        Buffers unload(long port) {
            nodes.remove(port);
            return this;
        }

        @Override
        public List<Port> ports() {
            List<Port> ports = new ArrayList<>();
            nodes.forEach((id, list) -> ports.add(new Port(id, tier, list,
                    rate.containsKey(id) ? Math.min(rate.get(id), stored.getOrDefault(id, 0L)) : 0, demand.getOrDefault(id, 0.0),
                    limit.getOrDefault(id, Double.POSITIVE_INFINITY))));
            return ports;
        }

        @Override
        public void commit(Map<Long, Integer> draws, Map<Long, Integer> deliveries) {
            draws.forEach((port, fe) -> {
                long have = stored.getOrDefault(port, 0L);
                if (fe > have) {
                    throw new AssertionError("Drew " + fe + " from port " + port + " holding " + have);
                }
                stored.put(port, have - fe);
                drawnTotal += fe;
            });
            deliveries.forEach((port, fe) -> {
                stored.merge(port, (long) fe, Long::sum);
                deliveredTotal += fe;
            });
        }
    }
}
