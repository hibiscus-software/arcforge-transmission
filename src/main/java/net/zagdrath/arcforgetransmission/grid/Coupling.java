/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.List;

// A transformer as the solver sees it: an electrical link from its primary (a whole circuit of nodes at one tier) to each
// of its secondaries (whole circuits a tier down, or a split-phase can's two legs), carrying power either way as the
// grid needs, up to rating FE/t in all, holding no energy. tap raises the secondaries' voltage over the primary's, per
// unit (a tap changer's setting; 0 for none). Out of service, it links nothing.
public record Coupling(long id, VoltageTier primaryTier, List<Long> primary, VoltageTier secondaryTier, List<List<Long>> secondaries,
        double rating, double tap, boolean inService) {
    public Coupling {
        primary = List.copyOf(primary);
        secondaries = secondaries.stream().map(List::copyOf).toList();
    }
}
