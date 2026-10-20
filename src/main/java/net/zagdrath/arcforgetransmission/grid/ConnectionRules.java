/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

// Whether a wire of a tier may join two insulators. A network is one tier and one circuit type (transformers are the
// only bridge between them), so anything that would mix them is refused here. Two different phase letters of one
// circuit type is a transposition, and allowed.
public final class ConnectionRules {
    public enum Refusal {
        SAME_ENDPOINT("same_endpoint"),
        ALREADY_CONNECTED("already_connected"),
        WRONG_WIRE("wrong_wire"),
        MIXED_TIER("mixed_tier"),
        MIXED_CIRCUIT("mixed_circuit"),
        TOO_LONG("too_long"),
        MISSING("missing");

        private final String key;

        Refusal(String key) {
            this.key = key;
        }

        // The lang key's last part (message.arcforgetransmission.wire.<key>).
        public String key() {
            return key;
        }
    }

    private ConnectionRules() {}

    // A 3-conductor connection (a tray cable, a busway joint, a lineup's internal bus): one circuit's conductors joined to
    // another's phase to phase, A to A, B to B, C to C (or L1, N, L2). Each pair, from's node then to's, in from's order;
    // or why not: a node missing, the two of different tiers or circuit types, a label without its match, or a pair
    // already joined.
    public record Pairing(List<long[]> pairs, @Nullable Refusal refusal) {}

    public static Pairing pairByPhase(GridGraph graph, List<Long> from, List<Long> to) {
        List<long[]> pairs = new ArrayList<>();
        if (from.isEmpty() || from.size() != to.size()) {
            return new Pairing(List.of(), Refusal.MISSING);
        }
        for (long a : from) {
            GridNode na = graph.node(a);
            if (na == null) {
                return new Pairing(List.of(), Refusal.MISSING);
            }
            GridNode match = null;
            for (long b : to) {
                GridNode nb = graph.node(b);
                if (nb == null) {
                    return new Pairing(List.of(), Refusal.MISSING);
                }
                if (nb.tier() != na.tier()) {
                    return new Pairing(List.of(), Refusal.MIXED_TIER);
                }
                if (nb.circuit() != na.circuit()) {
                    return new Pairing(List.of(), Refusal.MIXED_CIRCUIT);
                }
                if (nb.phase() == na.phase()) {
                    match = nb;
                }
            }
            if (match == null) {
                return new Pairing(List.of(), Refusal.MISSING);
            }
            if (a == match.id()) {
                return new Pairing(List.of(), Refusal.SAME_ENDPOINT);
            }
            if (graph.connected(a, match.id())) {
                return new Pairing(List.of(), Refusal.ALREADY_CONNECTED);
            }
            pairs.add(new long[] { a, match.id() });
        }
        return new Pairing(pairs, null);
    }

    // Null when a wire of wireTier may span `span` blocks from a to b.
    public static @Nullable Refusal check(GridGraph graph, long a, long b, VoltageTier wireTier, double span, GridSettings settings) {
        GridNode na = graph.node(a), nb = graph.node(b);
        if (na == null || nb == null) {
            return Refusal.MISSING;
        }
        if (a == b) {
            return Refusal.SAME_ENDPOINT;
        }
        // Shield wire joins two towers' peaks whatever their tiers.
        boolean shield = na.circuit() == CircuitType.GROUND && nb.circuit() == CircuitType.GROUND;
        if (!shield && na.tier() != nb.tier()) {
            return Refusal.MIXED_TIER;
        }
        if (!shield && na.tier() != wireTier) {
            return Refusal.WRONG_WIRE;
        }
        if (na.circuit() != nb.circuit()) {
            return Refusal.MIXED_CIRCUIT;
        }
        if (graph.connected(a, b)) {
            return Refusal.ALREADY_CONNECTED;
        }
        if (span > settings.maxSpan(wireTier) + 1.0E-6) {
            return Refusal.TOO_LONG;
        }
        return null;
    }
}
