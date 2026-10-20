/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.Section;

// A row of sections makes a lineup only in order, one tier, with its feeders within bounds; each section learns which
// way its incomer is and what tier its bus is at either side of it.
class LineupTest {
    private static final Section I = Section.INCOMING, X = Section.TRANSFORMER, M = Section.MAIN, F = Section.FEEDER, T = Section.TIE;

    private static Lineup.Plan plan(Section... row) {
        return Lineup.plan(List.of(row), Collections.nCopies(row.length, 2), 8);
    }

    @Test
    void singleEndedEitherWay() {
        Lineup.Plan p = plan(I, X, M, F, F);
        assertNull(p.problem());
        assertEquals(new Lineup.Role(-1, 0, 0, 0), p.roles().get(0));
        // The transformer steps the bus down on its far side; everything past it is on the lower bus.
        assertEquals(new Lineup.Role(-1, 0, 1, 0), p.roles().get(1));
        assertEquals(new Lineup.Role(-1, 1, 1, 0), p.roles().get(3));
        Lineup.Plan mirrored = plan(F, F, M, X, I);
        assertNull(mirrored.problem());
        assertEquals(new Lineup.Role(1, 1, 1, 0), mirrored.roles().get(0));
        assertEquals(new Lineup.Role(1, 0, 1, 0), mirrored.roles().get(3));
        // No transformer: it distributes at its incoming tier.
        assertTrue(plan(I, M, F).roles().stream().allMatch(r -> r.dropIn() == 0 && r.dropOut() == 0));
    }

    @Test
    void doubleEndedAroundItsTie() {
        Lineup.Plan p = plan(I, X, M, F, T, F, F, M, X, I);
        assertTrue(p.valid() && p.doubleEnded());
        assertEquals(new Lineup.Role(0, 1, 1, 0), p.roles().get(4));
        assertEquals(new Lineup.Role(1, 1, 1, 1), p.roles().get(5));
        assertEquals(new Lineup.Role(1, 0, 0, 1), p.roles().get(9));
        // Halves at different tiers can't be tied.
        assertEquals(Lineup.Problem.TIE_TIERS, plan(I, X, M, F, T, F, M, I).problem());
        assertEquals(Lineup.Problem.WRONG_ORDER, plan(I, M, F, T, F, T, F, M, I).problem());
    }

    @Test
    void problemsAreNamed() {
        assertEquals(Lineup.Problem.NO_MAIN, plan(I, F, F).problem());
        assertEquals(Lineup.Problem.NO_INCOMING, plan(M, F, F).problem());
        assertEquals(Lineup.Problem.NO_FEEDER, plan(I, M).problem());
        assertEquals(Lineup.Problem.WRONG_ORDER, plan(I, M, F, X).problem());
        assertEquals(Lineup.Problem.WRONG_ORDER, plan(F, I, M, F).problem());
        assertEquals(Lineup.Problem.MIXED_TIER, Lineup.plan(List.of(I, M, F), List.of(1, 1, 2), 8).problem());
        assertEquals(Lineup.Problem.TOO_MANY_FEEDERS, Lineup.plan(List.of(I, M, F, F, F), List.of(1, 1, 1, 1, 1), 2).problem());
        assertTrue(Lineup.plan(List.of(I, M, F, F), List.of(1, 1, 1, 1), 2).valid());
    }
}
