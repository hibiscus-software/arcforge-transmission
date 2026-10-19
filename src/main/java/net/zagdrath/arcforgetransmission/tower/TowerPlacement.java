/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// Where a placed structure's blocks go and what each one's shape is, from its model (TowerMesh): the model's shape boxes
// (one uniform box per pole, crossarm, peak, lattice bay or tank, and one per insulator), turned to the structure's
// orientation and cut into the block cells they reach. TowerOutline draws the whole frame as one outline, or the
// insulator looked at.
//
// A structure stands on its centre column's base block (where it was placed), oriented in eighths of a turn clockwise
// from north (seen from above): FACING gives the quarter turns, DIAGONAL adds an eighth, so lines can turn corners at
// 45 degrees as signs do. The model is drawn facing north; the line runs along its z. The main block (which draws it and
// holds the rest together) sits in the model's main cell, turned with it.
//
// Everything worked out here is cached per tier and orientation: cells, per-cell boxes, shapes, insulator and frame
// boxes (in the model's frame about its centre column; moved to the world when asked).
public final class TowerPlacement {
    private TowerPlacement() {}

    // A model box cut into pieces no longer than this (px) along its longer side before turning by an eighth, so a
    // diagonal crossarm's cells and shape follow it closely rather than one fat box round it.
    private static final float PIECE = 4.0F;

    private record Key(TowerTier tier, int eighths) {}

    private record ShapeKey(TowerTier tier, int eighths, BlockPos offset) {}

    // The model's turned shape boxes (px, from the centre column's base block) by the cell they reach into.
    private static final Map<Key, Map<Long, List<float[]>>> BY_CELL = new ConcurrentHashMap<>();
    private static final Map<Key, List<int[]>> CELLS = new ConcurrentHashMap<>();
    private static final Map<ShapeKey, VoxelShape> SHAPES = new ConcurrentHashMap<>();
    private static final Map<Key, List<float[]>> INSULATORS = new ConcurrentHashMap<>(), FRAMES = new ConcurrentHashMap<>();

    // Quarter turns clockwise (seen from above) from north to facing.
    public static int turns(Direction facing) {
        return (facing.get2DDataValue() + 2) & 3;
    }

    // The facing a number of quarter turns from north.
    public static Direction facingOf(int turns) {
        return Direction.from2DDataValue((turns + 2) & 3);
    }

    // A structure's orientation, eighths of a turn clockwise from north.
    public static int eighths(BlockState state) {
        Direction facing = state.hasProperty(TowerBlock.FACING) ? state.getValue(TowerBlock.FACING) : Direction.NORTH;
        boolean diagonal = state.hasProperty(TowerBlock.DIAGONAL) && state.getValue(TowerBlock.DIAGONAL);
        return turns(facing) * 2 + (diagonal ? 1 : 0);
    }

    // A state oriented `eighths` eighths of a turn from north.
    public static BlockState oriented(BlockState state, int eighths) {
        int e = Math.floorMod(eighths, 8);
        return state.setValue(TowerBlock.FACING, facingOf(e / 2)).setValue(TowerBlock.DIAGONAL, e % 2 == 1);
    }

    // A px point turned about the centre column's middle (8, 8).
    private static double[] turn(double x, double z, int eighths) {
        double[] t = TowerMesh.turnedPoint8((float) x, 0, (float) z, eighths);
        return new double[] { t[0] * 16.0, t[2] * 16.0 };
    }

    // The main block's offset from the centre column's base.
    public static BlockPos mainOffset(TowerTier tier, int eighths) {
        TowerMesh.Design design = tier.design();
        double[] c = turn(design.mainX * 16 + 8, design.mainZ * 16 + 8, eighths);
        return new BlockPos((int) Math.floor(c[0] / 16.0), 0, (int) Math.floor(c[1] / 16.0));
    }

    // The centre column's base for a structure whose main block is at main.
    public static BlockPos centre(TowerTier tier, int eighths, BlockPos main) {
        return main.subtract(mainOffset(tier, eighths));
    }

    // Every block of a structure standing on centre, the main block's first.
    public static List<BlockPos> positions(TowerTier tier, int eighths, BlockPos centre) {
        List<BlockPos> positions = new ArrayList<>();
        BlockPos main = centre.offset(mainOffset(tier, eighths));
        positions.add(main);
        for (int[] cell : cells(tier, eighths)) {
            BlockPos pos = centre.offset(cell[0], cell[1], cell[2]);
            if (!pos.equals(main)) {
                positions.add(pos);
            }
        }
        return positions;
    }

    // A model box (px) turned: an axis-aligned box round it (for an eighth turn, round each short piece of it).
    private static List<float[]> turned(float[] b, int eighths) {
        List<float[]> pieces = new ArrayList<>();
        if (eighths % 2 == 0) {
            pieces.add(b);
        } else {
            boolean alongX = b[3] - b[0] >= b[5] - b[2];
            float length = alongX ? b[3] - b[0] : b[5] - b[2];
            int n = Math.max(1, (int) Math.ceil(length / PIECE - 1.0E-3));
            for (int i = 0; i < n; i++) {
                float a0 = (alongX ? b[0] : b[2]) + length * i / n, a1 = (alongX ? b[0] : b[2]) + length * (i + 1) / n;
                pieces.add(alongX ? new float[] { a0, b[1], b[2], a1, b[4], b[5] } : new float[] { b[0], b[1], a0, b[3], b[4], a1 });
            }
        }
        List<float[]> out = new ArrayList<>(pieces.size());
        for (float[] p : pieces) {
            float x0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
            for (float[] corner : new float[][] { { p[0], p[2] }, { p[3], p[2] }, { p[0], p[5] }, { p[3], p[5] } }) {
                double[] t = turn(corner[0], corner[1], eighths);
                x0 = Math.min(x0, (float) t[0]);
                z0 = Math.min(z0, (float) t[1]);
                x1 = Math.max(x1, (float) t[0]);
                z1 = Math.max(z1, (float) t[1]);
            }
            out.add(new float[] { x0, p[1], z0, x1, p[4], z1 });
        }
        return out;
    }

    private static Map<Long, List<float[]>> byCell(TowerTier tier, int eighths) {
        return BY_CELL.computeIfAbsent(new Key(tier, eighths), key -> {
            Map<Long, List<float[]>> map = new HashMap<>();
            float e = 0.05F;
            for (float[] shape : TowerMesh.get(tier.design()).shapes()) {
                for (float[] b : turned(shape, eighths)) {
                    for (int x = (int) Math.floor((b[0] + e) / 16.0F); x <= (int) Math.floor((b[3] - e) / 16.0F); x++) {
                        for (int y = Math.max(0, (int) Math.floor((b[1] + e) / 16.0F)); y <= (int) Math.floor((b[4] - e) / 16.0F); y++) {
                            for (int z = (int) Math.floor((b[2] + e) / 16.0F); z <= (int) Math.floor((b[5] - e) / 16.0F); z++) {
                                map.computeIfAbsent(cellKey(x, y, z), k -> new ArrayList<>()).add(b);
                            }
                        }
                    }
                }
            }
            return map;
        });
    }

    // The cells the turned model reaches into (from the centre column's base block).
    public static List<int[]> cells(TowerTier tier, int eighths) {
        return CELLS.computeIfAbsent(new Key(tier, eighths), key -> {
            Set<Long> keys = new LinkedHashSet<>(byCell(tier, eighths).keySet());
            List<int[]> out = new ArrayList<>(keys.size());
            for (long k : keys) {
                out.add(new int[] { (int) ((k >> 42) & 0x1FFFFF) - 1024, (int) ((k >> 21) & 0x1FFFFF) - 1024, (int) (k & 0x1FFFFF) - 1024 });
            }
            return List.copyOf(out);
        });
    }

    private static long cellKey(int x, int y, int z) {
        return ((long) (x + 1024) << 42) | ((long) (y + 1024) << 21) | (z + 1024);
    }

    // The shape of the block `offset` from the centre column's base: the turned model's boxes in it, cut to the block.
    // Empty where the structure has nothing.
    public static VoxelShape shape(TowerTier tier, int eighths, BlockPos offset) {
        return SHAPES.computeIfAbsent(new ShapeKey(tier, eighths, offset.immutable()), key -> {
            List<float[]> boxes = byCell(tier, eighths).getOrDefault(cellKey(offset.getX(), offset.getY(), offset.getZ()), List.of());
            VoxelShape shape = Shapes.empty();
            for (float[] box : boxes) {
                // Snapped outward to whole pixels: every shape stays on the 16-step block grid, so merging dozens of boxes
                // stays cheap (fractional edges would each split the grid), and no thin member vanishes.
                double bx0 = snapDown(box[0] - offset.getX() * 16.0), bx1 = snapUp(box[3] - offset.getX() * 16.0);
                double by0 = snapDown(box[1] - offset.getY() * 16.0), by1 = snapUp(box[4] - offset.getY() * 16.0);
                double bz0 = snapDown(box[2] - offset.getZ() * 16.0), bz1 = snapUp(box[5] - offset.getZ() * 16.0);
                if (bx1 > bx0 && by1 > by0 && bz1 > bz0) {
                    shape = Shapes.joinUnoptimized(shape, Shapes.box(bx0, by0, bz0, bx1, by1, bz1), BooleanOp.OR);
                }
            }
            return shape.optimize();
        });
    }

    // A px coordinate within the block, to a whole pixel below / above (the tolerance keeps exact edges where they are),
    // in block units, inside the block.
    private static double snapDown(double px) {
        return Math.max(0, Math.min(16, Math.floor(px + 1.0E-3))) / 16.0;
    }

    private static double snapUp(double px) {
        return Math.max(0, Math.min(16, Math.ceil(px - 1.0E-3))) / 16.0;
    }

    // A model box (px) turned and placed in the world (an axis-aligned box round it), from the main block.
    public static AABB worldBox(float[] b, TowerTier tier, int eighths, BlockPos main) {
        BlockPos centre = centre(tier, eighths, main);
        float[] t = whole(b, eighths);
        return new AABB(centre.getX() + t[0] / 16.0, centre.getY() + t[1] / 16.0, centre.getZ() + t[2] / 16.0, centre.getX() + t[3] / 16.0,
                centre.getY() + t[4] / 16.0, centre.getZ() + t[5] / 16.0);
    }

    // A whole model box turned (one box round it, not in pieces), px.
    private static float[] whole(float[] b, int eighths) {
        float x0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        for (float[] corner : new float[][] { { b[0], b[2] }, { b[3], b[2] }, { b[0], b[5] }, { b[3], b[5] } }) {
            double[] t = turn(corner[0], corner[1], eighths);
            x0 = Math.min(x0, (float) t[0]);
            z0 = Math.min(z0, (float) t[1]);
            x1 = Math.max(x1, (float) t[0]);
            z1 = Math.max(z1, (float) t[1]);
        }
        return new float[] { x0, b[1], z0, x1, b[4], z1 };
    }

    // The structure's insulators' boxes in the world, from its main block (cached turned; moved here).
    public static List<AABB> insulatorBoxes(TowerTier tier, int eighths, BlockPos main) {
        return place(INSULATORS.computeIfAbsent(new Key(tier, eighths), key -> turnedAll(TowerMesh.get(tier.design()).insulators(), eighths)), tier,
                eighths, main);
    }

    // Its frame (each pole, crossarm, peak, lattice bay or tank), in pieces where it's turned by an eighth, in the world.
    public static List<AABB> frameBoxes(TowerTier tier, int eighths, BlockPos main) {
        return place(FRAMES.computeIfAbsent(new Key(tier, eighths), key -> {
            TowerMesh.Built built = TowerMesh.get(tier.design());
            List<float[]> frame = new ArrayList<>();
            for (float[] b : built.shapes()) {
                if (!built.insulators().contains(b)) {
                    frame.addAll(turned(b, eighths));
                }
            }
            return List.copyOf(frame);
        }), tier, eighths, main);
    }

    private static List<float[]> turnedAll(List<float[]> boxes, int eighths) {
        List<float[]> out = new ArrayList<>(boxes.size());
        for (float[] b : boxes) {
            out.add(whole(b, eighths));
        }
        return List.copyOf(out);
    }

    private static List<AABB> place(List<float[]> boxes, TowerTier tier, int eighths, BlockPos main) {
        BlockPos centre = centre(tier, eighths, main);
        List<AABB> out = new ArrayList<>(boxes.size());
        for (float[] t : boxes) {
            out.add(new AABB(centre.getX() + t[0] / 16.0, centre.getY() + t[1] / 16.0, centre.getZ() + t[2] / 16.0, centre.getX() + t[3] / 16.0,
                    centre.getY() + t[4] / 16.0, centre.getZ() + t[5] / 16.0));
        }
        return out;
    }

    // The box the whole structure is drawn in (for culling), from its main block.
    public static AABB renderBox(TowerTier tier, int eighths, BlockPos main) {
        BlockPos centre = centre(tier, eighths, main);
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (float[] b : TowerMesh.get(tier.design()).boxes()) {
            float[] t = whole(b, eighths);
            x0 = Math.min(x0, t[0]);
            y0 = Math.min(y0, t[1]);
            z0 = Math.min(z0, t[2]);
            x1 = Math.max(x1, t[3]);
            y1 = Math.max(y1, t[4]);
            z1 = Math.max(z1, t[5]);
        }
        return new AABB(centre.getX() + x0 / 16.0, centre.getY() + y0 / 16.0, centre.getZ() + z0 / 16.0, centre.getX() + x1 / 16.0,
                centre.getY() + y1 / 16.0, centre.getZ() + z1 / 16.0).inflate(0.5);
    }
}
