/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.world;

import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// One insulator a block offers the grid: its tier, the phase label it starts with, where wires attach (world), which of
// the block's groups it's in (the wrench's B/C swap works across a group), and its stable key (its group's name and its
// terminal's label: what the grid's save knows it by, InsulatorMatch).
public record Insulator(VoltageTier tier, Phase phase, Vec3 at, int group, String key) {
    public Insulator(VoltageTier tier, Phase phase, Vec3 at, int group) {
        this(tier, phase, at, group, "");
    }
}
