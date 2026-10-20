/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.List;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

// Hitboxes from a model's own boxes (px, the model's frame), so the outline and collision match what's drawn.
public final class FactoryShapes {
    private FactoryShapes() {}

    // The boxes turned `turns` quarter turns clockwise seen from above (north to east, as TowerMesh.turned), clipped to
    // the cell (dx, dy, dz) blocks from the model's own, as that cell's shape (in its own 0-16 frame); empty if none
    // reaches into it.
    public static VoxelShape cell(List<float[]> boxes, int turns, int dx, int dy, int dz) {
        VoxelShape shape = Shapes.empty();
        double x0 = dx * 16.0, y0 = dy * 16.0, z0 = dz * 16.0;
        for (float[] b : boxes) {
            float[] t = turned(b, turns);
            double minX = Math.max(t[0], x0), minY = Math.max(t[1], y0), minZ = Math.max(t[2], z0);
            double maxX = Math.min(t[3], x0 + 16), maxY = Math.min(t[4], y0 + 16), maxZ = Math.min(t[5], z0 + 16);
            if (maxX - minX > 0.05 && maxY - minY > 0.05 && maxZ - minZ > 0.05) {
                shape = Shapes.or(shape, Block.box(minX - x0, minY - y0, minZ - z0, maxX - x0, maxY - y0, maxZ - z0));
            }
        }
        return shape.optimize();
    }

    // A box {x0, y0, z0, x1, y1, z1} (px) turned quarter turns about the block's middle.
    public static float[] turned(float[] b, int turns) {
        float x0 = b[0], z0 = b[2], x1 = b[3], z1 = b[5];
        for (int i = 0; i < Math.floorMod(turns, 4); i++) {
            // (x, z) -> (16 - z, x)
            float nx0 = 16 - z1, nx1 = 16 - z0, nz0 = x0, nz1 = x1;
            x0 = nx0;
            x1 = nx1;
            z0 = nz0;
            z1 = nz1;
        }
        return new float[] { x0, b[1], z0, x1, b[4], z1 };
    }

    // A point (px) turned the same way.
    public static float[] turnedPoint(float x, float y, float z, int turns) {
        for (int i = 0; i < Math.floorMod(turns, 4); i++) {
            float nx = 16 - z;
            z = x;
            x = nx;
        }
        return new float[] { x, y, z };
    }
}
