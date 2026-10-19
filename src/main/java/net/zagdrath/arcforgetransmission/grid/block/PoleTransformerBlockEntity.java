/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.Port;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.data.AttachGroups;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.grid.world.Insulator;
import net.zagdrath.arcforgetransmission.tower.TowerPlacement;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// The Pole-Mount Transformer: three-phase in on its primaries (A, B, C on the lid), split phase out on its secondary
// (L1, N and L2 on the front: two single-phase circuits, L1 + N and L2 + N, sharing the neutral). Between them a small
// FE buffer: the primary takes what fits, each secondary circuit gives half of what's held, so it never makes energy
// (and a tick passes between in and out). LV both sides.
public class PoleTransformerBlockEntity extends GridEndpointBlockEntity implements GridManager.PortSource {
    // Insulator order (the attach file's): primaries A B C, then secondaries L1 N L2.
    private static final int L1 = 3, N = 4, L2 = 5;

    private int buffer;
    private FlowSolver.PortStatus primaryStatus = FlowSolver.PortStatus.UNWIRED;
    private double lastIn, lastOut;

    public PoleTransformerBlockEntity(BlockPos pos, BlockState state) {
        super(GridContent.POLE_TRANSFORMER_ENTITY.get(), pos, state);
    }

    public Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(PoleTransformerBlock.FACING) ? state.getValue(PoleTransformerBlock.FACING) : Direction.NORTH;
    }

    public int buffer() {
        return buffer;
    }

    public FlowSolver.PortStatus primaryStatus() {
        return primaryStatus;
    }

    public double lastIn() {
        return lastIn;
    }

    public double lastOut() {
        return lastOut;
    }

    // Its insulators, worked out once per facing.
    private @Nullable List<Insulator> insulatorCache;
    private @Nullable Direction cacheFacing;

    @Override
    public List<Insulator> insulators() {
        if (insulatorCache == null || cacheFacing != facing()) {
            cacheFacing = facing();
            insulatorCache = List.copyOf(buildInsulators());
        }
        return insulatorCache;
    }

    private List<Insulator> buildInsulators() {
        int turns = TowerPlacement.turns(facing());
        List<Insulator> out = new ArrayList<>();
        List<AttachGroups.Group> groups = AttachGroups.get("pole_transformer").groups();
        for (int g = 0; g < groups.size(); g++) {
            for (AttachGroups.Terminal terminal : groups.get(g).terminals()) {
                double[] p = terminal.pos();
                double[] t = TowerMesh.turnedPoint((float) p[0], (float) p[1], (float) p[2], turns);
                out.add(new Insulator(VoltageTier.LV, terminal.phase(),
                        new Vec3(worldPosition.getX() + t[0], worldPosition.getY() + t[1], worldPosition.getZ() + t[2]), g));
            }
        }
        return out;
    }

    @Override
    public void ports(ServerLevel level, GridData.Dimension dimension, List<GridManager.PortHandler> out) {
        Long[] ids = new Long[6];
        for (int i = 0; i < 6; i++) {
            ids[i] = dimension.nodeAt(new GridData.Anchor(worldPosition, i));
            if (ids[i] == null) {
                return;
            }
        }
        int capacity = GridConfig.TRANSFORMER_BUFFER.getAsInt();
        buffer = Math.min(buffer, capacity);
        int half = buffer / 2;
        lastIn = 0;
        lastOut = 0;
        out.add(new Side(List.of(ids[0], ids[1], ids[2]), 0, capacity - buffer, true));
        out.add(new Side(List.of(ids[L1], ids[N]), half, 0, false));
        out.add(new Side(List.of(ids[L2], ids[N]), buffer - half, 0, false));
    }

    // One side of the transformer as a port.
    private final class Side implements GridManager.PortHandler {
        private final List<Long> nodes;
        private final int offer, demand;
        private final boolean primary;

        Side(List<Long> nodes, int offer, int demand, boolean primary) {
            this.nodes = nodes;
            this.offer = offer;
            this.demand = demand;
            this.primary = primary;
        }

        @Override
        public Port port(long id) {
            return new Port(id, VoltageTier.LV, nodes, offer, demand, Double.POSITIVE_INFINITY);
        }

        @Override
        public int draw(int fe) {
            int got = Math.max(0, Math.min(Math.min(fe, offer), buffer));
            buffer -= got;
            setChanged();
            return got;
        }

        @Override
        public int deliver(int fe) {
            int got = Math.max(0, Math.min(fe, GridConfig.TRANSFORMER_BUFFER.getAsInt() - buffer));
            buffer += got;
            setChanged();
            return got;
        }

        @Override
        public void report(FlowSolver.PortStatus status, double drawn, double delivered) {
            if (primary) {
                primaryStatus = status;
                lastIn = delivered;
            } else {
                lastOut += drawn;
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("buffer", Codec.INT, buffer);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        buffer = Math.max(0, input.read("buffer", Codec.INT).orElse(0));
    }

    // The can, its brackets reaching into the pole's block, and its wires.
    public AABB getRenderBox() {
        return withWires(new AABB(worldPosition).inflate(1.0, 0.25, 1.0));
    }
}
