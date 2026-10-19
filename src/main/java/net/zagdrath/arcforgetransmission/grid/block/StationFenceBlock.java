/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import net.minecraft.world.level.block.FenceBlock;

// Station Fence: galvanized chain-link round a substation yard, joining like a fence. A fault near it is in a yard, where
// the ground rises with the fault current (GridYard): Ground Grid under the yard keeps it from shocking anyone.
public class StationFenceBlock extends FenceBlock {
    public StationFenceBlock(Properties properties) {
        super(properties);
    }
}
