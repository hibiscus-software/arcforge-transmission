/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.Section;

// Whether a row of switchgear sections (side by side, the same way round, read west to east along the lineup) makes a
// lineup, and what each section is in it. Pure: no world, unit tested.
//  - single-ended: Incoming, (Transformer), Main, Feeders; read either way (a mirrored order is fine);
//  - double-ended: Incoming, (Transformer), Main, Feeders, Bus Tie, Feeders, Main, (Transformer), Incoming; both halves'
//    buses at the same tier (both with a transformer or neither);
//  - every section the same tier; at most maxFeeders feeder sections a bus half, and at least one.
// Anything else is a problem the row's sections all report, and none of them does anything until it's fixed.
public final class Lineup {
    public enum Problem {
        MIXED_TIER("mixed_tier"), NO_INCOMING("no_incoming"), NO_MAIN("no_main"), NO_FEEDER("no_feeder"), TOO_MANY_FEEDERS("too_many_feeders"),
        WRONG_ORDER("wrong_order"), TIE_TIERS("tie_tiers");

        private final String key;

        Problem(String key) {
            this.key = key;
        }

        // The lang key's last part (hud.arcforgetransmission.lineup.<key>).
        public String key() {
            return key;
        }
    }

    // A section's place: which way its incomer lies along the lineup (-1 west, 1 east; 0 for the bus tie, between two),
    // how many tiers below the lineup's its bus is on the incomer's side of it and on the far side (a transformer
    // section steps one down), and which bus half it's in (0 west, 1 east; a single-ended lineup is all 0).
    public record Role(int incomer, int dropIn, int dropOut, int half) {}

    // A row's plan: a problem (null when it's a lineup), and each section's role, west to east.
    public record Plan(@Nullable Problem problem, List<Role> roles, boolean doubleEnded) {
        public boolean valid() {
            return problem == null;
        }
    }

    private Lineup() {}

    public static Plan plan(List<Section> row, List<Integer> tiers, int maxFeeders) {
        int n = row.size();
        for (int tier : tiers) {
            if (tier != tiers.get(0)) {
                return new Plan(Problem.MIXED_TIER, List.of(), false);
            }
        }
        List<Integer> ties = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (row.get(i) == Section.TIE) {
                ties.add(i);
            }
        }
        if (ties.size() > 1) {
            return new Plan(Problem.WRONG_ORDER, List.of(), false);
        }
        if (ties.size() == 1) {
            int t = ties.get(0);
            List<Section> west = row.subList(0, t), east = new ArrayList<>(row.subList(t + 1, n));
            Collections.reverse(east);
            Half a = half(west, maxFeeders), b = half(east, maxFeeders);
            if (a.problem != null || b.problem != null) {
                return new Plan(a.problem != null ? a.problem : b.problem, List.of(), true);
            }
            if (a.drop != b.drop) {
                return new Plan(Problem.TIE_TIERS, List.of(), true);
            }
            List<Role> roles = new ArrayList<>();
            for (int i = 0; i < t; i++) {
                roles.add(role(west.get(i), i, a.transformer, -1, 0));
            }
            roles.add(new Role(0, a.drop, a.drop, 0));
            List<Role> eastRoles = new ArrayList<>();
            for (int i = 0; i < east.size(); i++) {
                eastRoles.add(role(east.get(i), i, b.transformer, 1, 1));
            }
            Collections.reverse(eastRoles);
            roles.addAll(eastRoles);
            return new Plan(null, roles, true);
        }
        // Single-ended: the incomer at the west end, or (mirrored) at the east.
        boolean mirrored = n > 0 && row.get(0) != Section.INCOMING && row.get(n - 1) == Section.INCOMING;
        List<Section> fromIncomer = new ArrayList<>(row);
        if (mirrored) {
            Collections.reverse(fromIncomer);
        }
        Half h = half(fromIncomer, maxFeeders);
        if (h.problem != null) {
            return new Plan(h.problem, List.of(), false);
        }
        List<Role> roles = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            roles.add(role(fromIncomer.get(i), i, h.transformer, mirrored ? 1 : -1, 0));
        }
        if (mirrored) {
            Collections.reverse(roles);
        }
        return new Plan(null, roles, false);
    }

    // A section `i` from its half's incomer (the transformer, if any, at `transformer`).
    private static Role role(Section section, int i, int transformer, int incomer, int half) {
        int in = transformer >= 0 && i > transformer ? 1 : 0;
        int out = transformer >= 0 && i >= transformer ? 1 : 0;
        return new Role(incomer, in, out, half);
    }

    // A bus half, read from its incomer: what's wrong with it, where its transformer is (-1 none) and its bus's drop.
    private record Half(@Nullable Problem problem, int transformer, int drop) {}

    private static Half half(List<Section> s, int maxFeeders) {
        if (s.isEmpty() || s.get(0) != Section.INCOMING) {
            return new Half(s.contains(Section.INCOMING) ? Problem.WRONG_ORDER : Problem.NO_INCOMING, -1, 0);
        }
        int i = 1, transformer = -1;
        if (i < s.size() && s.get(i) == Section.TRANSFORMER) {
            transformer = i++;
        }
        if (i >= s.size() || s.get(i) != Section.MAIN) {
            return new Half(s.contains(Section.MAIN) ? Problem.WRONG_ORDER : Problem.NO_MAIN, -1, 0);
        }
        i++;
        int feeders = 0;
        for (; i < s.size(); i++) {
            if (s.get(i) != Section.FEEDER) {
                return new Half(Problem.WRONG_ORDER, -1, 0);
            }
            feeders++;
        }
        if (feeders == 0) {
            return new Half(Problem.NO_FEEDER, -1, 0);
        }
        if (feeders > maxFeeders) {
            return new Half(Problem.TOO_MANY_FEEDERS, -1, 0);
        }
        return new Half(null, transformer, transformer >= 0 ? 1 : 0);
    }
}
