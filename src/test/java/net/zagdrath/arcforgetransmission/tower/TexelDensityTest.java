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

import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// No mixels: every quad of the towers and the transformer shows the sheet at 16 texels per block (1 texel per px) along
// both of its directions, so no texel is ever stretched or squashed against the vanilla block grid. A direction the
// sheet doesn't change along (a ramp's rows, all alike) maps no texels and is fine at any length.
class TexelDensityTest {
    private static final int STRIDE = 8;

    @Test
    void everyQuadIsOneTexelPerPixel() {
        List<String> bad = new ArrayList<>();
        for (TowerMesh.Design design : TowerMesh.Design.values()) {
            check(design.name(), TowerMesh.get(design).solid(), bad);
        }
        check("POLE_TRANSFORMER", TowerMesh.poleTransformer().solid(), bad);
        assertTrue(bad.isEmpty(), bad.size() + " quads off 1 texel per px, e.g. " + bad.subList(0, Math.min(8, bad.size())));
    }

    private static void check(String name, float[] q, List<String> bad) {
        for (int i = 0; i + 4 * STRIDE <= q.length; i += 4 * STRIDE) {
            for (int e = 0; e < 4; e++) {
                int a = i + e * STRIDE, b = i + ((e + 1) % 4) * STRIDE;
                double dx = (q[b] - q[a]) * 16, dy = (q[b + 1] - q[a + 1]) * 16, dz = (q[b + 2] - q[a + 2]) * 16;
                double px = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double du = (q[b + 3] - q[a + 3]) * TowerMesh.SHEET_W, dv = (q[b + 4] - q[a + 4]) * TowerMesh.SHEET_H;
                double texels = Math.sqrt(du * du + dv * dv);
                if (px < 0.05 || texels < 1.0E-3) {
                    continue;
                }
                if (Math.abs(texels / px - 1.0) > 0.02) {
                    bad.add(String.format("%s quad %d edge %d: %.2f texels over %.2f px", name, i / (4 * STRIDE), e, texels, px));
                }
            }
        }
    }
}
