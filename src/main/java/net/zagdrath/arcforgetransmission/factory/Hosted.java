/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.zagdrath.arcforgetransmission.grid.world.GridData;

// A whole circuit mounted on another block that may host it (a lineup section, a busway tap-off: TerminationHost): a
// Cable Termination on any face, a busway feed-in on a roof. The host joins its conductors (A B C) to its own.
public interface Hosted {
    // The block it's mounted on.
    BlockPos mountPos();

    // Its conductors' nodes, A B C.
    List<Long> nodes(GridData.Dimension dimension);
}
