/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

// One tick of power over the whole grid of a world. Power only flows in complete circuits: a port (Port) is a set of
// insulators, one per conductor, and a circuit is the set of networks (one per conductor) at one tier that ports and
// transformer sides land on (which letter meets which doesn't matter: that's a transposition). A port with a conductor
// unwired, a phase missing, or two of its conductors on one network (a short) moves nothing.
//
// Transformers (Coupling) link a circuit to circuits a tier down, either way, holding nothing: circuits joined by them
// make an island; transformers in parallel between the same two circuits share what passes by their ratings. In each
// island supply and demand are matched (each sink served in proportion to its demand, each source supplying in
// proportion to its offer; batteries only cover what the rest can't, or take what they have spare); the pairs are built
// once a tick and rescaled as the rounds go. Each pair's current enters its networks at one end and leaves at the other
// (real current at its conductor's phase angle, reactive a quarter turn behind), and every wire's current is the sum of
// what passes it: in a tree network one pass over the tree, in a meshed one (a loop, parallel wires) a resistive solve,
// so parallel paths share by their impedance. Each wire loses I squared R, paid by the pairs through it in proportion to
// their currents (a capacitor bank's or reactor's by its own island's sources). A port's current limit caps its own
// pairs and a transformer's rating those through it; a source that can't cover its deliveries plus losses has its pairs
// scaled back, and the whole is worked again until nothing scales (and if the rounds run out, the losses are still paid).
//
// Voltage: each source holds its setpoint (per unit); along each wire it drops by the wire's impedance times its current
// (projected on the conductor's angle), a regulator's link boosting and a transformer's tap stepping. A load below
// fullPowerVoltage gets less (never under minShare, so it stays a load), worked out over a few rounds a tick and carried
// over by its first node, coming back when its voltage does. Capacitor banks supply reactive power to the island's loads
// and reactors (the rest coming from its sources, which also take any surplus), so a bank raises the voltage near it and
// a reactor lowers it; node voltages are the real flow's only.
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
        WRONG_TIER,
        // A conductor on a grounded line (a closed ground switch).
        GROUNDED,
        // No FE block where it should be (a pin insulator's mount, a unit transformer's front).
        NO_FE_BLOCK,
        // A power plant that connects at another tier (Generators): it gives nothing here (set by the block, never the
        // solver).
        WRONG_TIER_GENERATOR,
        // No live source in its island, or it's in no pair at all.
        DEAD,
        // Wired fine, but nothing on the line can take what it gives or give what it takes.
        NO_PARTNER
    }

    // A protective link's pickup (A) and whether it's a fuse (its pickup then its rating), for grading.
    public record Protective(double pickup, boolean fuse) {}

    // Protective devices between a link and the trouble through it: how many, whether they're all fuses, and the least
    // fuse rating among them (A; 0 for none).
    public record Beyond(int count, boolean allFuses, double fuseRating) {
        public static final Beyond NONE = new Beyond(0, false, 0);
    }

    // What one tick moved: per port, what it gave and got (FE/t), its status and voltage (per unit); per wire, its
    // current (A) and loss (FE/t); per node, its voltage (per unit; only where power flows); per transformer, the power
    // through it (FE/t, positive primary to secondary); the live networks (in an island with a source), and those live
    // without counting transfer switches (sourceLive: what's fed from its own side); ports that lost a phase; grounded
    // networks a source reaches (a fault); and for each protective link, the overloaded devices beyond it.
    public record Result(Map<Long, Double> drawn, Map<Long, Double> delivered, Map<Long, Double> edgeAmps, Map<Long, Double> edgeLoss,
            Map<Long, PortStatus> status, double totalLoss, Map<Long, Double> portVolts, Map<Long, Double> nodeVolts,
            Map<Long, Double> couplingFlow, Set<Long> liveNetworks, Set<Long> sourceLive, Set<Long> phaseLoss, Set<Long> groundFaults,
            Map<Long, Beyond> beyondInfo) {
        public static final Result EMPTY = new Result(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0.0, Map.of(), Map.of(), Map.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Map.of());

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

        // A port's voltage, per unit (a source its setpoint; 0 when nothing flowed to or from it).
        public double volts(long port) {
            return portVolts.getOrDefault(port, 0.0);
        }

        public double nodeVolts(long node) {
            return nodeVolts.getOrDefault(node, 0.0);
        }

        // How many overloaded protective devices lie past a protective link on the way to the loads it feeds.
        public int beyond(long edge) {
            return beyond(edge, Beyond.NONE).count();
        }

        public Beyond beyond(long edge, Beyond fallback) {
            return beyondInfo.getOrDefault(edge, fallback);
        }

        public double totalDrawn() {
            return drawn.values().stream().mapToDouble(Double::doubleValue).sum();
        }

        public double totalDelivered() {
            return delivered.values().stream().mapToDouble(Double::doubleValue).sum();
        }
    }

    // What carries over between ticks: each load's share of its demand that its voltage allows, by its first node.
    public static final class State {
        final Map<Long, Double> share = new HashMap<>();

        public double share(long key) {
            return share.getOrDefault(key, 1.0);
        }
    }

    private FlowSolver() {}

    // ================================================================================================================
    // Entry points
    // ================================================================================================================

    public static Result solve(GridGraph graph, Collection<Port> ports, GridSettings settings) {
        return solve(graph, ports, List.of(), List.of(), Set.of(), Map.of(), settings, new State());
    }

    public static Result solve(GridGraph graph, Collection<Port> ports, Collection<Coupling> couplings, Collection<Shunt> shunts, Set<Long> grounded,
            GridSettings settings, State state) {
        return solve(graph, ports, couplings, shunts, grounded, Map.of(), settings, state);
    }

    // protective: the protective links (by edge id), each counted as overloaded whenever it carries current.
    public static Result solve(GridGraph graph, Collection<Port> ports, Collection<Coupling> couplings, Collection<Shunt> shunts, Set<Long> grounded,
            Set<Long> protective, GridSettings settings, State state) {
        return solve(graph, ports, couplings, shunts, grounded, asProtective(protective), settings, state);
    }

    private static Map<Long, Protective> asProtective(Set<Long> links) {
        Map<Long, Protective> out = new HashMap<>();
        links.forEach(link -> out.put(link, new Protective(0, false)));
        return out;
    }

    public static Result solve(GridGraph graph, Collection<Port> ports, Collection<Coupling> couplings, Collection<Shunt> shunts, Set<Long> grounded,
            Map<Long, Protective> protective, GridSettings settings, State state) {
        Map<Long, PortStatus> status = new TreeMap<>();
        Context ctx = new Context(graph, ports, couplings, shunts, grounded, status);
        Result result = new Solve(ctx, graph, settings, state, protective).run();
        // Sides that weren't whole keep the status the check gave them.
        Map<Long, PortStatus> all = new TreeMap<>(status);
        all.putAll(result.status());
        return new Result(result.drawn(), result.delivered(), result.edgeAmps(), result.edgeLoss(), all, result.totalLoss(), result.portVolts(),
                result.nodeVolts(), result.couplingFlow(), result.liveNetworks(), result.sourceLive(), result.phaseLoss(), result.groundFaults(),
                result.beyondInfo());
    }

    // The share of its demand a load gets at a voltage (per unit).
    public static double share(double volts, GridSettings settings) {
        double full = settings.fullPowerVoltage(), cutoff = settings.cutoffVoltage();
        if (volts >= full) {
            return 1.0;
        }
        if (volts <= cutoff || full <= cutoff) {
            return 0.0;
        }
        return (volts - cutoff) / (full - cutoff);
    }

    // ================================================================================================================
    // The checked sides and their circuits, islands and liveness
    // ================================================================================================================

    // A checked side (a port, a transformer's side, a shunt): its id (a port's own; transformer sides and shunts get
    // negative ids of their own, so nothing collides or overflows), tier, nodes, its network for each node, circuit,
    // labels and island.
    private static final class Side {
        final long id;
        final VoltageTier tier;
        final Map<Long, Long> nodeOfNetwork;
        final String circuit;
        final List<Phase> labels;
        @Nullable Port port;
        @Nullable Shunt shunt;
        int island = -1;

        Side(long id, VoltageTier tier, Map<Long, Long> nodeOfNetwork, String circuit, List<Phase> labels) {
            this.id = id;
            this.tier = tier;
            this.nodeOfNetwork = nodeOfNetwork;
            this.circuit = circuit;
            this.labels = labels;
        }
    }

    // A working transformer: its sides (index 0 the primary, then the secondaries; null where a side isn't whole).
    private record Transformer(Coupling coupling, @Nullable Side[] sides) {}

    // One way across a transformer: leaving by one side, entering by another (down: primary to a secondary).
    private record Crossing(Transformer transformer, Side leave, Side enter, boolean down) {}

    private static final class Context {
        final GridGraph graph;
        final Set<Long> groundedNetworks = new TreeSet<>();
        final Map<Long, Side> ports = new TreeMap<>();
        final List<Side> shunts = new ArrayList<>();
        final List<Transformer> transformers = new ArrayList<>();
        // The crossings out of each circuit, grouped by the circuit they lead to (parallel transformers together).
        final Map<String, Map<String, List<Crossing>>> crossings = new TreeMap<>();
        final Map<String, Integer> island = new TreeMap<>(), sourceIsland = new TreeMap<>();
        final Map<String, Set<Long>> networksOf = new TreeMap<>();
        final Set<Integer> liveIslands = new TreeSet<>(), liveSourceIslands = new TreeSet<>();
        final Set<Long> liveNetworks = new TreeSet<>(), sourceLive = new TreeSet<>();
        final Map<Long, Port> portById = new TreeMap<>();
        private final Map<String, Map<String, String>> trees = new HashMap<>();
        long nextId = -1;

        Context(GridGraph graph, Collection<Port> portList, Collection<Coupling> couplings, Collection<Shunt> shuntList, Set<Long> grounded,
                Map<Long, PortStatus> status) {
            this.graph = graph;
            for (long node : grounded) {
                GridNetwork network = graph.networkOf(node);
                if (network != null) {
                    groundedNetworks.add(network.id());
                }
            }
            for (Port port : portList) {
                portById.put(port.id(), port);
                Side side = check(port.id(), port.tier(), port.nodes(), status);
                if (side != null) {
                    side.port = port;
                    ports.put(port.id(), side);
                }
            }
            for (Coupling coupling : couplings) {
                if (!coupling.inService()) {
                    continue;
                }
                Side[] sides = new Side[coupling.secondaries().size() + 1];
                sides[0] = check(nextId--, coupling.primaryTier(), coupling.primary(), null);
                if (sides[0] == null) {
                    continue;
                }
                for (int s = 0; s < coupling.secondaries().size(); s++) {
                    sides[s + 1] = check(nextId--, coupling.secondaryTier(), coupling.secondaries().get(s), null);
                }
                Transformer transformer = new Transformer(coupling, sides);
                transformers.add(transformer);
                for (int s = 1; s < sides.length; s++) {
                    if (sides[s] != null) {
                        addCrossing(new Crossing(transformer, sides[0], sides[s], true));
                        addCrossing(new Crossing(transformer, sides[s], sides[0], false));
                    }
                }
            }
            for (Shunt shunt : shuntList) {
                if (shunt.inService()) {
                    Side side = check(nextId--, shunt.tier(), shunt.nodes(), null);
                    if (side != null) {
                        side.shunt = shunt;
                        shunts.add(side);
                    }
                }
            }
            for (Side side : allSides()) {
                networksOf.put(side.circuit, side.nodeOfNetwork.keySet());
            }
            islands(island, false);
            islands(sourceIsland, true);
            for (Side side : allSides()) {
                side.island = island.get(side.circuit);
            }
            for (Side side : ports.values()) {
                if (side.port.offer() > 1.0E-9) {
                    liveIslands.add(island.get(side.circuit));
                    liveSourceIslands.add(sourceIsland.get(side.circuit));
                }
            }
            networksOf.forEach((circuit, networks) -> {
                if (liveIslands.contains(island.get(circuit))) {
                    liveNetworks.addAll(networks);
                }
                if (liveSourceIslands.contains(sourceIsland.get(circuit))) {
                    sourceLive.addAll(networks);
                }
            });
        }

        private void addCrossing(Crossing crossing) {
            crossings.computeIfAbsent(crossing.leave.circuit, k -> new TreeMap<>()).computeIfAbsent(crossing.enter.circuit, k -> new ArrayList<>())
                    .add(crossing);
        }

        List<Side> allSides() {
            List<Side> all = new ArrayList<>(ports.values());
            all.addAll(shunts);
            for (Transformer transformer : transformers) {
                for (Side side : transformer.sides) {
                    if (side != null) {
                        all.add(side);
                    }
                }
            }
            return all;
        }

        // Union of circuits through working transformers (bySource: not through transfer switches, so a feeder reads live
        // only from its own side's sources).
        private void islands(Map<String, Integer> out, boolean bySource) {
            Map<String, String> parent = new TreeMap<>();
            for (Side side : allSides()) {
                parent.putIfAbsent(side.circuit, side.circuit);
            }
            for (Transformer transformer : transformers) {
                if (bySource && transformer.coupling.transfer()) {
                    continue;
                }
                for (int s = 1; s < transformer.sides.length; s++) {
                    if (transformer.sides[s] != null) {
                        union(parent, transformer.sides[0].circuit, transformer.sides[s].circuit);
                    }
                }
            }
            Map<String, Integer> numbers = new TreeMap<>();
            for (String circuit : parent.keySet()) {
                String root = find(parent, circuit);
                out.put(circuit, numbers.computeIfAbsent(root, r -> numbers.size()));
            }
        }

        // Whether a side is one whole circuit, each conductor wired on a network of its own (none grounded).
        private @Nullable Side check(long id, VoltageTier tier, List<Long> nodes, @Nullable Map<Long, PortStatus> status) {
            Set<Phase> labels = EnumSet.noneOf(Phase.class);
            List<Phase> order = new ArrayList<>();
            Map<Long, Long> nodeOfNetwork = new TreeMap<>();
            boolean unwired = false, shorted = false, duplicate = false, isGrounded = false;
            for (long node : nodes) {
                GridNode n = graph.node(node);
                if (n == null) {
                    unwired = true;
                    continue;
                }
                if (n.tier() != tier) {
                    if (status != null) {
                        status.put(id, PortStatus.WRONG_TIER);
                    }
                    return null;
                }
                duplicate |= !labels.add(n.phase());
                order.add(n.phase());
                GridNetwork network = graph.networkOf(node);
                if (network == null) {
                    unwired = true;
                } else if (nodeOfNetwork.putIfAbsent(network.id(), node) != null) {
                    shorted = true;
                } else if (groundedNetworks.contains(network.id())) {
                    isGrounded = true;
                }
            }
            boolean whole = !duplicate && (labels.equals(EnumSet.of(Phase.A, Phase.B, Phase.C))
                    || labels.equals(EnumSet.of(Phase.L1, Phase.N)) || labels.equals(EnumSet.of(Phase.L2, Phase.N)));
            PortStatus result = !whole ? PortStatus.INCOMPLETE : unwired ? PortStatus.UNWIRED : shorted ? PortStatus.SHORTED
                    : isGrounded ? PortStatus.GROUNDED : PortStatus.OK;
            if (status != null) {
                status.put(id, result);
            }
            return result == PortStatus.OK ? new Side(id, tier, nodeOfNetwork, tier.id() + nodeOfNetwork.keySet(), order) : null;
        }

        // The hops from one circuit to another through transformers (fewest transformers), each the crossings in parallel
        // between its two circuits; null if unreachable.
        @Nullable List<List<Crossing>> hops(String from, String to) {
            if (from.equals(to)) {
                return List.of();
            }
            Map<String, String> via = trees.computeIfAbsent(from, start -> {
                Map<String, String> prev = new HashMap<>();
                Set<String> seen = new HashSet<>();
                seen.add(start);
                ArrayDeque<String> queue = new ArrayDeque<>();
                queue.add(start);
                while (!queue.isEmpty()) {
                    String circuit = queue.poll();
                    for (String next : crossings.getOrDefault(circuit, Map.of()).keySet()) {
                        if (seen.add(next)) {
                            prev.put(next, circuit);
                            queue.add(next);
                        }
                    }
                }
                return prev;
            });
            if (!via.containsKey(to)) {
                return null;
            }
            List<List<Crossing>> hops = new ArrayList<>();
            String at = to;
            while (!at.equals(from)) {
                String before = via.get(at);
                hops.add(0, crossings.get(before).get(at));
                at = before;
            }
            return hops;
        }
    }

    private static String find(Map<String, String> parent, String x) {
        while (!parent.get(x).equals(x)) {
            parent.put(x, parent.get(parent.get(x)));
            x = parent.get(x);
        }
        return x;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a), rb = find(parent, b);
        if (!ra.equals(rb)) {
            if (ra.compareTo(rb) < 0) {
                parent.put(rb, ra);
            } else {
                parent.put(ra, rb);
            }
        }
    }

    // ================================================================================================================
    // Networks as the solver works them
    // ================================================================================================================

    // One network this tick: its shape, each wire's impedance, the currents entering and leaving at each node, and what
    // comes of them: each wire's current (a to b), gross (the sum of the pairs' current sizes through it) and loss, each
    // node's potential, and the prefix sums the loss shares and regulator boosts are read from.
    private static final class Net {
        final GridNetwork network;
        final NetworkTopology t;
        final VoltageTier tier;
        final double[] r, x;
        final double[] injRe, injIm, injGross;
        final double[] curRe, curIm, gross, loss;
        final double[] pRe, pIm, lossPerGross, boost;
        final boolean boosted;
        // Protective links on each node's way to the root (a tree): the wire indices, nearest first.
        int @Nullable [][] protectiveUp;
        private @Nullable Map<Long, Integer> edgeIndex;

        Net(GridNetwork network, GridSettings settings) {
            this.network = network;
            this.t = network.topology();
            this.tier = network.tier();
            int n = t.n, m = t.m;
            r = new double[m];
            x = new double[m];
            boolean anyBoost = false;
            for (int e = 0; e < m; e++) {
                GridEdge edge = t.edges[e];
                r[e] = edge.resistance(settings);
                x[e] = settings.reactance(edge.tier()) * edge.length();
                anyBoost |= edge.boost() != 0;
            }
            boosted = anyBoost;
            injRe = new double[n];
            injIm = new double[n];
            injGross = new double[n];
            curRe = new double[m];
            curIm = new double[m];
            gross = new double[m];
            loss = new double[m];
            pRe = new double[n];
            pIm = new double[n];
            lossPerGross = new double[n];
            boost = new double[n];
        }

        void clear() {
            Arrays.fill(injRe, 0);
            Arrays.fill(injIm, 0);
            Arrays.fill(injGross, 0);
        }

        // Every wire's current, gross and loss, and every node's potential, from the injections.
        void solve() {
            if (t.tree) {
                solveTree();
            } else {
                solveMesh();
            }
        }

        private void solveTree() {
            int n = t.n;
            double[] sRe = injRe.clone(), sIm = injIm.clone(), sG = injGross.clone();
            for (int k = n - 1; k >= 1; k--) {
                int v = t.order[k], p = t.parent[v], e = t.parentEdge[v];
                double sign = t.childIsA[v] ? 1 : -1;
                curRe[e] = sign * sRe[v];
                curIm[e] = sign * sIm[v];
                gross[e] = Math.max(0, sG[v]);
                loss[e] = (curRe[e] * curRe[e] + curIm[e] * curIm[e]) * r[e];
                sRe[p] += sRe[v];
                sIm[p] += sIm[v];
                sG[p] += sG[v];
            }
            // Potentials from the root down: a node is above its parent by its wire's impedance times the current up it.
            int root = t.order[0];
            pRe[root] = pIm[root] = lossPerGross[root] = boost[root] = 0;
            for (int k = 1; k < n; k++) {
                int v = t.order[k], p = t.parent[v], e = t.parentEdge[v];
                double sign = t.childIsA[v] ? 1 : -1, ire = sign * curRe[e], iim = sign * curIm[e];
                pRe[v] = pRe[p] + r[e] * ire - x[e] * iim;
                pIm[v] = pIm[p] + r[e] * iim + x[e] * ire;
                lossPerGross[v] = lossPerGross[p] + (gross[e] > 1.0E-12 ? loss[e] / gross[e] : 0);
                double b = t.edges[e].boost();
                // Down from parent to child: b to a is backward (bucks), a to b forward (boosts).
                boost[v] = boost[p] + (b == 0 ? 0 : t.childIsA[v] ? 1 / (1 + b) - 1 : b);
            }
        }

        private void solveMesh() {
            int n = t.n, supers = t.supers;
            // The super-nodes' injections, and a resistive solve between them (real and reactive parts each alone).
            double[] sRe = new double[supers], sIm = new double[supers];
            for (int v = 0; v < n; v++) {
                sRe[t.superOf[v]] += injRe[v];
                sIm[t.superOf[v]] += injIm[v];
            }
            double[] g = new double[t.meshEdges.length];
            for (int k = 0; k < g.length; k++) {
                g[k] = 1.0 / Math.max(r[t.meshEdges[k]], 1.0E-9);
            }
            double[] phiRe = laplacianSolve(sRe, g), phiIm = laplacianSolve(sIm, g);
            Arrays.fill(curRe, 0);
            Arrays.fill(curIm, 0);
            double[] balRe = injRe.clone(), balIm = injIm.clone();
            for (int k = 0; k < g.length; k++) {
                int e = t.meshEdges[k], a = t.ea[e], b = t.eb[e];
                int sa = t.superOf[a], sb = t.superOf[b];
                curRe[e] = g[k] * (phiRe[sa] - phiRe[sb]);
                curIm[e] = g[k] * (phiIm[sa] - phiIm[sb]);
                balRe[a] -= curRe[e];
                balIm[a] -= curIm[e];
                balRe[b] += curRe[e];
                balIm[b] += curIm[e];
            }
            // Within each super-node the links carry what's left, leaves first.
            for (int k = n - 1; k >= 0; k--) {
                int v = t.linkOrder[k], p = t.linkParent[v];
                if (p < 0) {
                    continue;
                }
                int e = t.linkEdge[v];
                double sign = t.linkChildIsA[v] ? 1 : -1;
                curRe[e] = sign * balRe[v];
                curIm[e] = sign * balIm[v];
                balRe[p] += balRe[v];
                balIm[p] += balIm[v];
            }
            double ratio = 0;
            int count = 0;
            for (int e = 0; e < t.m; e++) {
                gross[e] = Math.hypot(curRe[e], curIm[e]);
                loss[e] = (curRe[e] * curRe[e] + curIm[e] * curIm[e]) * r[e];
                if (r[e] > 1.0E-12) {
                    ratio += x[e] / r[e];
                    count++;
                }
            }
            // Potentials: the resistive solve's, turned by the line's X/R (one network, one tier, one ratio).
            double k = count == 0 ? 0 : ratio / count;
            for (int v = 0; v < n; v++) {
                double re = phiRe[t.superOf[v]], im = phiIm[t.superOf[v]];
                pRe[v] = re - k * im;
                pIm[v] = im + k * re;
                boost[v] = 0;
            }
        }

        // Solves L phi = b for the super-nodes (L the weighted Laplacian of the mesh wires, super-node 0 held at 0) by
        // conjugate gradients.
        private double[] laplacianSolve(double[] b, double[] g) {
            int s = t.supers;
            double[] phi = new double[s];
            if (s <= 1) {
                return phi;
            }
            double[] res = b.clone(), ap = new double[s];
            res[0] = 0;
            double[] dir = res.clone();
            double rr = dot(res, res), start = rr;
            if (rr < 1.0E-24) {
                return phi;
            }
            for (int iter = 0; iter < 4 * s + 20 && rr > 1.0E-24 * Math.max(1, start); iter++) {
                Arrays.fill(ap, 0);
                for (int k = 0; k < g.length; k++) {
                    int e = t.meshEdges[k], a = t.superOf[t.ea[e]], c = t.superOf[t.eb[e]];
                    if (a == c) {
                        continue;
                    }
                    double flow = g[k] * (dir[a] - dir[c]);
                    ap[a] += flow;
                    ap[c] -= flow;
                }
                ap[0] = 0;
                double alpha = rr / Math.max(dot(dir, ap), 1.0E-300);
                for (int i = 1; i < s; i++) {
                    phi[i] += alpha * dir[i];
                    res[i] -= alpha * ap[i];
                }
                double next = dot(res, res);
                double beta = next / rr;
                for (int i = 1; i < s; i++) {
                    dir[i] = res[i] + beta * dir[i];
                }
                rr = next;
            }
            return phi;
        }

        private static double dot(double[] a, double[] b) {
            double sum = 0;
            for (int i = 0; i < a.length; i++) {
                sum += a[i] * b[i];
            }
            return sum;
        }

        int edgeIndex(long id) {
            if (edgeIndex == null) {
                edgeIndex = new HashMap<>();
                for (int e = 0; e < t.m; e++) {
                    edgeIndex.put(t.edges[e].id(), e);
                }
            }
            return edgeIndex.get(id);
        }

        // The protective links from s to tt in order (wire indices).
        int[] protectiveBetween(int s, int tt, Map<Long, Protective> protective) {
            if (t.tree) {
                if (protectiveUp == null) {
                    protectiveUp = new int[t.n][];
                    protectiveUp[t.order[0]] = new int[0];
                    for (int k = 1; k < t.n; k++) {
                        int v = t.order[k], e = t.parentEdge[v];
                        int[] above = protectiveUp[t.parent[v]];
                        if (protective.containsKey(t.edges[e].id())) {
                            int[] mine = new int[above.length + 1];
                            mine[0] = e;
                            System.arraycopy(above, 0, mine, 1, above.length);
                            protectiveUp[v] = mine;
                        } else {
                            protectiveUp[v] = above;
                        }
                    }
                }
                int lca = t.lca(s, tt);
                List<Integer> out = new ArrayList<>();
                for (int e : protectiveUp[s]) {
                    if (t.depth[child(e)] > t.depth[lca]) {
                        out.add(e);
                    }
                }
                List<Integer> down = new ArrayList<>();
                for (int e : protectiveUp[tt]) {
                    if (t.depth[child(e)] > t.depth[lca]) {
                        down.add(0, e);
                    }
                }
                out.addAll(down);
                return out.stream().mapToInt(Integer::intValue).toArray();
            }
            GridNetwork.Route route = network.route(t.ids[s], t.ids[tt]);
            if (route == null) {
                return new int[0];
            }
            List<Integer> out = new ArrayList<>();
            for (GridEdge edge : route.edges()) {
                if (protective.containsKey(edge.id())) {
                    out.add(edgeIndex(edge.id()));
                }
            }
            return out.stream().mapToInt(Integer::intValue).toArray();
        }

        private int child(int e) {
            return t.depth[t.ea[e]] > t.depth[t.eb[e]] ? t.ea[e] : t.eb[e];
        }

        // A pair's loss share per amp between two nodes (the sum of loss over gross on the wires between).
        double lossPerAmp(int s, int tt) {
            if (t.tree) {
                return lossPerGross[s] + lossPerGross[tt] - 2 * lossPerGross[t.lca(s, tt)];
            }
            GridNetwork.Route route = network.route(t.ids[s], t.ids[tt]);
            double sum = 0;
            if (route != null) {
                for (GridEdge edge : route.edges()) {
                    int e = edgeIndex(edge.id());
                    sum += gross[e] > 1.0E-12 ? loss[e] / gross[e] : 0;
                }
            }
            return sum;
        }

        // The regulator boosts from s to tt (per unit, added).
        double boostBetween(int s, int tt) {
            if (!boosted) {
                return 0;
            }
            if (t.tree) {
                return boost[tt] - boost[s];
            }
            GridNetwork.Route route = network.route(t.ids[s], t.ids[tt]);
            double sum = 0;
            if (route != null) {
                for (int i = 0; i < route.edges().size(); i++) {
                    double b = route.edges().get(i).boost();
                    if (b != 0) {
                        sum += route.forward().get(i) ? b : 1 / (1 + b) - 1;
                    }
                }
            }
            return sum;
        }
    }

    // ================================================================================================================
    // Pairs
    // ================================================================================================================

    // One conductor of a segment: its network, its two ends (node indices), the angle of its current, and whether it's
    // a neutral.
    private record Cond(Net net, int s, int t, double cos, double sin, boolean neutral) {}

    // A pair's run through one circuit: its tier, conductors, and the crossing it then makes (null at the end).
    private record Seg(VoltageTier tier, Cond[] conds, @Nullable Crossing next) {}

    // One way a pair's power goes (one per choice among parallel transformers): its share and segments.
    private record Way(double fraction, Seg[] segs) {}

    private static final class Pair {
        final Side source, sink;
        final Way[] ways;
        final int island;
        // Real power from source to sink, or reactive only (a bank's or reactor's).
        final boolean reactive;
        double power, vars;

        Pair(Side source, Side sink, Way[] ways, int island, boolean reactive) {
            this.source = source;
            this.sink = sink;
            this.ways = ways;
            this.island = island;
            this.reactive = reactive;
        }
    }

    // An island's sides in their roles, its pairs by kind, and this round's offers.
    private static final class IslandSides {
        final int id;
        final List<Side> sources = new ArrayList<>(), sinks = new ArrayList<>(), stores = new ArrayList<>(), caps = new ArrayList<>(),
                reactors = new ArrayList<>();
        final List<Pair> real = new ArrayList<>(), capToLoad = new ArrayList<>(), capToReactor = new ArrayList<>(), sourceToReactor = new ArrayList<>(),
                capToSource = new ArrayList<>();
        final Map<Side, Double> offer = new IdentityHashMap<>();

        IslandSides(int id) {
            this.id = id;
        }
    }

    // ================================================================================================================
    // A solve
    // ================================================================================================================

    private static final class Solve {
        final Context ctx;
        final GridGraph graph;
        final GridSettings settings;
        final State state;
        final Map<Long, Protective> protective;
        final Map<GridNetwork, Net> nets = new IdentityHashMap<>();
        final List<Pair> pairs = new ArrayList<>();
        final Map<Integer, IslandSides> islands = new TreeMap<>();
        final Map<Long, Double> drawn = new TreeMap<>(), delivered = new TreeMap<>(), bills = new HashMap<>();
        // Each giver's offer this round (sources and batteries giving), by id.
        final Map<Long, Double> offers = new HashMap<>();
        final Map<Long, Double> portVolts = new TreeMap<>();

        Solve(Context ctx, GridGraph graph, GridSettings settings, State state, Map<Long, Protective> protective) {
            this.ctx = ctx;
            this.graph = graph;
            this.settings = settings;
            this.state = state;
            this.protective = protective;
        }

        Net net(GridNetwork network) {
            return nets.computeIfAbsent(network, n -> new Net(n, settings));
        }

        Result run() {
            buildPairs();
            int rounds = Math.max(1, settings.voltageRounds());
            for (int round = 0; round < rounds; round++) {
                assign();
                flow();
                sinkVolts();
                updateShares();
            }
            Map<Long, PortStatus> status = statuses();
            Map<Long, Double> edgeAmps = new TreeMap<>(), edgeLoss = new TreeMap<>();
            double totalLoss = 0;
            for (Net net : nets.values()) {
                for (int e = 0; e < net.t.m; e++) {
                    double amps = Math.hypot(net.curRe[e], net.curIm[e]);
                    if (amps < 1.0E-9) {
                        amps = 0;
                    }
                    if (amps > 0 || net.gross[e] > 0) {
                        edgeAmps.put(net.t.edges[e].id(), amps);
                        edgeLoss.put(net.t.edges[e].id(), net.loss[e]);
                        totalLoss += net.loss[e];
                    }
                }
            }
            Map<Long, Double> couplingFlow = new TreeMap<>();
            for (Transformer transformer : ctx.transformers) {
                couplingFlow.put(transformer.coupling.id(), 0.0);
            }
            for (Pair pair : pairs) {
                if (pair.power <= 0) {
                    continue;
                }
                for (Way way : pair.ways) {
                    for (Seg seg : way.segs) {
                        if (seg.next != null) {
                            couplingFlow.merge(seg.next.transformer.coupling.id(), (seg.next.down ? 1 : -1) * pair.power * way.fraction, Double::sum);
                        }
                    }
                }
            }
            Map<Long, Double> nodeVolts = nodeVolts();
            // Sources show their setpoint even with nothing taken.
            for (Side side : ctx.ports.values()) {
                if (side.port.offer() > 1.0E-9 && ctx.liveIslands.contains(side.island)) {
                    portVolts.putIfAbsent(side.id, side.port.setpoint());
                }
            }
            Set<Long> keys = new HashSet<>();
            for (Port port : ctx.portById.values()) {
                keys.add(key(port));
            }
            state.share.keySet().retainAll(keys);
            return new Result(new TreeMap<>(drawn), new TreeMap<>(delivered), edgeAmps, edgeLoss, status, totalLoss, new TreeMap<>(portVolts), nodeVolts,
                    couplingFlow, ctx.liveNetworks, ctx.sourceLive, phaseLoss(), groundFaults(), protective.isEmpty() ? Map.of() : beyond());
        }

        static long key(Port port) {
            return port.nodes().isEmpty() ? -port.id() : port.nodes().get(0);
        }

        // --- Building the pairs, once a tick ------------------------------------------------------------------------

        // Each source's real pairs (for scaling a source back).
        final Map<Long, List<Pair>> bySource = new HashMap<>();

        void buildPairs() {
            for (Side side : ctx.ports.values()) {
                if (!ctx.liveIslands.contains(side.island)) {
                    continue;
                }
                IslandSides island = islands.computeIfAbsent(side.island, IslandSides::new);
                Port port = side.port;
                if (port.storage()) {
                    island.stores.add(side);
                } else {
                    if (port.offer() > 1.0E-9) {
                        island.sources.add(side);
                    }
                    if (port.demand() > 1.0E-9) {
                        island.sinks.add(side);
                    }
                }
            }
            for (Side side : ctx.shunts) {
                IslandSides island = islands.get(side.island);
                if (island == null) {
                    continue;
                }
                if (side.shunt.vars() > 0) {
                    island.caps.add(side);
                } else if (side.shunt.vars() < 0) {
                    island.reactors.add(side);
                }
            }
            for (IslandSides island : islands.values()) {
                List<Side> givers = new ArrayList<>(island.sources), takers = new ArrayList<>(island.sinks);
                givers.addAll(island.stores);
                takers.addAll(island.stores);
                for (Side sink : takers) {
                    for (Side source : givers) {
                        if (source == sink || source.port.storage() && sink.port.storage()) {
                            continue;
                        }
                        add(island.real, source, sink, island.id, false);
                    }
                }
                for (Side cap : island.caps) {
                    for (Side sink : island.sinks) {
                        add(island.capToLoad, cap, sink, island.id, true);
                    }
                    for (Side reactor : island.reactors) {
                        add(island.capToReactor, cap, reactor, island.id, true);
                    }
                    for (Side source : island.sources) {
                        add(island.capToSource, cap, source, island.id, true);
                    }
                }
                for (Side reactor : island.reactors) {
                    for (Side source : island.sources) {
                        add(island.sourceToReactor, source, reactor, island.id, true);
                    }
                }
            }
        }

        private void add(List<Pair> list, Side from, Side to, int island, boolean reactive) {
            List<List<Crossing>> hops = ctx.hops(from.circuit, to.circuit);
            if (hops == null) {
                return;
            }
            List<Way> ways = new ArrayList<>();
            build(from, to, hops, 0, 1.0, new ArrayList<>(), ways);
            if (!ways.isEmpty()) {
                Pair pair = new Pair(from, to, ways.toArray(new Way[0]), island, reactive);
                list.add(pair);
                pairs.add(pair);
                if (!reactive) {
                    bySource.computeIfAbsent(from.id, k -> new ArrayList<>()).add(pair);
                }
            }
        }

        // A pair's ways through the transformers between its circuits: one per choice among parallel transformers, each
        // its share of their ratings.
        private void build(Side entry, Side to, List<List<Crossing>> hops, int h, double fraction, List<Seg> segs, List<Way> out) {
            if (h == hops.size()) {
                Cond[] conds = conds(entry, to);
                if (conds != null) {
                    List<Seg> all = new ArrayList<>(segs);
                    all.add(new Seg(entry.tier, conds, null));
                    out.add(new Way(fraction, all.toArray(new Seg[0])));
                }
                return;
            }
            List<Crossing> group = hops.get(h);
            double total = 0;
            for (Crossing crossing : group) {
                total += Math.max(crossing.transformer.coupling.rating(), 0);
            }
            for (Crossing crossing : group) {
                Cond[] conds = conds(entry, crossing.leave);
                if (conds == null) {
                    continue;
                }
                double share = total > 0 ? Math.max(crossing.transformer.coupling.rating(), 0) / total : 1.0 / group.size();
                List<Seg> next = new ArrayList<>(segs);
                next.add(new Seg(entry.tier, conds, crossing));
                build(crossing.enter, to, hops, h + 1, fraction * share, next, out);
            }
        }

        // A segment's conductors: for each network, the entry's insulator on it to the exit's, at the entry's phase.
        private Cond @Nullable [] conds(Side from, Side to) {
            Cond[] out = new Cond[from.nodeOfNetwork.size()];
            int i = 0;
            for (Map.Entry<Long, Long> entry : from.nodeOfNetwork.entrySet()) {
                Long end = to.nodeOfNetwork.get(entry.getKey());
                GridNetwork network = graph.networkOf(entry.getValue());
                if (end == null || network == null) {
                    return null;
                }
                Net net = net(network);
                Phase phase = graph.node(entry.getValue()).phase();
                double angle = CircuitMath.angle(phase, from.labels);
                out[i++] = new Cond(net, net.t.index.get(entry.getValue()), net.t.index.get(end), Math.cos(angle), Math.sin(angle), phase.isNeutral());
            }
            return out;
        }

        // --- Powers, each voltage round -------------------------------------------------------------------------------

        double asked(Side sink) {
            Port port = sink.port;
            return port.demand() * Math.max(state.share(key(port)), settings.minShare());
        }

        void assign() {
            offers.clear();
            double tan = Math.tan(Math.acos(Math.max(0.0, Math.min(1.0, settings.loadPowerFactor()))));
            for (IslandSides island : islands.values()) {
                double supply = 0, wanted = 0, storeOffer = 0, storeRoom = 0;
                island.offer.clear();
                Map<Side, Double> demand = new IdentityHashMap<>();
                for (Side s : island.sources) {
                    island.offer.put(s, s.port.offer());
                    supply += s.port.offer();
                }
                for (Side s : island.sinks) {
                    demand.put(s, asked(s));
                    wanted += asked(s);
                }
                for (Side s : island.stores) {
                    storeOffer += s.port.offer();
                    storeRoom += s.port.demand();
                }
                // Batteries: cover the shortfall, or take the surplus.
                if (wanted > supply + 1.0E-9 && storeOffer > 1.0E-9) {
                    double share = Math.min(1.0, (wanted - supply) / storeOffer);
                    for (Side s : island.stores) {
                        island.offer.put(s, s.port.offer() * share);
                        supply += s.port.offer() * share;
                    }
                } else if (supply > wanted + 1.0E-9 && storeRoom > 1.0E-9) {
                    double share = Math.min(1.0, (supply - wanted) / storeRoom);
                    for (Side s : island.stores) {
                        demand.put(s, s.port.demand() * share);
                        wanted += s.port.demand() * share;
                    }
                }
                double served = Math.min(supply, wanted);
                // Reactive power wanted (loads' at their power factor, reactors') and supplied by the banks.
                double loadVars = 0, reactorVars = 0, capVars = 0;
                for (Side s : island.caps) {
                    capVars += s.shunt.vars();
                }
                for (Side s : island.reactors) {
                    reactorVars -= s.shunt.vars();
                }
                Map<Side, Double> toSink = new IdentityHashMap<>();
                for (Map.Entry<Side, Double> d : demand.entrySet()) {
                    double to = wanted > 1.0E-12 ? d.getValue() * served / wanted : 0;
                    toSink.put(d.getKey(), to);
                    if (!d.getKey().port.storage()) {
                        loadVars += to * tan;
                    }
                }
                double varsWanted = loadVars + reactorVars, covered = Math.min(capVars, varsWanted);
                double fromSources = varsWanted > 1.0E-9 ? 1.0 - covered / varsWanted : 1.0;
                for (Pair pair : island.real) {
                    double o = island.offer.getOrDefault(pair.source, 0.0), to = toSink.getOrDefault(pair.sink, 0.0);
                    double others = supply - island.offer.getOrDefault(pair.sink, 0.0);
                    if (o <= 1.0E-12 || to <= 1.0E-12 || others <= 1.0E-12) {
                        pair.power = pair.vars = 0;
                        continue;
                    }
                    pair.power = to * o / others;
                    pair.vars = pair.sink.port.storage() ? 0 : pair.power * tan * fromSources;
                }
                for (Pair pair : island.capToLoad) {
                    pair.power = 0;
                    pair.vars = capVars > 0 ? toSink.getOrDefault(pair.sink, 0.0) * tan * (1 - fromSources) * pair.source.shunt.vars() / capVars : 0;
                }
                for (Pair pair : island.capToReactor) {
                    pair.power = 0;
                    pair.vars = capVars > 0 ? -pair.sink.shunt.vars() * (1 - fromSources) * pair.source.shunt.vars() / capVars : 0;
                }
                for (Pair pair : island.sourceToReactor) {
                    pair.power = 0;
                    pair.vars = supply > 0 ? -pair.sink.shunt.vars() * fromSources * island.offer.getOrDefault(pair.source, 0.0) / supply : 0;
                }
                island.offer.forEach((side, o) -> offers.put(side.id, o));
                double surplus = capVars - covered;
                for (Pair pair : island.capToSource) {
                    pair.power = 0;
                    pair.vars = surplus > 1.0E-9 && supply > 0 ? surplus * pair.source.shunt.vars() / capVars * island.offer.getOrDefault(pair.sink, 0.0)
                            / supply : 0;
                }
            }
        }

        // --- Flow: currents, losses, scaling --------------------------------------------------------------------------

        void flow() {
            // A port's limit caps only its own pairs (what starts or ends there), never traffic passing its insulators.
            Map<Side, List<Pair>> byLimited = new IdentityHashMap<>();
            for (Pair pair : pairs) {
                for (Side end : new Side[] { pair.source, pair.sink }) {
                    if (end.port != null && end.port.limitAmps() < Double.POSITIVE_INFINITY) {
                        byLimited.computeIfAbsent(end, k -> new ArrayList<>()).add(pair);
                    }
                }
            }
            Map<Transformer, List<Pair>> crossingPairs = new IdentityHashMap<>();
            Map<Transformer, List<Double>> crossingShare = new IdentityHashMap<>();
            for (Pair pair : pairs) {
                for (Way way : pair.ways) {
                    for (Seg seg : way.segs) {
                        if (seg.next != null) {
                            crossingPairs.computeIfAbsent(seg.next.transformer, k -> new ArrayList<>()).add(pair);
                            crossingShare.computeIfAbsent(seg.next.transformer, k -> new ArrayList<>()).add(way.fraction);
                        }
                    }
                }
            }
            boolean converged = false;
            for (int round = 0; round < ROUNDS && !converged; round++) {
                boolean scaled = false;
                for (Map.Entry<Side, List<Pair>> entry : byLimited.entrySet()) {
                    double limit = Math.max(0, entry.getKey().port.limitAmps()), through = 0;
                    for (Pair pair : entry.getValue()) {
                        through += Math.hypot(pair.power, pair.vars) / entry.getKey().tier.volts();
                    }
                    if (through > limit + 1.0E-9) {
                        double factor = limit / through;
                        entry.getValue().forEach(pair -> scale(pair, factor));
                        scaled = true;
                    }
                }
                for (Map.Entry<Transformer, List<Pair>> entry : crossingPairs.entrySet()) {
                    double through = 0;
                    List<Double> shares = crossingShare.get(entry.getKey());
                    for (int i = 0; i < entry.getValue().size(); i++) {
                        Pair pair = entry.getValue().get(i);
                        through += Math.hypot(pair.power, pair.vars) * shares.get(i);
                    }
                    double rating = entry.getKey().coupling.rating();
                    if (through > rating + 1.0E-9) {
                        double factor = rating / through;
                        Set<Pair> once = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                        once.addAll(entry.getValue());
                        once.forEach(pair -> scale(pair, factor));
                        scaled = true;
                    }
                }
                currents();
                scaled |= bill(false);
                converged = !scaled;
            }
            if (!converged) {
                // Out of rounds: the last scaling stands, and its losses are paid (each source its bill, never past its
                // offer).
                currents();
                bill(true);
                currents();
                bill(false);
            }
            drawn.clear();
            delivered.clear();
            for (Pair pair : pairs) {
                if (!pair.reactive && pair.power > 0) {
                    delivered.merge(pair.sink.id, pair.power, Double::sum);
                }
            }
            drawn.putAll(bills);
            drawn.keySet().retainAll(ctx.portById.keySet());
            delivered.keySet().retainAll(ctx.portById.keySet());
        }

        private static void scale(Pair pair, double factor) {
            pair.power *= factor;
            pair.vars *= factor;
        }

        // Every wire's current, from every pair's current in at one end and out at the other.
        void currents() {
            for (Net net : nets.values()) {
                net.clear();
            }
            for (Pair pair : pairs) {
                if (pair.power == 0 && pair.vars == 0) {
                    continue;
                }
                for (Way way : pair.ways) {
                    for (Seg seg : way.segs) {
                        double real = pair.power * way.fraction / seg.tier.volts(), reactive = pair.vars * way.fraction / seg.tier.volts();
                        double size = Math.hypot(real, reactive);
                        for (Cond c : seg.conds) {
                            if (c.s == c.t) {
                                continue;
                            }
                            // Real at the conductor's angle; reactive (lagging) a quarter turn behind it.
                            double re = real * c.cos + reactive * c.sin, im = real * c.sin - reactive * c.cos;
                            Net net = c.net;
                            net.injRe[c.s] += re;
                            net.injIm[c.s] += im;
                            net.injRe[c.t] -= re;
                            net.injIm[c.t] -= im;
                            if (net.t.tree) {
                                net.injGross[c.s] += size;
                                net.injGross[c.t] += size;
                                net.injGross[net.t.lca(c.s, c.t)] -= 2 * size;
                            }
                        }
                    }
                }
            }
            for (Net net : nets.values()) {
                net.solve();
            }
        }

        // Each source's bill: its pairs' deliveries and loss shares, and its share (by offer) of its own island's reactive
        // losses. If one owes more than it offers, its pairs are scaled back (battery charging first; at the last, simply
        // in proportion, so it can pay). True if anything was scaled.
        boolean bill(boolean last) {
            bills.clear();
            Map<Integer, Double> reactiveLoss = new HashMap<>();
            Map<Long, Double> delivering = new HashMap<>();
            for (Pair pair : pairs) {
                if (pair.power == 0 && pair.vars == 0) {
                    continue;
                }
                double total = pair.reactive ? 0 : pair.power;
                for (Way way : pair.ways) {
                    for (Seg seg : way.segs) {
                        double amps = Math.hypot(pair.power, pair.vars) * way.fraction / seg.tier.volts();
                        for (Cond c : seg.conds) {
                            if (c.s != c.t) {
                                total += amps * c.net.lossPerAmp(c.s, c.t);
                            }
                        }
                    }
                }
                if (pair.reactive) {
                    reactiveLoss.merge(pair.island, total, Double::sum);
                } else if (pair.power > 0) {
                    bills.merge(pair.source.id, total, Double::sum);
                    delivering.merge(pair.source.id, pair.power, Double::sum);
                }
            }
            for (IslandSides island : islands.values()) {
                double loss = reactiveLoss.getOrDefault(island.id, 0.0), offered = 0;
                for (double o : island.offer.values()) {
                    offered += o;
                }
                if (loss > 0 && offered > 0) {
                    for (Map.Entry<Side, Double> o : island.offer.entrySet()) {
                        bills.merge(o.getKey().id, loss * o.getValue() / offered, Double::sum);
                    }
                }
            }
            boolean scaled = false;
            for (Map.Entry<Long, Double> owed : bills.entrySet()) {
                long source = owed.getKey();
                double can = offerOf(source);
                if (owed.getValue() <= can * (1 + 1.0E-9) + 1.0E-9) {
                    continue;
                }
                scaled = true;
                List<Pair> mine = bySource.getOrDefault(source, List.of());
                if (last) {
                    double factor = can / owed.getValue();
                    mine.forEach(p -> scale(p, factor));
                    continue;
                }
                // A battery charging from it gives way first.
                double charging = 0;
                for (Pair pair : mine) {
                    if (pair.sink.port != null && pair.sink.port.storage()) {
                        charging += pair.power;
                    }
                }
                if (charging > 1.0E-9) {
                    double keep = Math.max(0.0, 1.0 - (owed.getValue() - can) * 1.05 / charging);
                    mine.stream().filter(p -> p.sink.port != null && p.sink.port.storage()).forEach(p -> scale(p, keep));
                    continue;
                }
                // Scaled so that what it delivers (d, scaling with the factor) plus its losses (l, scaling with its
                // square) comes to what it can give: f d + f^2 l = can.
                double d = delivering.getOrDefault(source, 0.0), l = Math.max(0, owed.getValue() - d);
                double factor = l > 1.0E-12 ? (-d + Math.sqrt(d * d + 4 * l * can)) / (2 * l) : can / owed.getValue();
                mine.forEach(p -> scale(p, factor));
            }
            return scaled;
        }

        private double offerOf(long source) {
            return offers.getOrDefault(source, 0.0);
        }

        // --- Voltages ---------------------------------------------------------------------------------------------------

        // A conductor's drop from its start to its end (per unit of its tier), its regulator boosts taken off.
        private static double drop(Cond c, double volts) {
            Net net = c.net;
            double dre = net.pRe[c.s] - net.pRe[c.t], dim = net.pIm[c.s] - net.pIm[c.t];
            return (dre * c.cos + dim * c.sin) / volts - net.boostBetween(c.s, c.t);
        }

        // Each port's voltage: a load's the average over its pairs (by power) of its sources' setpoints less the drops.
        void sinkVolts() {
            Map<Long, double[]> sums = new HashMap<>();
            for (Pair pair : pairs) {
                if (pair.reactive || pair.power <= 0) {
                    continue;
                }
                double setpoint = pair.source.port != null ? pair.source.port.setpoint() : 1.0, sum = 0;
                for (Way way : pair.ways) {
                    sum += way.fraction * walk(setpoint, way, null, 0);
                }
                double[] s = sums.computeIfAbsent(pair.sink.id, k -> new double[2]);
                s[0] += Math.max(0, sum) * pair.power;
                s[1] += pair.power;
                double[] src = sums.computeIfAbsent(pair.source.id, k -> new double[2]);
                src[0] += setpoint * pair.power;
                src[1] += pair.power;
            }
            portVolts.clear();
            sums.forEach((id, s) -> portVolts.put(id, s[0] / s[1]));
        }

        // The voltage at a way's end from `volts` at its start, noting where it enters each network (for node voltages).
        private double walk(double volts, Way way, @Nullable Map<Net, Map<Long, double[]>> entries, double weight) {
            for (Seg seg : way.segs) {
                double lineDrop = 0, neutralDrop = 0;
                int lines = 0, neutrals = 0;
                for (Cond c : seg.conds) {
                    if (entries != null && !c.neutral) {
                        long key = ((long) c.s << 8) ^ Math.round(Math.atan2(c.sin, c.cos) * 40);
                        double[] e = entries.computeIfAbsent(c.net, k -> new HashMap<>()).computeIfAbsent(key, k -> new double[] { c.s, c.cos, c.sin, 0, 0 });
                        e[3] += volts * weight;
                        e[4] += weight;
                    }
                    double d = c.s == c.t ? 0 : drop(c, seg.tier.volts());
                    if (c.neutral) {
                        neutralDrop += d;
                        neutrals++;
                    } else {
                        lineDrop += d;
                        lines++;
                    }
                }
                volts -= (lines > 0 ? lineDrop / lines : 0) + (neutrals > 0 ? neutralDrop / neutrals : 0);
                if (seg.next != null) {
                    double tap = seg.next.transformer.coupling.tap();
                    volts = seg.next.down ? volts * (1 + tap) : volts / (1 + tap);
                }
            }
            return volts;
        }

        // Each line node's voltage where power flows: from each place power enters its network (by power), its voltage
        // there less the drop on to the node. Only real power counts (a bank's reactive pairs start from nothing real).
        Map<Long, Double> nodeVolts() {
            Map<Net, Map<Long, double[]>> entries = new IdentityHashMap<>();
            for (Pair pair : pairs) {
                if (pair.reactive || pair.power <= 0) {
                    continue;
                }
                double setpoint = pair.source.port != null ? pair.source.port.setpoint() : 1.0;
                for (Way way : pair.ways) {
                    walk(setpoint, way, entries, pair.power * way.fraction);
                }
            }
            Map<Long, Double> out = new HashMap<>();
            for (Map.Entry<Net, Map<Long, double[]>> entry : entries.entrySet()) {
                Net net = entry.getKey();
                double volts = net.tier.volts();
                for (int v = 0; v < net.t.n; v++) {
                    double sum = 0, weight = 0;
                    for (double[] e : entry.getValue().values()) {
                        int s = (int) e[0];
                        double dre = net.pRe[s] - net.pRe[v], dim = net.pIm[s] - net.pIm[v];
                        sum += (e[3] / e[4] - (dre * e[1] + dim * e[2]) / volts + net.boostBetween(s, v)) * e[4];
                        weight += e[4];
                    }
                    if (weight > 0) {
                        out.put(net.t.ids[v], Math.max(0, sum / weight));
                    }
                }
            }
            return out;
        }

        // Each load's share follows its voltage (half way each round, so it settles rather than swings), never under the
        // floor; a load with no reading (nothing reached it) comes back toward its full share.
        void updateShares() {
            for (Port port : ctx.portById.values()) {
                if (port.demand() <= 1.0E-9 || port.storage()) {
                    continue;
                }
                Double volts = portVolts.get(port.id());
                double target = volts == null ? 1.0 : share(volts, settings);
                long key = key(port);
                double now = state.share(key);
                state.share.put(key, Math.max(settings.minShare(), now + 0.5 * (target - now)));
            }
        }

        // --- What else the tick says ----------------------------------------------------------------------------------

        // OK only when actually paired (giving or taking); otherwise no partner on a live line, or dead.
        Map<Long, PortStatus> statuses() {
            Map<Long, PortStatus> status = new TreeMap<>();
            Set<Long> paired = new HashSet<>();
            for (Pair pair : pairs) {
                if (!pair.reactive && pair.power > 1.0E-9) {
                    paired.add(pair.source.id);
                    paired.add(pair.sink.id);
                }
            }
            for (Side side : ctx.ports.values()) {
                status.put(side.id, paired.contains(side.id) ? PortStatus.OK
                        : ctx.liveIslands.contains(side.island) ? PortStatus.NO_PARTNER : PortStatus.DEAD);
            }
            return status;
        }

        // A whole three-phase port in a dead circuit with some of its conductors on live networks has lost a phase.
        Set<Long> phaseLoss() {
            Set<Long> out = new TreeSet<>();
            for (Side side : ctx.ports.values()) {
                if (!ctx.liveIslands.contains(side.island)) {
                    long live = side.nodeOfNetwork.keySet().stream().filter(ctx.liveNetworks::contains).count();
                    if (live > 0 && live < side.nodeOfNetwork.size()) {
                        out.add(side.id);
                    }
                }
            }
            return out;
        }

        // A grounded line a source would reach with the ground switches open: a fault. Worked out from the circuits as
        // they'd be ungrounded (the islands only, not a second solve).
        Set<Long> groundFaults() {
            if (ctx.groundedNetworks.isEmpty()) {
                return Set.of();
            }
            Context open = new Context(graph, ctx.portById.values(), ctx.transformers.stream().map(Transformer::coupling).toList(), List.of(), Set.of(),
                    new TreeMap<>());
            Set<Long> out = new TreeSet<>();
            for (long network : ctx.groundedNetworks) {
                if (open.liveNetworks.contains(network)) {
                    out.add(network);
                }
            }
            return out;
        }

        // For each protective link, the overloaded devices past it on the way to the loads: on its own conductor, then in
        // the later segments (each counted along its busiest conductor); the most on any one way.
        Map<Long, Beyond> beyond() {
            Map<Long, Beyond> out = new HashMap<>();
            for (Pair pair : pairs) {
                if (pair.reactive || pair.power <= 0) {
                    continue;
                }
                for (Way way : pair.ways) {
                    int segs = way.segs.length;
                    int[][][] links = new int[segs][][];
                    // Each later segment's overloaded devices (count, all fuses, least fuse rating) along its busiest conductor.
                    double[][] later = new double[segs + 1][];
                    later[segs] = new double[] { 0, 1, 0 };
                    for (int i = segs - 1; i >= 0; i--) {
                        Seg seg = way.segs[i];
                        links[i] = new int[seg.conds.length][];
                        double[] best = { 0, 1, 0 };
                        for (int c = 0; c < seg.conds.length; c++) {
                            Cond cond = seg.conds[c];
                            links[i][c] = cond.s == cond.t ? new int[0] : cond.net.protectiveBetween(cond.s, cond.t, protective);
                            double[] mine = overloads(cond.net, links[i][c], 0);
                            if (mine[0] > best[0]) {
                                best = mine;
                            }
                        }
                        double[] next = later[i + 1];
                        later[i] = new double[] { best[0] + next[0], best[1] == 1 && next[1] == 1 ? 1 : 0, minRating(best[2], next[2]) };
                    }
                    for (int i = 0; i < segs; i++) {
                        Seg seg = way.segs[i];
                        for (int c = 0; c < seg.conds.length; c++) {
                            Cond cond = seg.conds[c];
                            int[] path = links[i][c];
                            for (int a = 0; a < path.length; a++) {
                                double[] after = overloads(cond.net, path, a + 1);
                                double[] rest = later[i + 1];
                                int count = (int) (after[0] + rest[0]);
                                boolean allFuses = count > 0 && after[1] == 1 && rest[1] == 1;
                                merge(out, cond.net.t.edges[path[a]].id(), new Beyond(count, allFuses, minRating(after[2], rest[2])));
                            }
                        }
                    }
                }
            }
            return out;
        }

        // The overloaded devices among links[from..]: {count, 1 if all fuses (or none), least fuse rating}.
        private double[] overloads(Net net, int[] links, int from) {
            double count = 0, all = 1, rating = 0;
            for (int b = from; b < links.length; b++) {
                Protective p = protective.get(net.t.edges[links[b]].id());
                if (p != null && Math.hypot(net.curRe[links[b]], net.curIm[links[b]]) > p.pickup() + 1.0E-9) {
                    count++;
                    if (p.fuse()) {
                        rating = minRating(rating, p.pickup());
                    } else {
                        all = 0;
                    }
                }
            }
            return new double[] { count, all, rating };
        }
    }

    private static double minRating(double a, double b) {
        return a == 0 ? b : b == 0 ? a : Math.min(a, b);
    }

    // Keeps the one with the most devices past it (on a tie, the least fuse rating).
    private static void merge(Map<Long, Beyond> out, long id, Beyond b) {
        Beyond now = out.get(id);
        if (now == null || b.count() > now.count() || b.count() == now.count() && b.fuseRating() > 0 && (now.fuseRating() == 0
                || b.fuseRating() < now.fuseRating())) {
            out.put(id, b);
        }
    }

    // ================================================================================================================
    // Fault study
    // ================================================================================================================

    // What a fault (a conductor to ground) at a node draws: each live source's current to it, limited by the source's own
    // impedance and the route's resistance and reactance (referred to the fault's tier through the transformers between),
    // every contribution scaled by the same ratio so the total is at most faultMultiple times the fault tier's rating,
    // added up on every link and wire it passes; and for each protective link on the way, the devices between it and the
    // fault (the fewest on any way). NONE if no source reaches it.
    public record FaultFlow(double amps, Map<Long, Double> edgeAmps, Map<Long, Beyond> beyondInfo) {
        public static final FaultFlow NONE = new FaultFlow(0, Map.of(), Map.of());

        public double amps(long edge) {
            return edgeAmps.getOrDefault(edge, 0.0);
        }

        public int beyond(long edge) {
            return beyond(edge, Beyond.NONE).count();
        }

        public Beyond beyond(long edge, Beyond fallback) {
            return beyondInfo.getOrDefault(edge, fallback);
        }
    }

    public static FaultFlow fault(GridGraph graph, Collection<Port> ports, Collection<Coupling> couplings, long faultNode, Set<Long> protective,
            GridSettings settings) {
        return fault(graph, ports, couplings, faultNode, asProtective(protective), settings);
    }

    public static FaultFlow fault(GridGraph graph, Collection<Port> ports, Collection<Coupling> couplings, long faultNode, Map<Long, Protective> protective,
            GridSettings settings) {
        GridNetwork faulted = graph.networkOf(faultNode);
        if (faulted == null) {
            return FaultFlow.NONE;
        }
        Context ctx = new Context(graph, ports, couplings, List.of(), Set.of(), new TreeMap<>());
        String circuit = null;
        for (Map.Entry<String, Set<Long>> c : ctx.networksOf.entrySet()) {
            if (c.getValue().contains(faulted.id()) && ctx.liveIslands.contains(ctx.island.get(c.getKey()))) {
                circuit = c.getKey();
                break;
            }
        }
        if (circuit == null) {
            return FaultFlow.NONE;
        }
        VoltageTier tier = graph.node(faultNode).tier();
        double limit = settings.faultMultiple() * settings.ratedAmps(tier);
        List<Double> contributions = new ArrayList<>();
        List<List<long[]>> paths = new ArrayList<>();
        Map<Long, Beyond> beyond = new HashMap<>();
        for (Side source : ctx.ports.values()) {
            if (source.port.offer() <= 1.0E-9 || !ctx.island.get(source.circuit).equals(ctx.island.get(circuit))) {
                continue;
            }
            List<List<Crossing>> hops = ctx.hops(source.circuit, circuit);
            if (hops == null) {
                continue;
            }
            // Ohms referred to the fault's tier: the source's own (reactive), then one conductor's way (the first
            // transformer of any parallel group), the faulted conductor in the last segment.
            double r = 0, x = settings.sourceImpedance(source.tier) * Math.pow((double) tier.volts() / source.tier.volts(), 2);
            List<long[]> edges = new ArrayList<>();
            List<GridEdge> ordered = new ArrayList<>();
            Side entry = source;
            boolean ok = true;
            for (int h = 0; h <= hops.size() && ok; h++) {
                Side exit = h < hops.size() ? hops.get(h).get(0).leave : null;
                double refer = Math.pow((double) tier.volts() / entry.tier.volts(), 2), scale = (double) tier.volts() / entry.tier.volts();
                boolean first = true;
                for (Map.Entry<Long, Long> start : entry.nodeOfNetwork.entrySet()) {
                    Long end = exit != null ? exit.nodeOfNetwork.get(start.getKey()) : start.getKey() == faulted.id() ? Long.valueOf(faultNode) : null;
                    if (end == null) {
                        continue;
                    }
                    GridNetwork network = graph.networkOf(start.getValue());
                    GridNetwork.Route route = network == null ? null : network.route(start.getValue(), end);
                    if (route == null) {
                        ok = false;
                        break;
                    }
                    for (GridEdge edge : route.edges()) {
                        edges.add(new long[] { edge.id(), Double.doubleToLongBits(scale) });
                        ordered.add(edge);
                        if (first) {
                            r += edge.resistance(settings) * refer;
                            x += settings.reactance(edge.tier()) * edge.length() * refer;
                        }
                    }
                    first = false;
                }
                if (h < hops.size()) {
                    entry = hops.get(h).get(0).enter;
                }
            }
            if (!ok) {
                continue;
            }
            contributions.add(source.port.setpoint() * tier.volts() / Math.max(Math.hypot(r, x), 1.0E-9));
            paths.add(edges);
            // The devices between each protective link and the fault, in order along the way (all carry the fault).
            List<GridEdge> devices = ordered.stream().filter(e -> protective.containsKey(e.id())).toList();
            for (int a = 0; a < devices.size(); a++) {
                int count = 0;
                boolean allFuses = true;
                double fuseRating = 0;
                for (int b = a + 1; b < devices.size(); b++) {
                    Protective after = protective.get(devices.get(b).id());
                    count++;
                    allFuses &= after.fuse();
                    if (after.fuse()) {
                        fuseRating = minRating(fuseRating, after.pickup());
                    }
                }
                Beyond b = new Beyond(count, count > 0 && allFuses, fuseRating);
                Beyond now = beyond.get(devices.get(a).id());
                if (now == null || b.count() < now.count()) {
                    beyond.put(devices.get(a).id(), b);
                }
            }
        }
        double total = contributions.stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) {
            return FaultFlow.NONE;
        }
        // Capped: every contribution by the same ratio as the total.
        double ratio = total > limit ? limit / total : 1.0;
        Map<Long, Double> edgeAmps = new TreeMap<>();
        for (int i = 0; i < paths.size(); i++) {
            double amps = contributions.get(i) * ratio;
            for (long[] e : paths.get(i)) {
                edgeAmps.merge(e[0], amps * Double.longBitsToDouble(e[1]), Double::sum);
            }
        }
        return new FaultFlow(Math.min(total, limit), edgeAmps, beyond);
    }
}
