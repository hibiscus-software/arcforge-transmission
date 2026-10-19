/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

// One tick of power over the whole grid of a world. Power only flows in complete circuits: a port (Port) is a set of
// insulators, one per conductor, and two ports are on the same circuit when their conductors land on the same set of
// networks (one per conductor; which letter meets which doesn't matter: that's a transposition, or ABC against ACB).
// A port with a conductor unwired, a phase missing, or two of its conductors on one network (a short) moves nothing.
//
// On each circuit, supply and demand are matched (each sink served in proportion to its demand, each source supplying
// in proportion to its offer) and each source-sink pair's power runs, conductor by conductor, along the cached
// shortest route in that conductor's network: I = P / V in each. The current in a wire is the sum of every pair's
// current through it as phasors (each conductor's at its phase angle from the source's label, the neutral carrying its
// line's back), so opposite flows cancel and a shared neutral carries only the difference of its two lines. Each wire
// loses I squared R, paid by the pairs through it in proportion to their own currents, on top of what they deliver. A
// port's current limit (an inductor's) caps every pair through any of its insulators. A source that can't cover its
// deliveries plus losses has its pairs scaled back (losses scaling with the square), and the whole is worked again until
// nothing needs scaling.
//
// It never makes energy: what the sources give is what the sinks get plus the losses.
public final class FlowSolver {
    private static final int ROUNDS = 24;

    // Why a port does or doesn't take part.
    public enum PortStatus {
        OK,
        // An insulator with no wire, or not in the grid at all.
        UNWIRED,
        // Not one whole circuit: A, B and C once each; or one line (L1 or L2) and N.
        INCOMPLETE,
        // Two of its conductors on one network.
        SHORTED,
        // An insulator of another tier than the port.
        WRONG_TIER
    }

    // What one tick moved: per port, what it gave and got (FE/t) and its status; per wire, its current (A) and loss
    // (FE/t).
    public record Result(Map<Long, Double> drawn, Map<Long, Double> delivered, Map<Long, Double> edgeAmps, Map<Long, Double> edgeLoss,
            Map<Long, PortStatus> status, double totalLoss) {
        public static final Result EMPTY = new Result(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0.0);

        public double drawn(long port) {
            return drawn.getOrDefault(port, 0.0);
        }

        public double delivered(long port) {
            return delivered.getOrDefault(port, 0.0);
        }

        public double amps(long edge) {
            return edgeAmps.getOrDefault(edge, 0.0);
        }

        public double loss(long edge) {
            return edgeLoss.getOrDefault(edge, 0.0);
        }

        public PortStatus status(long port) {
            return status.getOrDefault(port, PortStatus.UNWIRED);
        }

        public double totalDrawn() {
            return drawn.values().stream().mapToDouble(Double::doubleValue).sum();
        }

        public double totalDelivered() {
            return delivered.values().stream().mapToDouble(Double::doubleValue).sum();
        }
    }

    // One conductor of a pair: its route through its network and the angle of its current.
    private record Conductor(GridNetwork.Route route, double cos, double sin) {}

    private static final class Pair {
        final Port source, sink;
        final List<Conductor> conductors;
        double power;

        Pair(Port source, Port sink, List<Conductor> conductors, double power) {
            this.source = source;
            this.sink = sink;
            this.conductors = conductors;
            this.power = power;
        }
    }

    // A checked port: its network for each of its nodes.
    private record Checked(Port port, Map<Long, Long> nodeOfNetwork, List<Long> key) {}

    private FlowSolver() {}

    public static Result solve(GridGraph graph, Collection<Port> ports, GridSettings settings) {
        Map<Long, PortStatus> status = new TreeMap<>();
        // Circuits by their networks (sorted ids) and tier.
        Map<String, List<Checked>> circuits = new TreeMap<>();
        for (Port port : ports) {
            Checked checked = check(graph, port, status);
            if (checked != null) {
                circuits.computeIfAbsent(port.tier().id() + checked.key(), k -> new ArrayList<>()).add(checked);
            }
        }
        List<Pair> pairs = new ArrayList<>();
        Map<Long, Double> offers = new TreeMap<>();
        for (List<Checked> circuit : circuits.values()) {
            pair(graph, circuit, pairs, offers);
        }
        Map<Long, double[]> phasor = new HashMap<>();
        Map<Long, Double> gross = new HashMap<>(), edgeAmps = new TreeMap<>(), edgeLoss = new TreeMap<>();
        Map<Long, Double> limits = new TreeMap<>();
        for (Port port : ports) {
            if (port.limitAmps() < Double.POSITIVE_INFINITY && status.get(port.id()) == PortStatus.OK) {
                for (long node : port.nodes()) {
                    limits.merge(node, Math.max(0.0, port.limitAmps()), Math::min);
                }
            }
        }
        double[] pairCost = new double[pairs.size()];
        for (int round = 0; round < ROUNDS; round++) {
            boolean scaled = false;
            // Current limits: every pair through a limited insulator shares its limit.
            for (Map.Entry<Long, Double> limit : limits.entrySet()) {
                double through = 0;
                List<Pair> passing = new ArrayList<>();
                for (Pair pair : pairs) {
                    if (passes(pair, limit.getKey())) {
                        through += CircuitMath.current(pair.power, pair.source.tier());
                        passing.add(pair);
                    }
                }
                if (through > limit.getValue() + 1.0E-9) {
                    double factor = limit.getValue() / through;
                    passing.forEach(pair -> pair.power *= factor);
                    scaled = true;
                }
            }
            // Currents: phasors along each wire (a to b positive), and each wire's total of the pairs' own currents.
            phasor.clear();
            gross.clear();
            for (Pair pair : pairs) {
                double amps = CircuitMath.current(pair.power, pair.source.tier());
                for (Conductor conductor : pair.conductors) {
                    GridNetwork.Route route = conductor.route;
                    for (int i = 0; i < route.edges().size(); i++) {
                        long edge = route.edges().get(i).id();
                        double sign = route.forward().get(i) ? 1.0 : -1.0;
                        double[] sum = phasor.computeIfAbsent(edge, e -> new double[2]);
                        sum[0] += sign * amps * conductor.cos;
                        sum[1] += sign * amps * conductor.sin;
                        gross.merge(edge, amps, Double::sum);
                    }
                }
            }
            edgeAmps.clear();
            edgeLoss.clear();
            for (long edgeId : phasor.keySet()) {
                GridEdge edge = graph.edge(edgeId);
                double amps = CircuitMath.magnitude(phasor, edgeId);
                // A hair of cancellation error is no current.
                if (amps < 1.0E-9) {
                    amps = 0.0;
                }
                edgeAmps.put(edgeId, amps);
                edgeLoss.put(edgeId, edge == null ? 0.0 : CircuitMath.loss(amps, edge.resistance(settings)));
            }
            // Each pair pays its share of the losses on its routes; each source owes what its pairs cost.
            Map<Long, Double> cost = new TreeMap<>(), delivering = new TreeMap<>();
            for (int p = 0; p < pairs.size(); p++) {
                Pair pair = pairs.get(p);
                double amps = CircuitMath.current(pair.power, pair.source.tier());
                double total = pair.power;
                for (Conductor conductor : pair.conductors) {
                    for (GridEdge edge : conductor.route.edges()) {
                        double all = gross.getOrDefault(edge.id(), 0.0);
                        if (all > 0) {
                            total += edgeLoss.getOrDefault(edge.id(), 0.0) * amps / all;
                        }
                    }
                }
                pairCost[p] = total;
                cost.merge(pair.source.id(), total, Double::sum);
                delivering.merge(pair.source.id(), pair.power, Double::sum);
            }
            for (Map.Entry<Long, Double> owed : cost.entrySet()) {
                double can = offers.getOrDefault(owed.getKey(), 0.0);
                if (owed.getValue() > can * (1 + 1.0E-9) + 1.0E-9) {
                    // Scaled so that what it delivers (d, which scales with the factor) plus its losses (l, which
                    // scale with its square) comes to what it can give: f d + f^2 l = can.
                    double d = delivering.getOrDefault(owed.getKey(), 0.0), l = Math.max(0, owed.getValue() - d);
                    double factor = l > 1.0E-12 ? (-d + Math.sqrt(d * d + 4 * l * can)) / (2 * l) : can / owed.getValue();
                    for (Pair pair : pairs) {
                        if (pair.source.id() == owed.getKey()) {
                            pair.power *= factor;
                        }
                    }
                    scaled = true;
                }
            }
            if (!scaled) {
                break;
            }
        }
        Map<Long, Double> drawn = new TreeMap<>(), delivered = new TreeMap<>();
        for (int p = 0; p < pairs.size(); p++) {
            Pair pair = pairs.get(p);
            drawn.merge(pair.source.id(), pairCost[p], Double::sum);
            delivered.merge(pair.sink.id(), pair.power, Double::sum);
        }
        double totalLoss = edgeLoss.values().stream().mapToDouble(Double::doubleValue).sum();
        return new Result(drawn, delivered, edgeAmps, edgeLoss, status, totalLoss);
    }

    // Whether a pair's current passes through an insulator (as an end, or on the way).
    private static boolean passes(Pair pair, long node) {
        for (Conductor conductor : pair.conductors) {
            if (conductor.route.nodes().contains(node)) {
                return true;
            }
        }
        return false;
    }

    // Matches a circuit's sources to its sinks: each sink served in proportion to its demand, each source supplying in
    // proportion to its offer, never a port with itself.
    private static void pair(GridGraph graph, List<Checked> circuit, List<Pair> pairs, Map<Long, Double> offers) {
        List<Checked> sources = new ArrayList<>(), sinks = new ArrayList<>();
        double supply = 0, wanted = 0;
        for (Checked checked : circuit) {
            if (checked.port.offer() > 1.0E-9) {
                sources.add(checked);
                supply += checked.port.offer();
                offers.put(checked.port.id(), checked.port.offer());
            }
            if (checked.port.demand() > 1.0E-9) {
                sinks.add(checked);
                wanted += checked.port.demand();
            }
        }
        if (supply <= 0 || wanted <= 0) {
            return;
        }
        double served = Math.min(supply, wanted);
        for (Checked sink : sinks) {
            double toSink = sink.port.demand() * served / wanted;
            // From every other source, by what it offers (a port that both gives and takes isn't paired with itself;
            // a source asked for more than it has is scaled back with its losses below).
            double others = supply - (sink.port.offer() > 1.0E-9 ? sink.port.offer() : 0.0);
            if (others <= 1.0E-9) {
                continue;
            }
            for (Checked source : sources) {
                if (source.port.id() == sink.port.id()) {
                    continue;
                }
                List<Conductor> conductors = conductors(graph, source, sink);
                if (conductors != null) {
                    pairs.add(new Pair(source.port, sink.port, conductors, toSink * source.port.offer() / others));
                }
            }
        }
    }

    // A pair's conductors: for each network, the source's insulator on it to the sink's, at the source's phase.
    private static @Nullable List<Conductor> conductors(GridGraph graph, Checked source, Checked sink) {
        List<Phase> labels = new ArrayList<>();
        for (long node : source.port.nodes()) {
            labels.add(graph.node(node).phase());
        }
        List<Conductor> conductors = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : source.nodeOfNetwork.entrySet()) {
            long from = entry.getValue();
            Long to = sink.nodeOfNetwork.get(entry.getKey());
            GridNetwork network = graph.networkOf(from);
            GridNetwork.Route route = to == null || network == null ? null : network.route(from, to);
            if (route == null) {
                return null;
            }
            double angle = CircuitMath.angle(graph.node(from).phase(), labels);
            conductors.add(new Conductor(route, Math.cos(angle), Math.sin(angle)));
        }
        return conductors;
    }

    // Whether a port is one whole circuit, each conductor wired on a network of its own.
    private static @Nullable Checked check(GridGraph graph, Port port, Map<Long, PortStatus> status) {
        Set<Phase> labels = EnumSet.noneOf(Phase.class);
        Map<Long, Long> nodeOfNetwork = new TreeMap<>();
        boolean unwired = false, shorted = false, duplicate = false;
        for (long id : port.nodes()) {
            GridNode node = graph.node(id);
            if (node == null) {
                unwired = true;
                continue;
            }
            if (node.tier() != port.tier()) {
                status.put(port.id(), PortStatus.WRONG_TIER);
                return null;
            }
            duplicate |= !labels.add(node.phase());
            GridNetwork network = graph.networkOf(id);
            if (network == null) {
                unwired = true;
            } else if (nodeOfNetwork.putIfAbsent(network.id(), id) != null) {
                shorted = true;
            }
        }
        boolean whole = !duplicate && (labels.equals(EnumSet.of(Phase.A, Phase.B, Phase.C))
                || labels.equals(EnumSet.of(Phase.L1, Phase.N)) || labels.equals(EnumSet.of(Phase.L2, Phase.N)));
        PortStatus result = !whole ? PortStatus.INCOMPLETE : unwired ? PortStatus.UNWIRED : shorted ? PortStatus.SHORTED : PortStatus.OK;
        status.put(port.id(), result);
        return result == PortStatus.OK ? new Checked(port, nodeOfNetwork, List.copyOf(nodeOfNetwork.keySet())) : null;
    }
}
