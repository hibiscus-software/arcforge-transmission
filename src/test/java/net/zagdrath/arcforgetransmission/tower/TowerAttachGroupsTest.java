/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.zagdrath.arcforgetransmission.grid.CircuitType;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.data.AttachGroups;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// The towers' attach groups (data/arcforgetransmission/attach_groups) match their models: every terminal and reserved
// point lies on the model's surface (an insulator's tip, a clamp's face), and each tower has the circuits Phase 1 uses.
class TowerAttachGroupsTest {
    private static final String[] MODELS = { "lv_distribution_pole", "mv_sub_transmission_pole", "hv_h_frame_tower", "ev_lattice_tower" };

    @Test
    void everyTerminalIsOnTheModel() {
        for (TowerMesh.Design design : TowerMesh.Design.values()) {
            String model = MODELS[design.ordinal()];
            float[] quads = TowerMesh.get(design).solid();
            AttachGroups.Model groups = AttachGroups.get(model);
            for (AttachGroups.Group group : groups.groups()) {
                for (AttachGroups.Terminal terminal : group.terminals()) {
                    double d = distanceToSurface(quads, terminal.pos());
                    assertTrue(d < 0.05, model + " " + group.name() + " " + terminal.label() + " is " + d + " px off the model");
                }
            }
            for (AttachGroups.Reserved point : groups.reserved()) {
                double d = distanceToSurface(quads, point.pos());
                assertTrue(d < 0.05, model + " " + point.name() + " is " + d + " px off the model");
            }
        }
    }

    // The Pole-Mount Transformer's primaries' tops and secondaries' tips are on its model too.
    @Test
    void theTransformersTerminalsAreOnItsModel() {
        float[] quads = TowerMesh.poleTransformer().solid();
        AttachGroups.Model groups = AttachGroups.get("pole_transformer");
        assertEquals(2, groups.groups().size());
        for (AttachGroups.Group group : groups.groups()) {
            assertEquals(3, group.terminals().size());
            for (AttachGroups.Terminal terminal : group.terminals()) {
                double d = distanceToSurface(quads, terminal.pos());
                assertTrue(d < 0.05, "pole_transformer " + terminal.label() + " is " + d + " px off the model");
            }
        }
        assertEquals(List.of(Phase.L1, Phase.N, Phase.L2), groups.groups().get(1).terminals().stream().map(AttachGroups.Terminal::phase).toList());
    }

    @Test
    void eachTowerHasItsCircuits() {
        assertEquals(2, AttachGroups.get("lv_distribution_pole").groups().size(), "LV groups");
        assertEquals(1, AttachGroups.get("mv_sub_transmission_pole").groups().size(), "MV circuits");
        assertEquals(1, AttachGroups.get("hv_h_frame_tower").groups().size(), "HV circuits");
        assertEquals(2, AttachGroups.get("ev_lattice_tower").groups().size(), "EV circuits");
        for (String model : MODELS) {
            for (AttachGroups.Group group : AttachGroups.get(model).groups()) {
                if (group.name().equals("pole_top")) {
                    continue;
                }
                assertEquals(CircuitType.THREE_PHASE, group.circuit(), model + " " + group.name());
                assertEquals(List.of(Phase.A, Phase.B, Phase.C), group.terminals().stream().map(AttachGroups.Terminal::phase).toList(),
                        model + " " + group.name() + " phases");
            }
        }
        // The LV pole-top pin is the neutral for the pole-mount transformer's split phase.
        AttachGroups.Group top = AttachGroups.get("lv_distribution_pole").groups().get(1);
        assertEquals(CircuitType.SINGLE_PHASE, top.circuit());
        assertEquals(Phase.N, top.terminals().get(0).phase());
    }

    // The shortest distance (px) from p to any quad of the model (vertex data in blocks, 8 floats a vertex).
    static double distanceToSurface(float[] q, double[] p) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 31 < q.length; i += 32) {
            double[][] v = new double[4][];
            for (int k = 0; k < 4; k++) {
                v[k] = new double[] { q[i + 8 * k] * 16.0, q[i + 8 * k + 1] * 16.0, q[i + 8 * k + 2] * 16.0 };
            }
            for (double d : new double[] { triangle(p, v[0], v[1], v[2]), triangle(p, v[0], v[2], v[3]) }) {
                // A quad with two points the same is a triangle: its empty half has no distance.
                if (!Double.isNaN(d)) {
                    best = Math.min(best, d);
                }
            }
        }
        return best;
    }

    // Distance from p to triangle abc (the closest-point method from Real-Time Collision Detection).
    private static double triangle(double[] p, double[] a, double[] b, double[] c) {
        double[] ab = sub(b, a), ac = sub(c, a), ap = sub(p, a);
        double d1 = dot(ab, ap), d2 = dot(ac, ap);
        if (d1 <= 0 && d2 <= 0) {
            return length(sub(p, a));
        }
        double[] bp = sub(p, b);
        double d3 = dot(ab, bp), d4 = dot(ac, bp);
        if (d3 >= 0 && d4 <= d3) {
            return length(bp);
        }
        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            double v = d1 / (d1 - d3);
            return length(sub(p, add(a, scale(ab, v))));
        }
        double[] cp = sub(p, c);
        double d5 = dot(ab, cp), d6 = dot(ac, cp);
        if (d6 >= 0 && d5 <= d6) {
            return length(cp);
        }
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            double w = d2 / (d2 - d6);
            return length(sub(p, add(a, scale(ac, w))));
        }
        double va = d3 * d6 - d5 * d4;
        if (va <= 0 && d4 - d3 >= 0 && d5 - d6 >= 0) {
            double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            return length(sub(p, add(b, scale(sub(c, b), w))));
        }
        double denom = 1.0 / (va + vb + vc);
        double v = vb * denom, w = vc * denom;
        return length(sub(p, add(a, add(scale(ab, v), scale(ac, w)))));
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] };
    }

    private static double[] add(double[] a, double[] b) {
        return new double[] { a[0] + b[0], a[1] + b[1], a[2] + b[2] };
    }

    private static double[] scale(double[] a, double s) {
        return new double[] { a[0] * s, a[1] * s, a[2] * s };
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double length(double[] a) {
        return Math.sqrt(dot(a, a));
    }
}
