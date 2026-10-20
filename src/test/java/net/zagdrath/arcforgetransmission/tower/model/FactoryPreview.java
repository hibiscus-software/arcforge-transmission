/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower.model;

import java.util.Locale;

// Factory distribution's preview scenes for ModelPreview (./gradlew renderPreviews -Pdesigns=F_TERM_LV#3,...): several
// models placed together, as they stand in game, with plain blocks for what they're mounted on. Not a test.
//   TERM_<tier>[_SIDE|_BOTTOM]   a Cable Termination on top of a block, on a wall bracket, or hanging under one
//   TERMS_<tier>                 a run of them along a wall, and two standing on blocks in front
//   SEC_<section>_<tier>[_<state>]  a lineup's section close up, its operators closed (or OPEN, TRIPPED)
//   LINEUP_<tier>                a double-ended lineup, the tie open, a feeder tripped and one open
//   SINGLE_<tier>                a single-ended lineup with no transformer
final class FactoryPreview {
    private FactoryPreview() {}

    static String title(String scene) {
        return scene;
    }

    static float[] scene(String scene) {
        String[] parts = scene.split("_");
        Scene out = new Scene();
        switch (parts[0]) {
            case "TERM" -> {
                // One termination close up: TERM_<tier> on top of a block, TERM_<tier>_SIDE on a wall's bracket,
                // TERM_<tier>_BOTTOM hanging under a block.
                int tier = tier(parts[1]);
                int h = FactoryMesh.terminationHeight(tier);
                String mount = parts.length > 2 ? parts[2] : "TOP";
                switch (mount) {
                    case "SIDE" -> {
                        for (int y = 0; y < h + 1; y++) {
                            out.block(0, y, 1);
                        }
                        out.add(FactoryMesh.termination(tier, FactoryMesh.Mount.SIDE).solid(), 0, 0, 0);
                    }
                    case "BOTTOM" -> {
                        out.block(0, h, 0);
                        out.add(FactoryMesh.termination(tier, FactoryMesh.Mount.BOTTOM).solid(), 0, h - 1, 0);
                    }
                    default -> {
                        out.block(0, 0, 0);
                        out.add(FactoryMesh.termination(tier, FactoryMesh.Mount.TOP).solid(), 0, 1, 0);
                    }
                }
            }
            case "TERMS" -> {
                // A run: four terminations along a wall on their brackets, and two on top of the blocks in front.
                int tier = tier(parts[1]);
                int h = FactoryMesh.terminationHeight(tier);
                for (int x = 0; x < 4; x++) {
                    for (int y = 0; y < h + 1; y++) {
                        out.block(x, y, 1);
                    }
                    out.add(FactoryMesh.termination(tier, FactoryMesh.Mount.SIDE).solid(), x, 0, 0);
                }
                for (int x = 0; x < 4; x += 2) {
                    out.block(x, 0, -2);
                    out.add(FactoryMesh.termination(tier, FactoryMesh.Mount.TOP).solid(), x, 1, -2);
                }
            }
            case "SEC" -> {
                // One section close up, its operators all in one state: SEC_<section>_<tier>[_OPEN|_TRIPPED].
                FactoryMesh.Section section = FactoryMesh.Section.valueOf(parts[1]);
                int tier = tier(parts[2]);
                FactoryMesh.OpState state = parts.length > 3 ? FactoryMesh.OpState.valueOf(parts[3]) : FactoryMesh.OpState.CLOSED;
                out.section(section, tier, 0, state);
            }
            case "LINEUP" -> {
                // A double-ended lineup: incomer, (transformer), main, feeders, the tie (open), feeders, main, (transformer),
                // incomer; one feeder tripped, one open, the rest closed.
                int tier = tier(parts[1]);
                java.util.List<FactoryMesh.Section> order = new java.util.ArrayList<>();
                order.add(FactoryMesh.Section.INCOMING);
                if (tier > 0) {
                    order.add(FactoryMesh.Section.TRANSFORMER);
                }
                order.addAll(java.util.List.of(FactoryMesh.Section.MAIN, FactoryMesh.Section.FEEDER, FactoryMesh.Section.FEEDER, FactoryMesh.Section.TIE,
                        FactoryMesh.Section.FEEDER, FactoryMesh.Section.MAIN));
                if (tier > 0) {
                    order.add(FactoryMesh.Section.TRANSFORMER);
                }
                order.add(FactoryMesh.Section.INCOMING);
                int w = FactoryMesh.sectionWidth(tier), feeders = 0;
                for (int i = 0; i < order.size(); i++) {
                    FactoryMesh.Section section = order.get(i);
                    FactoryMesh.OpState state = FactoryMesh.OpState.CLOSED;
                    if (section == FactoryMesh.Section.TIE) {
                        state = FactoryMesh.OpState.OPEN;
                    } else if (section == FactoryMesh.Section.FEEDER) {
                        state = feeders == 1 ? FactoryMesh.OpState.TRIPPED : feeders == 2 ? FactoryMesh.OpState.OPEN : FactoryMesh.OpState.CLOSED;
                        feeders++;
                    }
                    out.section(section, tier, i * w, state);
                }
            }
            case "SINGLE" -> {
                // A single-ended lineup with no transformer (it distributes at its incoming tier): incomer, main, three
                // feeders, the middle one open.
                int tier = tier(parts[1]);
                int w = FactoryMesh.sectionWidth(tier);
                FactoryMesh.Section[] order = { FactoryMesh.Section.INCOMING, FactoryMesh.Section.MAIN, FactoryMesh.Section.FEEDER,
                        FactoryMesh.Section.FEEDER, FactoryMesh.Section.FEEDER };
                for (int i = 0; i < order.length; i++) {
                    out.section(order[i], tier, i * w, i == 3 ? FactoryMesh.OpState.OPEN : FactoryMesh.OpState.CLOSED);
                }
            }
            default -> throw new IllegalArgumentException("No factory scene " + scene);
        }
        return out.solid();
    }

    static int tier(String id) {
        return switch (id.toUpperCase(Locale.ROOT)) {
            case "LV" -> 0;
            case "MV" -> 1;
            case "HV" -> 2;
            default -> 3;
        };
    }

    // Models put together, each moved by whole blocks.
    static final class Scene {
        private final MeshBuilder.Floats floats = new MeshBuilder.Floats();
        private static float[] cube;

        void add(float[] solid, float dx, float dy, float dz) {
            for (int i = 0; i + 7 < solid.length; i += 8) {
                floats.add(solid[i] + dx, solid[i + 1] + dy, solid[i + 2] + dz, solid[i + 3], solid[i + 4], solid[i + 5], solid[i + 6], solid[i + 7]);
            }
        }

        // A section at block x along the lineup, with its operators in one state.
        void section(FactoryMesh.Section section, int tier, int x, FactoryMesh.OpState state) {
            add(FactoryMesh.section(section, tier).solid(), x, 0, 0);
            for (FactoryMesh.Operator op : FactoryMesh.operators(section, tier)) {
                add(FactoryMesh.operator(op.op(), tier, state).solid(), x + op.x() / 16, op.y() / 16, op.z() / 16);
            }
        }

        // A plain concrete block, for what something is mounted on.
        void block(int x, int y, int z) {
            if (cube == null) {
                TowerMesh m = new TowerMesh();
                m.concrete(0, 0, 0, 16, 16, 16);
                cube = m.solid.toArray();
            }
            add(cube, x, y, z);
        }

        float[] solid() {
            return floats.toArray();
        }
    }
}
