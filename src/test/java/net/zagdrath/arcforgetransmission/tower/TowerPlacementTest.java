/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;

// Every structure works out its cells and block shapes at all eight orientations, quickly (a diagonal EHV lattice once
// built a shape too big to finish), and its main block is one of its cells with something in it.
class TowerPlacementTest {
    @Test
    void everyOrientationPlacesQuickly() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            for (TowerTier tier : TowerTier.values()) {
                for (int e = 0; e < 8; e++) {
                    var cells = TowerPlacement.cells(tier, e);
                    assertFalse(cells.isEmpty(), tier + " at " + e + " has no cells");
                    boolean mainFound = false;
                    BlockPos main = TowerPlacement.mainOffset(tier, e);
                    for (int[] c : cells) {
                        VoxelShape shape = TowerPlacement.shape(tier, e, new BlockPos(c[0], c[1], c[2]));
                        assertFalse(shape.isEmpty(), tier + " at " + e + ": an empty cell " + c[0] + " " + c[1] + " " + c[2]);
                        mainFound |= main.equals(new BlockPos(c[0], c[1], c[2]));
                    }
                    assertTrue(mainFound, tier + " at " + e + ": its main block isn't one of its cells");
                    TowerPlacement.frameBoxes(tier, e, BlockPos.ZERO);
                    TowerPlacement.insulatorBoxes(tier, e, BlockPos.ZERO);
                }
            }
        });
    }
}
