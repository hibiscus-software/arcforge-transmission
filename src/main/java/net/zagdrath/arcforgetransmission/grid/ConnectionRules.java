/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

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
