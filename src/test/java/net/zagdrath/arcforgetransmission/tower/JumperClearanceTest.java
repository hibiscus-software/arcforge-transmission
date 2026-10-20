/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.zagdrath.arcforgetransmission.grid.Catenary;
import net.zagdrath.arcforgetransmission.grid.data.AttachGroups;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// A drawn jumper (TowerTier.drawsJumpers) is a conductor hanging free: from each link pair's first terminal to its second
// it passes through none of its tower's steel, timber or concrete (the model's boxes; its own strain strings' boxes at its
// ends aside, where it's clamped). A pole (a tall box square in plan) is round: the jumper must clear its faces (a
// ten-sided pole's, inside the box's circle), not its box's corners.
class JumperClearanceTest {
    // How near an end (px) the jumper may touch its own string and clamp.
    private static final double END = 4.0;

    @Test
    void jumpersHangClearOfTheirTowers() {
        List<String> clashes = new ArrayList<>();
        for (TowerTier tier : TowerTier.values()) {
            if (!tier.drawsJumpers()) {
                continue;
            }
            var built = TowerMesh.get(tier.design());
            List<float[]> solid = new ArrayList<>(built.boxes());
            solid.removeAll(built.insulators());
            var groups = AttachGroups.get(tier.id()).groups();
            int position = 0;
            List<double[][]> drawn = new ArrayList<>();
            for (int g = 0; g + 1 < groups.size(); g += 2) {
                var from = groups.get(g).terminals();
                var to = groups.get(g + 1).terminals();
                for (int k = 0; k < Math.min(from.size(), to.size()); k++) {
                    double[] a = from.get(k).pos(), b = to.get(k).pos();
                    double sag = 16 * (Catenary.jumperSag(tier.voltage()) + tier.jumperStep(position++));
                    double half = tier.voltage().wireWidthPx() / 2;
                    double[][] curve = new double[201][];
                    for (int i = 0; i <= 200; i++) {
                        curve[i] = Catenary.point(a, b, i / 200.0, sag);
                    }
                    // Nor through another of its jumpers (a transposition's cross).
                    for (double[][] other : drawn) {
                        for (double[] p : curve) {
                            for (double[] q : other) {
                                if (Catenary.distance(p, q) < 2 * half + 0.5) {
                                    clashes.add(tier.id() + " jumper " + g + "/" + k + " meets another at " + java.util.Arrays.toString(p));
                                }
                            }
                        }
                    }
                    drawn.add(curve);
                    for (int i = 0; i <= 200; i++) {
                        double[] p = Catenary.point(a, b, i / 200.0, sag);
                        if (Catenary.distance(p, a) < END || Catenary.distance(p, b) < END) {
                            continue;
                        }
                        for (float[] box : solid) {
                            double w = box[3] - box[0], d = box[5] - box[2];
                            if (box[4] - box[1] > 64 && Math.abs(w - d) < 1.0E-3) {
                                double r = w / 2 * Math.cos(Math.PI / 10), dx = p[0] - (box[0] + box[3]) / 2, dz = p[2] - (box[2] + box[5]) / 2;
                                if (p[1] > box[1] && p[1] < box[4] && Math.hypot(dx, dz) < r + half) {
                                    clashes.add(tier.id() + " jumper " + g + "/" + k + " t=" + i + " at " + java.util.Arrays.toString(p) + " by pole "
                                            + java.util.Arrays.toString(box));
                                    break;
                                }
                                continue;
                            }
                            if (p[0] > box[0] - half && p[0] < box[3] + half && p[1] > box[1] - half && p[1] < box[4] + half && p[2] > box[2] - half
                                    && p[2] < box[5] + half) {
                                clashes.add(tier.id() + " jumper " + g + "/" + k + " t=" + i + " at " + java.util.Arrays.toString(p) + " in " + java.util.Arrays.toString(box));
                                break;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(clashes.isEmpty(), clashes.size() + " clashes, first: " + (clashes.isEmpty() ? "" : clashes));
    }
}
