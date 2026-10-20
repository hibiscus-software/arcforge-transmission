/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// Every quad faces the way its normal says: the game culls a quad's back, so one wound the other way vanishes in game
// (and shows what's behind it) though a preview, drawing both sides, looks right.
class WindingTest {
    private static final int VERTEX = 8, QUAD = 4 * VERTEX;

    static Map<String, float[]> models() {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (int tier = 0; tier < 4; tier++) {
            for (FactoryMesh.Mount mount : FactoryMesh.Mount.values()) {
                out.put("termination " + tier + " " + mount, FactoryMesh.termination(tier, mount).solid());
            }
        }
        for (TowerMesh.Design design : TowerMesh.Design.values()) {
            out.put(design.name(), TowerMesh.get(design).solid());
        }
        return out;
    }

    // The quads (by their place in the model's parts) wound against their normal.
    static List<String> inverted(float[] solid) {
        List<String> bad = new ArrayList<>();
        for (int q = 0; q + QUAD <= solid.length; q += QUAD) {
            float[] p0 = at(solid, q), p1 = at(solid, q + VERTEX), p2 = at(solid, q + 2 * VERTEX), p3 = at(solid, q + 3 * VERTEX);
            // The quad's own normal (Newell), from its winding.
            float[][] p = { p0, p1, p2, p3 };
            double nx = 0, ny = 0, nz = 0;
            for (int i = 0; i < 4; i++) {
                float[] a = p[i], b = p[(i + 1) % 4];
                nx += (a[1] - b[1]) * (a[2] + b[2]);
                ny += (a[2] - b[2]) * (a[0] + b[0]);
                nz += (a[0] - b[0]) * (a[1] + b[1]);
            }
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1.0E-9) {
                continue;
            }
            double dot = (nx * solid[q + 5] + ny * solid[q + 6] + nz * solid[q + 7]) / len;
            if (dot < -0.1) {
                bad.add(String.format(java.util.Locale.ROOT, "quad %d at (%.1f %.1f %.1f) normal (%.2f %.2f %.2f)", q / QUAD, p0[0], p0[1], p0[2],
                        solid[q + 5], solid[q + 6], solid[q + 7]));
            }
        }
        return bad;
    }

    private static float[] at(float[] solid, int v) {
        return new float[] { solid[v], solid[v + 1], solid[v + 2] };
    }

    @Test
    void everyQuadFacesItsNormal() {
        List<String> problems = new ArrayList<>();
        models().forEach((name, solid) -> {
            List<String> bad = inverted(solid);
            if (!bad.isEmpty()) {
                problems.add(name + ": " + bad.size() + " of " + solid.length / QUAD + " inverted, e.g. " + bad.subList(0, Math.min(3, bad.size())));
            }
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
