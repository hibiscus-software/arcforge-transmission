/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

// One tick of power over the whole grid of a world. Power only flows in complete circuits: a port (Port) is a set of
// insulators, one per conductor, and a circuit is the set of networks (one per conductor) at one tier that ports and
// transformer sides land on (which letter meets which doesn't matter: that's a transposition, or ABC against ACB). A
// port with a conductor unwired, a phase missing, or two of its conductors on one network (a short) moves nothing.
//
// Transformers (Coupling) link a circuit to circuits a tier down, either way, holding nothing: circuits joined by them
// make an island. In each island supply and demand are matched (each sink served in proportion to its demand, each
// source supplying in proportion to its offer; batteries only cover what the rest can't, or take what they have spare),
// and each source-sink pair's power runs circuit by circuit through the transformers between them, conductor by
// conductor along the cached shortest route in each network: I = P / V at each segment's tier. A wire's current is the
// sum of every pair's through it as phasors (each conductor's at its phase angle from its segment's entry labels, the
// neutral carrying its line's back; reactive current a quarter turn behind), so opposite flows cancel and a shared
// neutral carries only the difference of its lines. Each wire loses I squared R, paid by the pairs through it in
// proportion to their currents. Port current limits and transformer ratings cap the pairs through them; a source that
// can't cover its deliveries plus losses has its pairs scaled back, and the whole is worked again until nothing scales.
//
// Voltage: each source holds its setpoint (per unit); along each route a wire drops R I + X I (in phase and reactive
// parts of its current along the conductor), a regulator's link multiplies by its boost and a transformer by its tap.
// A load below fullPowerVoltage gets less (none at cutoffVoltage), worked out over a few rounds a tick, starting from the
// last tick's (State). Capacitor banks supply reactive power to the island's loads and reactors (the rest coming from its
// sources, which also take any surplus), so a bank raises the voltage near it and a reactor lowers it.
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
        GROUNDED
    }

    // What one tick moved: per port, what it gave and got (FE/t), its status and voltage (per unit); per wire, its
    // current (A) and loss (FE/t); per node, its voltage (per unit; only nodes power passed); per transformer, the power
    // through it (FE/t); the live networks (in an island with a source); ports that lost a phase (whole, but one of their
    // conductors on a dead network beside live ones); grounded networks a source reaches (a fault).
    public record Result(Map<Long, Double> drawn, Map<Long, Double> delivered, Map<Long, Double> edgeAmps, Map<Long, Double> edgeLoss,
            Map<Long, PortStatus> status, double totalLoss, Map<Long, Double> portVolts, Map<Long, Double> nodeVolts,
            Map<Long, Double> couplingFlow, Set<Long> liveNetworks, Set<Long> phaseLoss, Set<Long> groundFaults, Map<Long, Integer> beyond) {
        public static final Result EMPTY = new Result(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0.0, Map.of(), Map.of(), Map.of(), Set.of(),
                Set.of(), Set.of(), Map.of());

        // How many protective devices lie past a protective link on the way to the loads it feeds (the most on any way).
        public int beyond(long edge) {
            return beyond.getOrDefault(edge, 0);
        }

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

        public double totalDrawn() {
            return drawn.values().stream().mapToDouble(Double::doubleValue).sum();
        }

        public double totalDelivered() {
            return delivered.values().stream().mapToDouble(Double::doubleValue).sum();
        }
    }

    // What carries over between ticks: each load's share of its demand that its voltage allows.
    public static final class State {
        final Map<Long, Double> share = new HashMap<>();

        public double share(long port) {
            return share.getOrDefault(port, 1.0);
        }
    }

    // One conductor of a segment: its route through its network, the angle of its current, and whether it's a neutral.
    private record Conductor(GridNetwork.Route route, double cos, double sin, boolean neutral) {}

    // A pair's run through one circuit: its tier, conductors, and the transformer it then crosses (null at the end) and
    // which way (true: primary to secondary).
    private record Segment(VoltageTier tier, List<Conductor> conductors, @Nullable Coupling next, boolean down) {}

    private static final class Pair {
        // Real power's ends (a capacitor bank's or reactor's reactive pair has a source and consumer id, no real power).
        final long source, sink;
        final List<Segment> segments;
        double power, vars;

        Pair(long source, long sink, List<Segment> segments, double power, double vars) {
            this.source = source;
            this.sink = sink;
            this.segments = segments;
            this.power = power;
            this.vars = vars;
        }
    }

    // A checked side (a port, a transformer's side, a shunt): its network for each of its nodes, its circuit, its labels.
    private record Checked(long id, VoltageTier tier, List<Long> nodes, Map<Long, Long> nodeOfNetwork, String circuit, List<Phase> labels) {}

    // A transformer's side in a circuit: which coupling, and which side (-1 primary, else that secondary).
    private record Link(Coupling coupling, int side, Checked checked) {}

    private FlowSolver() {}

    public static Result solve(GridGraph graph, Collection<Port> ports, GridSettings settings) {
        return solve(graph, ports, List.of(), List.of(), Set.of(), settings, new State());
    }

    public static Result solve(GridGraph graph, Collection<Port> ports, Collection<Coupling> couplings, Collection<Shunt> shunts, Set<Long> grounded,
            GridSettings settings, State state) {
        return solve(graph, ports, couplings, shunts, grounded, Set.of(), settings, state);
    }

    // protective: the links (jumpers) that are protective devices, for Result.beyond.
    public static Result solve(GridGraph graph, Collection<Port> ports, Collection<Coupling> couplings, Collection<Shunt> shunts, Set<Long> grounded,
            Set<Long> protective, GridSettings settings, State state) {
        Map<Long, PortStatus> status = new TreeMap<>();
        Context ctx = context(graph, ports, couplings, shunts, grounded, status);
        Set<Long> groundedNetworks = ctx.groundedNetworks;
        Map<Long, Checked> checkedPorts = ctx.checkedPorts;
        Map<Long, Port> portById = ctx.portById;
        Map<String, List<Link>> linksIn = ctx.linksIn;
        Map<Long, List<Checked>> sidesOf = ctx.sidesOf;
        List<Checked> checkedShunts = ctx.checkedShunts;
        Map<Long, Shunt> shuntById = ctx.shuntById;
        Map<String, Integer> island = ctx.island;
        Set<Integer> liveIslands = ctx.liveIslands;
        Map<Integer, Set<String>> circuitsOf = ctx.circuitsOf;
        Set<Long> liveNetworks = ctx.liveNetworks;
        // A grounded line a source would reach with the ground switches open: a fault (a ground switch closed, or a
        // breaker closed, onto a live line).
        Set<Long> groundFaults = new TreeSet<>();
        if (!groundedNetworks.isEmpty()) {
            Set<Long> wouldBeLive = solve(graph, ports, couplings, List.of(), Set.of(), settings, new State()).liveNetworks();
            for (long network : groundedNetworks) {
                if (wouldBeLive.contains(network)) {
                    groundFaults.add(network);
                }
            }
        }
        // A whole three-phase port in a dead circuit with some of its conductors on live networks has lost a phase.
        Set<Long> phaseLoss = new TreeSet<>();
        for (Checked checked : checkedPorts.values()) {
            if (!liveIslands.contains(island.get(checked.circuit))) {
                long live = checked.nodeOfNetwork.keySet().stream().filter(liveNetworks::contains).count();
                if (live > 0 && live < checked.nodeOfNetwork.size()) {
                    phaseLoss.add(checked.id);
                }
            }
        }

        Map<Long, Double> drawn = new TreeMap<>(), delivered = new TreeMap<>(), edgeAmps = new TreeMap<>(), edgeLoss = new TreeMap<>();
        Map<Long, Double> portVolts = new TreeMap<>(), nodeVolts = new TreeMap<>(), couplingFlow = new TreeMap<>();
        int rounds = Math.max(1, settings.voltageRounds());
        Map<Long, Integer> beyond = new TreeMap<>();
        for (int round = 0; round < rounds; round++) {
            drawn.clear();
            delivered.clear();
            edgeAmps.clear();
            edgeLoss.clear();
            portVolts.clear();
            nodeVolts.clear();
            couplingFlow.clear();
            List<Pair> pairs = new ArrayList<>();
            Map<Long, Double> offers = new TreeMap<>();
            Paths paths = new Paths(linksIn, sidesOf);
            for (int i : circuitsOf.keySet()) {
                if (liveIslands.contains(i)) {
                    pairIsland(graph, circuitsOf.get(i), checkedPorts, portById, checkedShunts, shuntById, paths, settings, state, pairs, offers);
                }
            }
            Map<Long, double[]> phasor = new HashMap<>();
            flow(graph, pairs, portById, checkedPorts, offers, settings, phasor, drawn, delivered, edgeAmps, edgeLoss, couplingFlow);
            voltages(pairs, portById, settings, phasor, portVolts, nodeVolts);
            if (round == rounds - 1 && !protective.isEmpty()) {
                for (Pair pair : pairs) {
                    if (pair.power > 0) {
                        countBeyond(pair.segments, -1, protective, beyond, true);
                    }
                }
            }
            // Each load's share follows its voltage (half way each round, so it settles rather than swings).
            for (Checked checked : checkedPorts.values()) {
                Port port = portById.get(checked.id);
                if (port.demand() > 1.0E-9 && !port.storage() && portVolts.containsKey(port.id())) {
                    double target = share(portVolts.get(port.id()), settings);
                    double now = state.share(port.id());
                    state.share.put(port.id(), now + 0.5 * (target - now));
                }
            }
        }
        // Sources show their setpoint even with nothing taken.
        for (Checked checked : checkedPorts.values()) {
            Port port = portById.get(checked.id);
            if (port.offer() > 1.0E-9 && liveIslands.contains(island.get(checked.circuit))) {
                portVolts.putIfAbsent(port.id(), port.setpoint());
            }
        }
        state.share.keySet().retainAll(portById.keySet());
        double totalLoss = edgeLoss.values().stream().mapToDouble(Double::doubleValue).sum();
        return new Result(drawn, delivered, edgeAmps, edgeLoss, status, totalLoss, portVolts, nodeVolts, couplingFlow, liveNetworks, phaseLoss,
                groundFaults, beyond);
    }

    // The edges a path runs through in order: every conductor's of each segment (in the last, only the conductor ending
    // at `last`, if it's a node).
    private static List<GridEdge> pathEdges(List<Segment> segments, long last) {
        List<GridEdge> edges = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            for (Conductor conductor : segments.get(i).conductors) {
                List<Long> nodes = conductor.route.nodes();
                if (i == segments.size() - 1 && last >= 0 && nodes.get(nodes.size() - 1) != last) {
                    continue;
                }
                edges.addAll(conductor.route.edges());
            }
        }
        return edges;
    }

    // For each protective link along a path (segments; in the last, only the conductor ending at `last` if it's a node),
    // how many devices come after it: those after it on its own conductor, then those in the later segments (a
    // three-phase device is a link on each conductor, so a segment counts its devices along its busiest conductor).
    // Keeps the most (max) or the fewest seen.
    private static void countBeyond(List<Segment> segments, long last, Set<Long> protective, Map<Long, Integer> beyond, boolean max) {
        int later = 0;
        for (int i = segments.size() - 1; i >= 0; i--) {
            int most = 0;
            for (Conductor conductor : segments.get(i).conductors) {
                List<Long> nodes = conductor.route.nodes();
                if (i == segments.size() - 1 && last >= 0 && nodes.get(nodes.size() - 1) != last) {
                    continue;
                }
                List<Long> order = new ArrayList<>();
                for (GridEdge edge : conductor.route.edges()) {
                    if (protective.contains(edge.id())) {
                        order.add(edge.id());
                    }
                }
                for (int j = 0; j < order.size(); j++) {
                    beyond.merge(order.get(j), order.size() - 1 - j + later, max ? Math::max : Math::min);
                }
                most = Math.max(most, order.size());
            }
            later += most;
        }
    }

    // What a fault (a conductor to ground) at a node draws: each live source's current to it, limited by the source's own
    // impedance and the route's resistance (referred to the fault's tier through the transformers between, and to at most
    // faultMultiple times the fault tier's rating), added up on every link and wire it passes; and for each protective
    // link on the way, how many more lie between it and the fault (the fewest on any way). NONE if no source reaches it.
    public record FaultFlow(double amps, Map<Long, Double> edgeAmps, Map<Long, Integer> beyond) {
        public static final FaultFlow NONE = new FaultFlow(0, Map.of(), Map.of());

        public double amps(long edge) {
            return edgeAmps.getOrDefault(edge, 0.0);
        }

        public int beyond(long edge) {
            return beyond.getOrDefault(edge, 0);
        }
    }

    public static FaultFlow fault(GridGraph graph, Collection<Port> ports, Collection<Coupling> couplings, long faultNode, Set<Long> protective,
            GridSettings settings) {
        GridNetwork faulted = graph.networkOf(faultNode);
        if (faulted == null) {
            return FaultFlow.NONE;
        }
        Context ctx = context(graph, ports, couplings, List.of(), Set.of(), new TreeMap<>());
        // The fault's circuit: a live one with its network.
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
        double limit = settings.faultMultiple() * settings.ratedAmps(tier), total = 0;
        Paths paths = new Paths(ctx.linksIn, ctx.sidesOf);
        Map<Long, Double> edgeAmps = new TreeMap<>();
        Map<Long, Integer> beyond = new TreeMap<>();
        for (Checked source : ctx.checkedPorts.values()) {
            Port port = ctx.portById.get(source.id);
            if (port.offer() <= 1.0E-9 || !ctx.island.get(source.circuit).equals(ctx.island.get(circuit))) {
                continue;
            }
            List<Link[]> hops = paths.hops(source.circuit, circuit);
            if (hops == null) {
                continue;
            }
            Checked entry = hops.isEmpty() ? source : hops.get(hops.size() - 1)[1].checked;
            Map<Long, Long> to = new TreeMap<>(entry.nodeOfNetwork);
            to.put(faulted.id(), faultNode);
            List<Segment> segments = segments(graph, paths, source, new Checked(-1, entry.tier, List.of(), to, circuit, entry.labels));
            if (segments == null) {
                continue;
            }
            // Ohms, referred to the fault's tier: the source's own, then one conductor's route in each segment (the
            // faulted one's in the last).
            double ohms = settings.sourceImpedance(port.tier()) * Math.pow((double) tier.volts() / port.tier().volts(), 2);
            for (int i = 0; i < segments.size(); i++) {
                Segment segment = segments.get(i);
                double refer = Math.pow((double) tier.volts() / segment.tier.volts(), 2);
                for (Conductor conductor : segment.conductors) {
                    List<Long> nodes = conductor.route.nodes();
                    boolean counted = i < segments.size() - 1 ? conductor == segment.conductors.get(0) : nodes.get(nodes.size() - 1) == faultNode;
                    if (counted) {
                        for (GridEdge edge : conductor.route.edges()) {
                            ohms += edge.resistance(settings) * refer;
                        }
                    }
                }
            }
            double amps = Math.min(limit, port.setpoint() * tier.volts() / Math.max(ohms, 1.0E-9));
            total += amps;
            for (int i = 0; i < segments.size(); i++) {
                double here = amps * tier.volts() / segments.get(i).tier.volts();
                for (GridEdge edge : pathEdges(List.of(segments.get(i)), i == segments.size() - 1 ? faultNode : -1)) {
                    edgeAmps.merge(edge.id(), here, Double::sum);
                }
            }
            countBeyond(segments, faultNode, protective, beyond, false);
        }
        return new FaultFlow(Math.min(total, limit), edgeAmps, beyond);
    }

    // What a solve and a fault study share: the checked ports, transformer sides and shunts, the circuits' islands and
    // which are live.
    private static final class Context {
        Set<Long> groundedNetworks;
        Map<Long, Checked> checkedPorts;
        Map<Long, Port> portById;
        Map<String, List<Link>> linksIn;
        Map<Long, List<Checked>> sidesOf;
        List<Checked> checkedShunts;
        Map<Long, Shunt> shuntById;
        Map<String, Integer> island;
        Set<Integer> liveIslands;
        Map<Integer, Set<String>> circuitsOf;
        Set<Long> liveNetworks;
        Map<String, Set<Long>> networksOf;
    }

    private static Context context(GridGraph graph, Collection<Port> ports, Collection<Coupling> couplings, Collection<Shunt> shunts, Set<Long> grounded,
            Map<Long, PortStatus> status) {
        // Grounded networks: a closed ground switch's.
        Set<Long> groundedNetworks = new TreeSet<>();
        for (long node : grounded) {
            GridNetwork network = graph.networkOf(node);
            if (network != null) {
                groundedNetworks.add(network.id());
            }
        }
        Map<Long, Checked> checkedPorts = new TreeMap<>();
        Map<Long, Port> portById = new TreeMap<>();
        for (Port port : ports) {
            portById.put(port.id(), port);
            Checked checked = check(graph, port.id(), port.tier(), port.nodes(), status, groundedNetworks);
            if (checked != null) {
                checkedPorts.put(port.id(), checked);
            }
        }
        // Transformer sides, each in its circuit (an unwired side links nothing).
        Map<String, List<Link>> linksIn = new TreeMap<>();
        List<Coupling> working = new ArrayList<>();
        Map<Long, List<Checked>> sidesOf = new TreeMap<>();
        for (Coupling coupling : couplings) {
            if (!coupling.inService()) {
                continue;
            }
            Checked primary = check(graph, sideId(coupling, -1), coupling.primaryTier(), coupling.primary(), null, groundedNetworks);
            if (primary == null) {
                continue;
            }
            List<Checked> sides = new ArrayList<>();
            sides.add(primary);
            for (int s = 0; s < coupling.secondaries().size(); s++) {
                sides.add(check(graph, sideId(coupling, s), coupling.secondaryTier(), coupling.secondaries().get(s), null, groundedNetworks));
            }
            working.add(coupling);
            sidesOf.put(coupling.id(), sides);
            for (int s = -1; s < coupling.secondaries().size(); s++) {
                Checked side = sides.get(s + 1);
                if (side != null) {
                    linksIn.computeIfAbsent(side.circuit, k -> new ArrayList<>()).add(new Link(coupling, s, side));
                }
            }
        }
        List<Checked> checkedShunts = new ArrayList<>();
        Map<Long, Shunt> shuntById = new TreeMap<>();
        for (Shunt shunt : shunts) {
            if (shunt.inService()) {
                Checked checked = check(graph, shunt.id(), shunt.tier(), shunt.nodes(), null, groundedNetworks);
                if (checked != null) {
                    checkedShunts.add(checked);
                    shuntById.put(shunt.id(), shunt);
                }
            }
        }
        // Islands: circuits joined through transformers (a transformer joins its primary to each secondary).
        Map<String, Integer> island = islands(checkedPorts.values(), linksIn, sidesOf, working, checkedShunts);
        // Which islands have a source (live), and their networks.
        Set<Integer> liveIslands = new TreeSet<>();
        for (Checked checked : checkedPorts.values()) {
            Port port = portById.get(checked.id);
            if (port.offer() > 1.0E-9) {
                liveIslands.add(island.get(checked.circuit));
            }
        }
        Set<Long> liveNetworks = new TreeSet<>();
        Map<Integer, Set<String>> circuitsOf = new TreeMap<>();
        island.forEach((circuit, i) -> circuitsOf.computeIfAbsent(i, k -> new TreeSet<>()).add(circuit));
        Map<String, Set<Long>> networksOf = new TreeMap<>();
        checkedPorts.values().forEach(c -> networksOf.put(c.circuit, c.nodeOfNetwork.keySet()));
        checkedShunts.forEach(c -> networksOf.put(c.circuit, c.nodeOfNetwork.keySet()));
        linksIn.values().forEach(links -> links.forEach(l -> networksOf.put(l.checked.circuit, l.checked.nodeOfNetwork.keySet())));
        for (Map.Entry<String, Set<Long>> circuit : networksOf.entrySet()) {
            if (liveIslands.contains(island.get(circuit.getKey()))) {
                liveNetworks.addAll(circuit.getValue());
            }
        }
        Context ctx = new Context();
        ctx.groundedNetworks = groundedNetworks;
        ctx.checkedPorts = checkedPorts;
        ctx.portById = portById;
        ctx.linksIn = linksIn;
        ctx.sidesOf = sidesOf;
        ctx.checkedShunts = checkedShunts;
        ctx.shuntById = shuntById;
        ctx.island = island;
        ctx.liveIslands = liveIslands;
        ctx.circuitsOf = circuitsOf;
        ctx.liveNetworks = liveNetworks;
        ctx.networksOf = networksOf;
        return ctx;
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

    private static long sideId(Coupling coupling, int side) {
        return -(coupling.id() * 64 + side + 2);
    }

    // Union of circuits through working transformers; returns each circuit's island number.
    private static Map<String, Integer> islands(Collection<Checked> ports, Map<String, List<Link>> linksIn, Map<Long, List<Checked>> sidesOf,
            List<Coupling> working, List<Checked> shunts) {
        Map<String, String> parent = new TreeMap<>();
        for (Checked checked : ports) {
            parent.putIfAbsent(checked.circuit, checked.circuit);
        }
        for (Checked checked : shunts) {
            parent.putIfAbsent(checked.circuit, checked.circuit);
        }
        for (String circuit : linksIn.keySet()) {
            parent.putIfAbsent(circuit, circuit);
        }
        for (Coupling coupling : working) {
            List<Checked> sides = sidesOf.get(coupling.id());
            Checked primary = sides.get(0);
            for (int s = 1; s < sides.size(); s++) {
                if (sides.get(s) != null) {
                    union(parent, primary.circuit, sides.get(s).circuit);
                }
            }
        }
        Map<String, Integer> numbers = new TreeMap<>(), island = new TreeMap<>();
        for (String circuit : parent.keySet()) {
            String root = find(parent, circuit);
            island.put(circuit, numbers.computeIfAbsent(root, r -> numbers.size()));
        }
        return island;
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

    // The ways between circuits through transformers (fewest transformers, ties by coupling id then side), found once
    // per source circuit a tick.
    private static final class Paths {
        final Map<String, List<Link>> linksIn;
        final Map<Long, List<Checked>> sidesOf;
        final Map<String, Map<String, Link[]>> trees = new HashMap<>();

        Paths(Map<String, List<Link>> linksIn, Map<Long, List<Checked>> sidesOf) {
            this.linksIn = linksIn;
            this.sidesOf = sidesOf;
        }

        // The hops from one circuit to another: each [the side left by, the side entered by], or null if unreachable.
        @Nullable List<Link[]> hops(String from, String to) {
            Map<String, Link[]> tree = trees.computeIfAbsent(from, this::bfs);
            if (!from.equals(to) && !tree.containsKey(to)) {
                return null;
            }
            List<Link[]> hops = new ArrayList<>();
            String at = to;
            while (!at.equals(from)) {
                Link[] hop = tree.get(at);
                hops.add(0, hop);
                at = hop[0].checked.circuit;
            }
            return hops;
        }

        private Map<String, Link[]> bfs(String from) {
            Map<String, Link[]> via = new HashMap<>();
            Set<String> seen = new HashSet<>();
            seen.add(from);
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(from);
            while (!queue.isEmpty()) {
                String circuit = queue.poll();
                List<Link> out = new ArrayList<>(linksIn.getOrDefault(circuit, List.of()));
                out.sort((x, y) -> x.coupling.id() != y.coupling.id() ? Long.compare(x.coupling.id(), y.coupling.id()) : Integer.compare(x.side, y.side));
                for (Link leave : out) {
                    List<Checked> sides = sidesOf.get(leave.coupling.id());
                    // Primary to each secondary; a secondary to the primary.
                    for (int s = -1; s < sides.size() - 1; s++) {
                        if ((leave.side == -1) == (s == -1) || sides.get(s + 1) == null) {
                            continue;
                        }
                        Checked enter = sides.get(s + 1);
                        if (seen.add(enter.circuit)) {
                            via.put(enter.circuit, new Link[] { leave, new Link(leave.coupling, s, enter) });
                            queue.add(enter.circuit);
                        }
                    }
                }
            }
            return via;
        }
    }

    // Matches an island's sources to its sinks, and its capacitor banks and reactors to its loads and sources.
    private static void pairIsland(GridGraph graph, Set<String> circuits, Map<Long, Checked> checkedPorts, Map<Long, Port> portById,
            List<Checked> shunts, Map<Long, Shunt> shuntById, Paths paths, GridSettings settings, State state, List<Pair> pairs,
            Map<Long, Double> offers) {
        List<Checked> sources = new ArrayList<>(), sinks = new ArrayList<>(), stores = new ArrayList<>();
        Map<Long, Double> offer = new TreeMap<>(), demand = new TreeMap<>();
        double supply = 0, wanted = 0;
        for (Checked checked : checkedPorts.values()) {
            if (!circuits.contains(checked.circuit)) {
                continue;
            }
            Port port = portById.get(checked.id);
            if (port.storage()) {
                stores.add(checked);
                continue;
            }
            if (port.offer() > 1.0E-9) {
                sources.add(checked);
                offer.put(port.id(), port.offer());
                supply += port.offer();
            }
            double asked = port.demand() * state.share(port.id());
            if (asked > 1.0E-9) {
                sinks.add(checked);
                demand.put(port.id(), asked);
                wanted += asked;
            }
        }
        // Batteries: cover the shortfall, or take the surplus.
        double storeOffer = 0, storeRoom = 0;
        for (Checked checked : stores) {
            storeOffer += portById.get(checked.id).offer();
            storeRoom += portById.get(checked.id).demand();
        }
        if (wanted > supply + 1.0E-9 && storeOffer > 1.0E-9) {
            double share = Math.min(1.0, (wanted - supply) / storeOffer);
            for (Checked checked : stores) {
                double give = portById.get(checked.id).offer() * share;
                if (give > 1.0E-9) {
                    sources.add(checked);
                    offer.put(checked.id, give);
                    supply += give;
                }
            }
        } else if (supply > wanted + 1.0E-9 && storeRoom > 1.0E-9) {
            double share = Math.min(1.0, (supply - wanted) / storeRoom);
            for (Checked checked : stores) {
                double take = portById.get(checked.id).demand() * share;
                if (take > 1.0E-9) {
                    sinks.add(checked);
                    demand.put(checked.id, take);
                    wanted += take;
                }
            }
        }
        offers.putAll(offer);
        double tan = Math.tan(Math.acos(Math.max(0.0, Math.min(1.0, settings.loadPowerFactor()))));
        // Reactive power: loads' (at their power factor) and reactors' wanted; capacitor banks' supplied.
        double loadVars = 0, reactorVars = 0, capVars = 0;
        List<Checked> caps = new ArrayList<>(), reactors = new ArrayList<>();
        for (Checked checked : shunts) {
            if (!circuits.contains(checked.circuit)) {
                continue;
            }
            double vars = shuntById.get(checked.id).vars();
            if (vars > 0) {
                caps.add(checked);
                capVars += vars;
            } else if (vars < 0) {
                reactors.add(checked);
                reactorVars -= vars;
            }
        }
        double served = Math.min(supply, wanted);
        for (Checked sink : sinks) {
            loadVars += demand.get(sink.id) * served / Math.max(wanted, 1.0E-12) * (portById.get(sink.id).storage() ? 0 : tan);
        }
        double varsWanted = loadVars + reactorVars, covered = Math.min(capVars, varsWanted);
        // The share of every consumer's reactive power the sources (not the banks) supply.
        double fromSources = varsWanted > 1.0E-9 ? 1.0 - covered / varsWanted : 1.0;
        if (supply > 1.0E-9 && wanted > 1.0E-9) {
            for (Checked sink : sinks) {
                double toSink = demand.get(sink.id) * served / wanted;
                double sinkVars = portById.get(sink.id).storage() ? 0 : toSink * tan;
                // From every other source, by what it offers (a port that both gives and takes isn't paired with
                // itself; a source asked for more than it has is scaled back with its losses below).
                double others = supply - offer.getOrDefault(sink.id, 0.0);
                if (others <= 1.0E-9) {
                    continue;
                }
                for (Checked source : sources) {
                    if (source.id == sink.id) {
                        continue;
                    }
                    List<Segment> segments = segments(graph, paths, source, sink);
                    if (segments != null) {
                        double share = offer.get(source.id) / others;
                        pairs.add(new Pair(source.id, sink.id, segments, toSink * share, sinkVars * share * fromSources));
                    }
                }
                // The banks' part of its reactive power, from each bank by its size.
                for (Checked cap : caps) {
                    List<Segment> segments = segments(graph, paths, cap, sink);
                    if (segments != null && sinkVars > 0) {
                        pairs.add(new Pair(cap.id, sink.id, segments, 0, sinkVars * (1 - fromSources) * shuntById.get(cap.id).vars() / capVars));
                    }
                }
            }
        }
        // Reactors take from the banks first, then the sources.
        for (Checked reactor : reactors) {
            double vars = -shuntById.get(reactor.id).vars();
            for (Checked cap : caps) {
                List<Segment> segments = segments(graph, paths, cap, reactor);
                if (segments != null) {
                    pairs.add(new Pair(cap.id, reactor.id, segments, 0, vars * (1 - fromSources) * shuntById.get(cap.id).vars() / capVars));
                }
            }
            addFromSources(graph, paths, sources, offer, supply, reactor, vars * fromSources, false, pairs);
        }
        // A bank's surplus goes back to the sources (they take it).
        double surplus = capVars - covered;
        if (surplus > 1.0E-9) {
            for (Checked cap : caps) {
                addFromSources(graph, paths, sources, offer, supply, cap, surplus * shuntById.get(cap.id).vars() / capVars, true, pairs);
            }
        }
    }

    // Reactive pairs between the sources (by offer) and one bank or reactor: from the sources to it, or (toSources) from
    // it to them.
    private static void addFromSources(GridGraph graph, Paths paths, List<Checked> sources, Map<Long, Double> offer, double supply, Checked other,
            double vars, boolean toSources, List<Pair> pairs) {
        if (vars <= 1.0E-9 || supply <= 1.0E-9) {
            return;
        }
        for (Checked source : sources) {
            List<Segment> segments = toSources ? segments(graph, paths, other, source) : segments(graph, paths, source, other);
            if (segments != null) {
                double share = offer.get(source.id) / supply;
                pairs.add(toSources ? new Pair(other.id, source.id, segments, 0, vars * share) : new Pair(source.id, other.id, segments, 0, vars * share));
            }
        }
    }

    // A pair's segments from one side to another through the transformers between their circuits, or null if there's no
    // way.
    private static @Nullable List<Segment> segments(GridGraph graph, Paths paths, Checked from, Checked to) {
        List<Link[]> hops = paths.hops(from.circuit, to.circuit);
        if (hops == null) {
            return null;
        }
        List<Segment> segments = new ArrayList<>();
        Checked entry = from;
        for (Link[] hop : hops) {
            List<Conductor> conductors = conductors(graph, entry, hop[0].checked);
            if (conductors == null) {
                return null;
            }
            segments.add(new Segment(entry.tier, conductors, hop[0].coupling, hop[0].side == -1));
            entry = hop[1].checked;
        }
        List<Conductor> conductors = conductors(graph, entry, to);
        if (conductors == null) {
            return null;
        }
        segments.add(new Segment(entry.tier, conductors, null, false));
        return segments;
    }

    // A segment's conductors: for each network, the entry's insulator on it to the exit's, at the entry's phase.
    private static @Nullable List<Conductor> conductors(GridGraph graph, Checked from, Checked to) {
        List<Conductor> conductors = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : from.nodeOfNetwork.entrySet()) {
            long start = entry.getValue();
            Long end = to.nodeOfNetwork.get(entry.getKey());
            GridNetwork network = graph.networkOf(start);
            GridNetwork.Route route = end == null || network == null ? null : network.route(start, end);
            if (route == null) {
                return null;
            }
            Phase phase = graph.node(start).phase();
            double angle = CircuitMath.angle(phase, from.labels);
            conductors.add(new Conductor(route, Math.cos(angle), Math.sin(angle), phase.isNeutral()));
        }
        return conductors;
    }

    // The pairs' currents, losses and costs, scaling them to limits, ratings and what each source can give.
    private static void flow(GridGraph graph, List<Pair> pairs, Map<Long, Port> portById, Map<Long, Checked> checkedPorts, Map<Long, Double> offers,
            GridSettings settings, Map<Long, double[]> phasor, Map<Long, Double> drawn, Map<Long, Double> delivered, Map<Long, Double> edgeAmps,
            Map<Long, Double> edgeLoss, Map<Long, Double> couplingFlow) {
        Map<Long, Double> gross = new HashMap<>();
        // Current limits by node: every pair through a limited insulator shares its limit.
        Map<Long, Double> limits = new TreeMap<>();
        for (Checked checked : checkedPorts.values()) {
            Port port = portById.get(checked.id);
            if (port.limitAmps() < Double.POSITIVE_INFINITY) {
                for (long node : port.nodes()) {
                    limits.merge(node, Math.max(0.0, port.limitAmps()), Math::min);
                }
            }
        }
        Map<Long, Coupling> couplings = new TreeMap<>();
        for (Pair pair : pairs) {
            for (Segment segment : pair.segments) {
                if (segment.next != null) {
                    couplings.put(segment.next.id(), segment.next);
                }
            }
        }
        double[] pairCost = new double[pairs.size()];
        for (int round = 0; round < ROUNDS; round++) {
            boolean scaled = false;
            for (Map.Entry<Long, Double> limit : limits.entrySet()) {
                double through = 0;
                List<Pair> passing = new ArrayList<>();
                for (Pair pair : pairs) {
                    double amps = ampsAt(pair, limit.getKey());
                    if (amps > 0) {
                        through += amps;
                        passing.add(pair);
                    }
                }
                if (through > limit.getValue() + 1.0E-9) {
                    double factor = limit.getValue() / through;
                    passing.forEach(pair -> {
                        pair.power *= factor;
                        pair.vars *= factor;
                    });
                    scaled = true;
                }
            }
            // Transformer ratings: all the power through one, either way.
            for (Coupling coupling : couplings.values()) {
                double through = 0;
                for (Pair pair : pairs) {
                    if (crosses(pair, coupling)) {
                        through += Math.hypot(pair.power, pair.vars);
                    }
                }
                if (through > coupling.rating() + 1.0E-9) {
                    double factor = coupling.rating() / through;
                    for (Pair pair : pairs) {
                        if (crosses(pair, coupling)) {
                            pair.power *= factor;
                            pair.vars *= factor;
                        }
                    }
                    scaled = true;
                }
            }
            currents(graph, pairs, settings, phasor, gross, edgeAmps, edgeLoss);
            // Each pair pays its share of the losses on its routes; each source owes what its pairs cost.
            Map<Long, Double> cost = new TreeMap<>(), delivering = new TreeMap<>();
            double reactiveLoss = 0;
            for (int p = 0; p < pairs.size(); p++) {
                Pair pair = pairs.get(p);
                double total = pair.power;
                for (Segment segment : pair.segments) {
                    double amps = Math.hypot(pair.power, pair.vars) / segment.tier.volts();
                    for (Conductor conductor : segment.conductors) {
                        for (GridEdge edge : conductor.route.edges()) {
                            double all = gross.getOrDefault(edge.id(), 0.0);
                            if (all > 0) {
                                total += edgeLoss.getOrDefault(edge.id(), 0.0) * amps / all;
                            }
                        }
                    }
                }
                pairCost[p] = total;
                if (pair.power > 0) {
                    cost.merge(pair.source, total, Double::sum);
                    delivering.merge(pair.source, pair.power, Double::sum);
                } else {
                    reactiveLoss += total;
                }
            }
            // A bank's or reactor's losses are paid by the island's sources, by offer.
            double offered = offers.values().stream().mapToDouble(Double::doubleValue).sum();
            if (reactiveLoss > 0 && offered > 0) {
                for (Map.Entry<Long, Double> o : offers.entrySet()) {
                    cost.merge(o.getKey(), reactiveLoss * o.getValue() / offered, Double::sum);
                }
            }
            for (Map.Entry<Long, Double> owed : cost.entrySet()) {
                double can = offers.getOrDefault(owed.getKey(), 0.0);
                if (owed.getValue() > can * (1 + 1.0E-9) + 1.0E-9) {
                    // A battery charging from it gives way first.
                    double charging = 0;
                    for (Pair pair : pairs) {
                        Port to = portById.get(pair.sink);
                        if (pair.source == owed.getKey() && to != null && to.storage()) {
                            charging += pair.power;
                        }
                    }
                    if (charging > 1.0E-9) {
                        double keep = Math.max(0.0, 1.0 - (owed.getValue() - can) * 1.05 / charging);
                        for (Pair pair : pairs) {
                            Port to = portById.get(pair.sink);
                            if (pair.source == owed.getKey() && to != null && to.storage()) {
                                pair.power *= keep;
                                pair.vars *= keep;
                            }
                        }
                        scaled = true;
                        continue;
                    }
                    // Scaled so that what it delivers (d, which scales with the factor) plus its losses (l, which
                    // scale with its square) comes to what it can give: f d + f^2 l = can.
                    double d = delivering.getOrDefault(owed.getKey(), 0.0), l = Math.max(0, owed.getValue() - d);
                    double factor = l > 1.0E-12 ? (-d + Math.sqrt(d * d + 4 * l * can)) / (2 * l) : can / owed.getValue();
                    for (Pair pair : pairs) {
                        if (pair.source == owed.getKey()) {
                            pair.power *= factor;
                            pair.vars *= factor;
                        }
                    }
                    scaled = true;
                }
            }
            if (!scaled) {
                drawn.putAll(cost);
                break;
            }
            if (round == ROUNDS - 1) {
                drawn.putAll(cost);
            }
        }
        for (Pair pair : pairs) {
            if (pair.power > 0) {
                delivered.merge(pair.sink, pair.power, Double::sum);
            }
            for (Segment segment : pair.segments) {
                if (segment.next != null) {
                    couplingFlow.merge(segment.next.id(), pair.power, Double::sum);
                }
            }
        }
        drawn.keySet().retainAll(portById.keySet());
        delivered.keySet().retainAll(portById.keySet());
    }

    // The current (A) a pair puts through a node, or 0 if it doesn't pass it.
    private static double ampsAt(Pair pair, long node) {
        for (Segment segment : pair.segments) {
            for (Conductor conductor : segment.conductors) {
                if (conductor.route.nodes().contains(node)) {
                    return Math.hypot(pair.power, pair.vars) / segment.tier.volts();
                }
            }
        }
        return 0;
    }

    private static boolean crosses(Pair pair, Coupling coupling) {
        for (Segment segment : pair.segments) {
            if (segment.next != null && segment.next.id() == coupling.id()) {
                return true;
            }
        }
        return false;
    }

    // Each wire's current (the phasor sum of the pairs through it, real and reactive), its gross (the sum of their sizes)
    // and its loss.
    private static void currents(GridGraph graph, List<Pair> pairs, GridSettings settings, Map<Long, double[]> phasor, Map<Long, Double> gross,
            Map<Long, Double> edgeAmps, Map<Long, Double> edgeLoss) {
        phasor.clear();
        gross.clear();
        for (Pair pair : pairs) {
            for (Segment segment : pair.segments) {
                double real = pair.power / segment.tier.volts(), reactive = pair.vars / segment.tier.volts();
                double size = Math.hypot(real, reactive);
                for (Conductor conductor : segment.conductors) {
                    GridNetwork.Route route = conductor.route;
                    for (int i = 0; i < route.edges().size(); i++) {
                        long edge = route.edges().get(i).id();
                        double sign = route.forward().get(i) ? 1.0 : -1.0;
                        double[] sum = phasor.computeIfAbsent(edge, e -> new double[2]);
                        // Real at the conductor's angle; reactive (lagging) a quarter turn behind it.
                        sum[0] += sign * (real * conductor.cos + reactive * conductor.sin);
                        sum[1] += sign * (real * conductor.sin - reactive * conductor.cos);
                        gross.merge(edge, size, Double::sum);
                    }
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
    }

    // Voltages along every pair's routes: from its source's setpoint, each wire dropping R I + X I (the in-phase and
    // reactive parts of its current along the conductor), a regulator's link boosting, a transformer's tap stepping.
    // A port's and a node's voltage are the averages over the pairs through them, by their power.
    private static void voltages(List<Pair> pairs, Map<Long, Port> portById, GridSettings settings, Map<Long, double[]> phasor,
            Map<Long, Double> portVolts, Map<Long, Double> nodeVolts) {
        Map<Long, double[]> portSum = new TreeMap<>(), nodeSum = new TreeMap<>();
        for (Pair pair : pairs) {
            Port source = portById.get(pair.source);
            double volts = source != null ? source.setpoint() : 1.0;
            double weight = Math.max(1.0E-9, Math.hypot(pair.power, pair.vars));
            if (source != null && pair.power > 0) {
                add(portSum, pair.source, volts, weight);
            }
            for (Segment segment : pair.segments) {
                double lineDrop = 0, neutralDrop = 0;
                int lines = 0, neutrals = 0;
                for (Conductor conductor : segment.conductors) {
                    double v = volts;
                    if (!conductor.neutral) {
                        add(nodeSum, conductor.route.nodes().get(0), v, weight);
                    }
                    for (int i = 0; i < conductor.route.edges().size(); i++) {
                        GridEdge edge = conductor.route.edges().get(i);
                        double sign = conductor.route.forward().get(i) ? 1.0 : -1.0;
                        double[] p = phasor.getOrDefault(edge.id(), new double[2]);
                        double inPhase = sign * (p[0] * conductor.cos + p[1] * conductor.sin);
                        double reactive = sign * (p[0] * conductor.sin - p[1] * conductor.cos);
                        double drop = edge.resistance(settings) * inPhase + settings.reactance(edge.tier()) * edge.length() * reactive;
                        v -= drop / segment.tier.volts();
                        if (edge.boost() != 0) {
                            v *= sign > 0 ? 1 + edge.boost() : 1 / (1 + edge.boost());
                        }
                        if (!conductor.neutral) {
                            add(nodeSum, conductor.route.nodes().get(i + 1), v, weight);
                        }
                    }
                    if (conductor.neutral) {
                        neutralDrop += volts - v;
                        neutrals++;
                    } else {
                        lineDrop += volts - v;
                        lines++;
                    }
                }
                volts -= (lines > 0 ? lineDrop / lines : 0) + (neutrals > 0 ? neutralDrop / neutrals : 0);
                if (segment.next != null) {
                    volts = segment.down ? volts * (1 + segment.next.tap()) : volts / (1 + segment.next.tap());
                }
            }
            if (pair.power > 0) {
                add(portSum, pair.sink, Math.max(0, volts), weight);
            }
        }
        portSum.forEach((id, s) -> portVolts.put(id, s[0] / s[1]));
        nodeSum.forEach((id, s) -> nodeVolts.put(id, s[0] / s[1]));
    }

    private static void add(Map<Long, double[]> sums, long key, double value, double weight) {
        double[] s = sums.computeIfAbsent(key, k -> new double[2]);
        s[0] += value * weight;
        s[1] += weight;
    }

    // Whether a side is one whole circuit, each conductor wired on a network of its own (none grounded).
    private static @Nullable Checked check(GridGraph graph, long id, VoltageTier tier, List<Long> nodes, @Nullable Map<Long, PortStatus> status,
            Set<Long> groundedNetworks) {
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
        return result == PortStatus.OK ? new Checked(id, tier, nodes, nodeOfNetwork, tier.id() + nodeOfNetwork.keySet(), order) : null;
    }
}
