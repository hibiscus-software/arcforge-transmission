/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.arcforgetransmission.tower.TowerPlacement;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.Section;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// Where a lineup section's blocks are and what shape each is, from its model (FactoryMesh.section): its main block at
// the model's origin cell, its front north in the model's frame turned to face FACING, the lineup running east (+x in the
// model) to its right as you face it.
public final class SectionGeometry {
    private SectionGeometry() {}

    private static final Map<String, List<int[]>> CELLS = new ConcurrentHashMap<>();
    private static final Map<String, VoxelShape> SHAPES = new ConcurrentHashMap<>();

    public static int turns(Direction facing) {
        return TowerPlacement.turns(facing);
    }

    // The model's cells (from its origin), its own first.
    public static List<int[]> cells(Section section, int tier) {
        return CELLS.computeIfAbsent(section + ":" + tier, k -> {
            List<int[]> out = new ArrayList<>();
            out.add(new int[] { 0, 0, 0 });
            for (int[] cell : FactoryMeshCells.of(FactoryMesh.section(section, tier))) {
                if (cell[0] != 0 || cell[1] != 0 || cell[2] != 0) {
                    out.add(cell);
                }
            }
            return List.copyOf(out);
        });
    }

    // A model cell's block, for a main block at `main` facing `facing`.
    public static BlockPos world(BlockPos main, Direction facing, int[] cell) {
        float[] p = FactoryShapes.turnedPoint(cell[0] * 16 + 8, cell[1] * 16 + 8, cell[2] * 16 + 8, turns(facing));
        return main.offset((int) Math.floor(p[0] / 16), (int) Math.floor(p[1] / 16), (int) Math.floor(p[2] / 16));
    }

    public static List<BlockPos> worldCells(Section section, int tier, BlockPos main, Direction facing) {
        List<BlockPos> out = new ArrayList<>();
        for (int[] cell : cells(section, tier)) {
            out.add(world(main, facing, cell));
        }
        return out;
    }

    // The model cell a block is, or null if it isn't one of the section's.
    public static int[] cellOf(Section section, int tier, BlockPos main, Direction facing, BlockPos pos) {
        for (int[] cell : cells(section, tier)) {
            if (world(main, facing, cell).equals(pos)) {
                return cell;
            }
        }
        return null;
    }

    // A cell's shape, in its block's own frame.
    public static VoxelShape shape(Section section, int tier, Direction facing, int[] cell) {
        return SHAPES.computeIfAbsent(section + ":" + tier + ":" + facing + ":" + cell[0] + "," + cell[1] + "," + cell[2], k -> {
            int turns = turns(facing);
            float[] c = FactoryShapes.turnedPoint(cell[0] * 16 + 8, cell[1] * 16 + 8, cell[2] * 16 + 8, turns);
            VoxelShape shape = FactoryShapes.cell(FactoryMesh.section(section, tier).shapes(), turns, (int) Math.floor(c[0] / 16), (int) Math.floor(c[1] / 16),
                    (int) Math.floor(c[2] / 16));
            return shape.isEmpty() ? net.minecraft.world.level.block.Block.box(6, 6, 6, 10, 10, 10) : shape;
        });
    }

    // A point in the model's frame (px) as a world position.
    public static Vec3 point(BlockPos main, Direction facing, float x, float y, float z) {
        float[] p = FactoryShapes.turnedPoint(x, y, z, turns(facing));
        return new Vec3(main.getX() + p[0] / 16.0, main.getY() + p[1] / 16.0, main.getZ() + p[2] / 16.0);
    }

    // A world position in the model's frame (px): the inverse of point().
    public static float[] local(BlockPos main, Direction facing, Vec3 at) {
        float x = (float) ((at.x - main.getX()) * 16), y = (float) ((at.y - main.getY()) * 16), z = (float) ((at.z - main.getZ()) * 16);
        return FactoryShapes.turnedPoint(x, y, z, 4 - turns(facing));
    }

    // The direction the lineup runs east in, for a section facing `facing` (to its right as you face its front).
    public static Direction east(Direction facing) {
        float[] p = FactoryShapes.turnedPoint(24, 8, 8, turns(facing));
        float[] o = FactoryShapes.turnedPoint(8, 8, 8, turns(facing));
        return Direction.getApproximateNearest(p[0] - o[0], 0, p[2] - o[2]);
    }

    // The cells a built model reaches into (TowerMesh.Built's shapes), its own included.
    static final class FactoryMeshCells {
        static List<int[]> of(TowerMesh.Built built) {
            List<int[]> cells = new ArrayList<>();
            java.util.Set<Long> seen = new java.util.HashSet<>();
            for (float[] b : built.shapes()) {
                for (int x = (int) Math.floor((b[0] + 0.05) / 16); x <= (int) Math.floor((b[3] - 0.05) / 16); x++) {
                    for (int y = (int) Math.floor((b[1] + 0.05) / 16); y <= (int) Math.floor((b[4] - 0.05) / 16); y++) {
                        for (int z = (int) Math.floor((b[2] + 0.05) / 16); z <= (int) Math.floor((b[5] - 0.05) / 16); z++) {
                            if (seen.add(((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512))) {
                                cells.add(new int[] { x, y, z });
                            }
                        }
                    }
                }
            }
            return cells;
        }
    }
}
