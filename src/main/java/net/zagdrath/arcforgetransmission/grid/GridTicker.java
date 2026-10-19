/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// One grid tick for a whole world: the ports (Port: inductors on a block, a transformer's sides) give their supply and
// demand, the flow is solved (FlowSolver), the energy moved, and each wire's ticks over its rating counted; wires over it
// too long burn out and are removed (GridFaults hears of every overcurrent and burnout). Ports are whatever holds the
// energy: in game, the blocks the inductors are on and the transformers (absent while their chunk isn't loaded, so they
// neither give nor take: the grid holds no energy of its own, so nothing is lost or made at a chunk border).
public final class GridTicker {
    private GridTicker() {}

    // Where energy comes from and goes to.
    public interface Terminals {
        // Every port that can give or take this tick.
        Collection<Port> ports();

        // Moves the tick's energy, by port id: takes the draws from the sources and gives the deliveries to the sinks
        // (whole FE; draws are rounded up and deliveries down, so rounding never makes energy).
        void commit(Map<Long, Integer> draws, Map<Long, Integer> deliveries);
    }

    public record Report(FlowSolver.Result result, List<GridEdge> burnt) {}

    public static Report tick(GridGraph graph, Terminals terminals, GridSettings settings) {
        Collection<Port> ports = terminals.ports();
        FlowSolver.Result result = FlowSolver.solve(graph, ports, settings);
        Map<Long, Double> offer = new TreeMap<>();
        for (Port port : ports) {
            offer.merge(port.id(), Math.max(0.0, port.offer()), Math::max);
        }
        Map<Long, Integer> draws = new TreeMap<>(), deliveries = new TreeMap<>();
        // Never more than offered (the solver can overshoot an offer by a rounding hair).
        result.drawn().forEach((id, fe) -> draws.put(id, (int) Math.min(Math.floor(offer.getOrDefault(id, 0.0) + 1.0E-6),
                Math.min(Integer.MAX_VALUE, Math.ceil(fe - 1.0E-6)))));
        result.delivered().forEach((id, fe) -> deliveries.put(id, (int) Math.min(Integer.MAX_VALUE, Math.floor(fe + 1.0E-6))));
        draws.values().removeIf(fe -> fe <= 0);
        deliveries.values().removeIf(fe -> fe <= 0);
        if (!draws.isEmpty() || !deliveries.isEmpty()) {
            terminals.commit(draws, deliveries);
        }
        // Overcurrent: a wire over its rating for burnoutTicks in a row burns out.
        List<GridEdge> burning = new ArrayList<>();
        for (GridEdge edge : graph.edges()) {
            double amps = result.amps(edge.id());
            if (amps > settings.ratedAmps(edge.tier()) + 1.0E-9) {
                edge.overTicks++;
                GridFaults.raise(FaultEvent.edge(edge.id(), FaultEvent.Cause.OVERCURRENT, amps));
                if (edge.overTicks >= settings.burnoutTicks()) {
                    burning.add(edge);
                }
            } else {
                edge.overTicks = 0;
            }
        }
        for (GridEdge edge : burning) {
            graph.removeEdge(edge.id());
            GridFaults.raise(FaultEvent.edge(edge.id(), FaultEvent.Cause.OVERCURRENT_BURNOUT, result.amps(edge.id())));
        }
        return new Report(result, burning);
    }
}
