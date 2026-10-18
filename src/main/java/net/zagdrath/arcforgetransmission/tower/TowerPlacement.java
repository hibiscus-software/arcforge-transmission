/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// Where a transmission tower's blocks go and what each one's shape is, from its model (TowerMesh): the
// model's solid boxes (each member, slanted ones as a stair of 2 px steps) cut into the block cells they reach, so every
// part is hit and walked into only where the tower really is (between the H-frame's poles and through the lattice's
// open bays is open air).
//
// A tower stands on its centre column's base block (where it was placed) facing the way the player faced, the line's
// direction. The model is drawn facing north; facing east it's turned a quarter clockwise seen from above, and so on.
// The main block (which draws it and holds the rest together) sits in the model's main cell, a pole's or leg's foot.
public final class TowerPlacement {
    private TowerPlacement() {}

    // The model's boxes by the cell they reach into (cells from the centre column's base, unturned; boxes in px).
    private static final Map<TowerTier, Map<Long, List<float[]>>> BY_CELL = new EnumMap<>(TowerTier.class);
    private static final Map<ShapeKey, VoxelShape> SHAPES = new ConcurrentHashMap<>();

    private record ShapeKey(TowerTier tier, int turns, BlockPos offset) {}

    // Quarter turns clockwise (seen from above) from north to facing.
    public static int turns(Direction facing) {
        return (facing.get2DDataValue() + 2) & 3;
    }

    // A cell offset (x, z) from the centre column turned `turns` quarter turns clockwise: (x, z) -> (-z, x) each.
    private static BlockPos turn(int x, int y, int z, int turns) {
        for (int t = 0; t < turns; t++) {
            int nx = -z;
            z = x;
            x = nx;
        }
        return new BlockPos(x, y, z);
    }

    // The main block's offset from the centre column's base.
    public static BlockPos mainOffset(TowerTier tier, Direction facing) {
        TowerMesh.Design design = tier.design();
        return turn(design.mainX, 0, design.mainZ, turns(facing));
    }

    // The centre column's base for a tower whose main block is at main.
    public static BlockPos centre(TowerTier tier, Direction facing, BlockPos main) {
        return main.subtract(mainOffset(tier, facing));
    }

    // Every block of a tower standing on centre, the main block's first.
    public static List<BlockPos> positions(TowerTier tier, Direction facing, BlockPos centre) {
        List<BlockPos> positions = new ArrayList<>();
        BlockPos main = centre.offset(mainOffset(tier, facing));
        positions.add(main);
        int turns = turns(facing);
        for (int[] cell : TowerMesh.get(tier.design()).cells()) {
            BlockPos pos = centre.offset(turn(cell[0], cell[1], cell[2], turns));
            if (!pos.equals(main)) {
                positions.add(pos);
            }
        }
        return positions;
    }

    private static Map<Long, List<float[]>> byCell(TowerTier tier) {
        synchronized (BY_CELL) {
            return BY_CELL.computeIfAbsent(tier, t -> {
                Map<Long, List<float[]>> map = new HashMap<>();
                float e = 0.05F;
                for (float[] b : TowerMesh.get(t.design()).boxes()) {
                    for (int x = (int) Math.floor((b[0] + e) / 16.0F); x <= (int) Math.floor((b[3] - e) / 16.0F); x++) {
                        for (int y = Math.max(0, (int) Math.floor((b[1] + e) / 16.0F)); y <= (int) Math.floor((b[4] - e) / 16.0F); y++) {
                            for (int z = (int) Math.floor((b[2] + e) / 16.0F); z <= (int) Math.floor((b[5] - e) / 16.0F); z++) {
                                map.computeIfAbsent(key(x, y, z), k -> new ArrayList<>()).add(b);
                            }
                        }
                    }
                }
                return map;
            });
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 1024) << 42) | ((long) (y + 1024) << 21) | (z + 1024);
    }

    // The shape of the block `offset` from the centre column's base of a tower facing `facing`: the model's boxes in it,
    // cut to the block. Empty where the tower has nothing.
    public static VoxelShape shape(TowerTier tier, Direction facing, BlockPos offset) {
        int turns = turns(facing);
        ShapeKey cacheKey = new ShapeKey(tier, turns, offset.immutable());
        VoxelShape cached = SHAPES.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        // The cell in the model's (north-facing) frame: undo the turns, (x, z) -> (z, -x) each.
        int mx = offset.getX(), mz = offset.getZ();
        for (int t = 0; t < turns; t++) {
            int nx = mz;
            mz = -mx;
            mx = nx;
        }
        List<float[]> boxes = byCell(tier).getOrDefault(key(mx, offset.getY(), mz), List.of());
        VoxelShape shape = Shapes.empty();
        for (float[] box : boxes) {
            float x0 = box[0], z0 = box[2], x1 = box[3], z1 = box[5];
            for (int t = 0; t < turns; t++) {
                // 90° clockwise about the centre column's middle: (x, z) -> (16 - z, x).
                float nx0 = 16 - z1, nx1 = 16 - z0, nz0 = x0, nz1 = x1;
                x0 = nx0;
                x1 = nx1;
                z0 = nz0;
                z1 = nz1;
            }
            // Snapped outward to whole pixels: every shape stays on the 16-step block grid, so merging dozens of boxes
            // stays cheap (fractional edges would each split the grid), and no thin member vanishes.
            double bx0 = snapDown(x0 - offset.getX() * 16.0), bx1 = snapUp(x1 - offset.getX() * 16.0);
            double by0 = snapDown(box[1] - offset.getY() * 16.0), by1 = snapUp(box[4] - offset.getY() * 16.0);
            double bz0 = snapDown(z0 - offset.getZ() * 16.0), bz1 = snapUp(z1 - offset.getZ() * 16.0);
            if (bx1 > bx0 && by1 > by0 && bz1 > bz0) {
                shape = Shapes.joinUnoptimized(shape, Shapes.box(bx0, by0, bz0, bx1, by1, bz1), BooleanOp.OR);
            }
        }
        shape = shape.optimize();
        SHAPES.put(cacheKey, shape);
        return shape;
    }

    // A px coordinate within the block, to a whole pixel below / above (the tolerance keeps exact edges where they are),
    // in block units, inside the block.
    private static double snapDown(double px) {
        return Math.max(0, Math.min(16, Math.floor(px + 1.0E-3))) / 16.0;
    }

    private static double snapUp(double px) {
        return Math.max(0, Math.min(16, Math.ceil(px - 1.0E-3))) / 16.0;
    }

    // The box the whole tower is drawn in (for culling), from its main block.
    public static AABB renderBox(TowerTier tier, Direction facing, BlockPos main) {
        BlockPos centre = centre(tier, facing, main);
        int turns = turns(facing);
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (float[] b : TowerMesh.get(tier.design()).boxes()) {
            for (float[] corner : new float[][] { { b[0], b[1], b[2] }, { b[3], b[4], b[5] }, { b[0], b[1], b[5] }, { b[3], b[4], b[2] } }) {
                double[] p = TowerMesh.turnedPoint(corner[0], corner[1], corner[2], turns);
                x0 = Math.min(x0, p[0]);
                y0 = Math.min(y0, p[1]);
                z0 = Math.min(z0, p[2]);
                x1 = Math.max(x1, p[0]);
                y1 = Math.max(y1, p[1]);
                z1 = Math.max(z1, p[2]);
            }
        }
        return new AABB(centre.getX() + x0, centre.getY() + y0, centre.getZ() + z0, centre.getX() + x1, centre.getY() + y1, centre.getZ() + z1)
                .inflate(0.5);
    }
}
